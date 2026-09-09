package com.threadwork.app.identity

import com.sun.net.httpserver.HttpServer
import java.awt.Desktop
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Duration
import java.util.Base64
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

data class OAuthClientRegistration(
    val clientId: String,
    val clientSecret: String = "",
)

object OAuthClientConfiguration {
    fun registration(provider: OAuthProvider): OAuthClientRegistration {
        val prefix = provider.id
        return OAuthClientRegistration(
            clientId = setting(prefix, "clientId", "THREADWORK_${prefix.uppercase()}_CLIENT_ID"),
            clientSecret = setting(prefix, "clientSecret", "THREADWORK_${prefix.uppercase()}_CLIENT_SECRET"),
        )
    }

    private fun setting(provider: String, property: String, environment: String): String =
        System.getProperty("threadwork.oauth.$provider.$property").orEmpty().trim()
            .ifBlank { System.getenv(environment).orEmpty().trim() }
}

class DesktopOAuthClient(
    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(20))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build(),
    private val registrationProvider: (OAuthProvider) -> OAuthClientRegistration = OAuthClientConfiguration::registration,
    private val openBrowser: (URI) -> Unit = ::browse,
) {
    private val json = Json { ignoreUnknownKeys = true }

    fun authenticate(provider: OAuthProvider): OAuthUserProfile {
        val registration = registrationProvider(provider)
        require(registration.clientId.isNotBlank()) {
            "${provider.label} login is not configured. Set THREADWORK_${provider.id.uppercase()}_CLIENT_ID."
        }
        if (provider == OAuthProvider.GITHUB) {
            require(registration.clientSecret.isNotBlank()) {
                "GitHub login requires THREADWORK_GITHUB_CLIENT_SECRET for its OAuth code exchange."
            }
        }

        val callback = LoopbackOAuthCallback()
        return try {
            val state = randomUrlToken(32)
            val verifier = randomUrlToken(64)
            val challenge = base64Url(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()))
            val definition = providerDefinition(provider)
            val authorizationParameters = linkedMapOf(
                "client_id" to registration.clientId,
                "redirect_uri" to callback.redirectUri.toString(),
                "response_type" to "code",
                "scope" to definition.scope,
                "state" to state,
                "code_challenge" to challenge,
                "code_challenge_method" to "S256",
                "prompt" to "select_account",
            )
            if (provider == OAuthProvider.MICROSOFT) authorizationParameters["response_mode"] = "query"
            openBrowser(uriWithQuery(definition.authorizationEndpoint, authorizationParameters))
            val response = callback.await()
            require(response["state"] == state) { "OAuth callback state did not match the login request." }
            response["error"]?.let { error ->
                error("${provider.label} login failed: ${response["error_description"] ?: error}")
            }
            val code = response["code"].orEmpty()
            require(code.isNotBlank()) { "${provider.label} did not return an authorization code." }
            val token = exchangeCode(definition, registration, callback.redirectUri, code, verifier)
            loadProfile(provider, token)
        } finally {
            callback.close()
        }
    }

    private fun exchangeCode(
        definition: OAuthProviderDefinition,
        registration: OAuthClientRegistration,
        redirectUri: URI,
        code: String,
        verifier: String,
    ): String {
        val parameters = linkedMapOf(
            "grant_type" to "authorization_code",
            "client_id" to registration.clientId,
            "code" to code,
            "redirect_uri" to redirectUri.toString(),
            "code_verifier" to verifier,
        )
        registration.clientSecret.takeIf(String::isNotBlank)?.let { parameters["client_secret"] = it }
        val response = send(
            HttpRequest.newBuilder(URI.create(definition.tokenEndpoint))
                .timeout(Duration.ofSeconds(30))
                .header("Accept", "application/json")
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(formBody(parameters)))
                .build(),
        )
        val body = json.parseToJsonElement(response).jsonObject
        body.string("error")?.let { error ->
            error("OAuth token exchange failed: ${body.string("error_description") ?: error}")
        }
        return body.string("access_token").orEmpty().also {
            require(it.isNotBlank()) { "OAuth token exchange returned no access token." }
        }
    }

    private fun loadProfile(provider: OAuthProvider, token: String): OAuthUserProfile = when (provider) {
        OAuthProvider.GOOGLE -> googleProfile(token)
        OAuthProvider.GITHUB -> githubProfile(token)
        OAuthProvider.MICROSOFT -> microsoftProfile(token)
    }

    private fun googleProfile(token: String): OAuthUserProfile {
        val body = authenticatedJson("https://openidconnect.googleapis.com/v1/userinfo", token)
        val photoUrl = body.string("picture").orEmpty()
        return OAuthUserProfile(
            provider = OAuthProvider.GOOGLE,
            emailAddress = body.string("email").orEmpty(),
            username = body.string("preferred_username").orEmpty(),
            fullName = body.string("name").orEmpty(),
            profilePhotoUrl = photoUrl,
            profilePhotoBytes = downloadImage(photoUrl),
            userId = body.string("sub").orEmpty(),
            role = body.string("role").orEmpty(),
        )
    }

    private fun githubProfile(token: String): OAuthUserProfile {
        val body = authenticatedJson("https://api.github.com/user", token)
        val email = body.string("email") ?: githubPrimaryEmail(token)
        val photoUrl = body.string("avatar_url").orEmpty()
        return OAuthUserProfile(
            provider = OAuthProvider.GITHUB,
            emailAddress = email.orEmpty(),
            username = body.string("login").orEmpty(),
            fullName = body.string("name").orEmpty(),
            profilePhotoUrl = photoUrl,
            profilePhotoBytes = downloadImage(photoUrl),
            userId = body.string("id").orEmpty(),
            role = body.string("role") ?: body.string("type").orEmpty(),
        )
    }

    private fun githubPrimaryEmail(token: String): String? {
        val request = authenticatedRequest("https://api.github.com/user/emails", token).GET().build()
        val response = send(request)
        val emails = json.parseToJsonElement(response) as? JsonArray ?: return null
        return emails.mapNotNull { it as? JsonObject }
            .firstOrNull { it.boolean("primary") == true && it.boolean("verified") != false }
            ?.string("email")
            ?: emails.mapNotNull { (it as? JsonObject)?.string("email") }.firstOrNull()
    }

    private fun microsoftProfile(token: String): OAuthUserProfile {
        val body = authenticatedJson(
            "https://graph.microsoft.com/v1.0/me?%24select=id,displayName,mail,userPrincipalName,jobTitle",
            token,
        )
        val username = body.string("userPrincipalName").orEmpty()
        return OAuthUserProfile(
            provider = OAuthProvider.MICROSOFT,
            emailAddress = body.string("mail") ?: username,
            username = username,
            fullName = body.string("displayName").orEmpty(),
            profilePhotoBytes = authenticatedBytes("https://graph.microsoft.com/v1.0/me/photo/%24value", token),
            userId = body.string("id").orEmpty(),
            role = body.string("jobTitle").orEmpty(),
        )
    }

    private fun authenticatedJson(url: String, token: String): JsonObject =
        json.parseToJsonElement(send(authenticatedRequest(url, token).GET().build())).jsonObject

    private fun authenticatedBytes(url: String, token: String): ByteArray? {
        val response = httpClient.send(
            authenticatedRequest(url, token).GET().build(),
            HttpResponse.BodyHandlers.ofByteArray(),
        )
        return response.body().takeIf { response.statusCode() in 200..299 }
    }

    private fun authenticatedRequest(url: String, token: String): HttpRequest.Builder =
        HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(30))
            .header("Accept", "application/json")
            .header("Authorization", "Bearer $token")
            .header("User-Agent", "Threadwork-Desktop")

    private fun downloadImage(url: String): ByteArray? {
        if (url.isBlank()) return null
        val response = runCatching {
            httpClient.send(
                HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(20)).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray(),
            )
        }.getOrNull() ?: return null
        return response.body().takeIf { response.statusCode() in 200..299 }
    }

    private fun send(request: HttpRequest): String {
        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        require(response.statusCode() in 200..299) {
            "OAuth request failed with HTTP ${response.statusCode()}: ${response.body().take(500)}"
        }
        return response.body()
    }
}

private data class OAuthProviderDefinition(
    val authorizationEndpoint: String,
    val tokenEndpoint: String,
    val scope: String,
)

private fun providerDefinition(provider: OAuthProvider): OAuthProviderDefinition = when (provider) {
    OAuthProvider.GOOGLE -> OAuthProviderDefinition(
        authorizationEndpoint = "https://accounts.google.com/o/oauth2/v2/auth",
        tokenEndpoint = "https://oauth2.googleapis.com/token",
        scope = "openid email profile",
    )
    OAuthProvider.GITHUB -> OAuthProviderDefinition(
        authorizationEndpoint = "https://github.com/login/oauth/authorize",
        tokenEndpoint = "https://github.com/login/oauth/access_token",
        scope = "read:user user:email",
    )
    OAuthProvider.MICROSOFT -> OAuthProviderDefinition(
        authorizationEndpoint = "https://login.microsoftonline.com/common/oauth2/v2.0/authorize",
        tokenEndpoint = "https://login.microsoftonline.com/common/oauth2/v2.0/token",
        scope = "openid profile email User.Read",
    )
}

private class LoopbackOAuthCallback : AutoCloseable {
    private val callback = CompletableFuture<Map<String, String>>()
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "threadwork-oauth-callback").apply { isDaemon = true }
    }
    private val server = HttpServer.create(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0).apply {
        createContext("/oauth/callback") { exchange ->
            val parameters = parseQuery(exchange.requestURI.rawQuery.orEmpty())
            val successful = parameters["code"].isNullOrBlank().not() && parameters["error"].isNullOrBlank()
            val response = if (successful) {
                "Threadwork login completed. You can close this browser window."
            } else {
                "Threadwork login was not completed. You can close this browser window."
            }
            val bytes = response.toByteArray(StandardCharsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "text/plain; charset=utf-8")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
            callback.complete(parameters)
        }
        this.executor = this@LoopbackOAuthCallback.executor
        start()
    }

    val redirectUri: URI = URI.create("http://127.0.0.1:${server.address.port}/oauth/callback")

    fun await(): Map<String, String> = callback.get(3, TimeUnit.MINUTES)

    override fun close() {
        server.stop(0)
        executor.shutdownNow()
    }
}

private fun JsonObject.string(name: String): String? =
    get(name)?.jsonPrimitive?.content?.takeUnless { it == "null" }

private fun JsonObject.boolean(name: String): Boolean? =
    string(name)?.toBooleanStrictOrNull()

private fun uriWithQuery(base: String, parameters: Map<String, String>): URI =
    URI.create("$base?${formBody(parameters)}")

private fun formBody(parameters: Map<String, String>): String =
    parameters.entries.joinToString("&") { (name, value) -> "${urlEncode(name)}=${urlEncode(value)}" }

private fun parseQuery(query: String): Map<String, String> =
    query.split('&')
        .filter { it.isNotBlank() }
        .associate { part -> urlDecode(part.substringBefore('=')) to urlDecode(part.substringAfter('=', "")) }

private fun urlEncode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)

private fun urlDecode(value: String): String = URLDecoder.decode(value, StandardCharsets.UTF_8)

private fun randomUrlToken(byteCount: Int): String = ByteArray(byteCount)
    .also(SecureRandom()::nextBytes)
    .let(::base64Url)

private fun base64Url(value: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(value)

private fun browse(uri: URI) {
    require(Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
        "No desktop browser integration is available. Open this URL manually: $uri"
    }
    Desktop.getDesktop().browse(uri)
}
