package com.threadwork.app.identity

import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.geom.Ellipse2D
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.util.prefs.Preferences
import java.util.Base64
import javax.imageio.ImageIO
import javax.swing.Icon
import javax.swing.ImageIcon

enum class OAuthProvider(val id: String, val label: String) {
    GOOGLE("google", "Google"),
    GITHUB("github", "GitHub"),
    MICROSOFT("microsoft", "Microsoft"),
}

data class UserIdentity(
    val provider: OAuthProvider,
    val emailAddress: String = "",
    val username: String = "",
    val fullName: String = "",
    val profilePhotoUrl: String = "",
    val expiresAt: Instant,
    val userId: String = "",
    val role: String = "",
)

fun UserIdentity?.designator(systemUser: String = System.getProperty("user.name").orEmpty()): String = when {
    this == null -> systemUser.ifBlank { "local user" }
    emailAddress.isNotBlank() -> emailAddress
    username.isNotBlank() -> username
    userId.isNotBlank() -> userId
    fullName.isNotBlank() -> fullName
    else -> systemUser.ifBlank { "local user" }
}

class UserIdentityStore(
    private val preferences: Preferences = Preferences.userRoot().node(PREF_NODE),
    private val avatarFile: Path = defaultAvatarFile(),
    private val now: () -> Instant = Instant::now,
) {
    fun load(): UserIdentity? {
        val provider = runCatching {
            OAuthProvider.valueOf(preferences.get(PROVIDER_KEY, ""))
        }.getOrNull() ?: return null
        val expiresAt = preferences.get(EXPIRES_AT_KEY, "")
            .takeIf(String::isNotBlank)
            ?.let { value -> runCatching { Instant.parse(value) }.getOrNull() }
            ?: return clear().let { null }
        if (!expiresAt.isAfter(now())) return clear().let { null }
        return UserIdentity(
            provider = provider,
            emailAddress = preferences.get(EMAIL_KEY, ""),
            username = preferences.get(USERNAME_KEY, ""),
            fullName = preferences.get(FULL_NAME_KEY, ""),
            profilePhotoUrl = preferences.get(PHOTO_URL_KEY, ""),
            expiresAt = expiresAt,
            userId = preferences.get(USER_ID_KEY, ""),
            role = preferences.get(ROLE_KEY, ""),
        )
    }

    fun save(profile: OAuthUserProfile): UserIdentity {
        val identity = UserIdentity(
            provider = profile.provider,
            emailAddress = profile.emailAddress.trim(),
            username = profile.username.trim(),
            fullName = profile.fullName.trim(),
            profilePhotoUrl = profile.profilePhotoUrl.trim(),
            expiresAt = now().plus(SESSION_DURATION),
            userId = profile.userId.trim(),
            role = profile.role.trim(),
        )
        preferences.put(PROVIDER_KEY, identity.provider.name)
        preferences.put(EMAIL_KEY, identity.emailAddress)
        preferences.put(USERNAME_KEY, identity.username)
        preferences.put(FULL_NAME_KEY, identity.fullName)
        preferences.put(PHOTO_URL_KEY, identity.profilePhotoUrl)
        preferences.put(USER_ID_KEY, identity.userId)
        preferences.put(ROLE_KEY, identity.role)
        preferences.put(EXPIRES_AT_KEY, identity.expiresAt.toString())
        saveAvatar(profile.profilePhotoBytes)
        preferences.flush()
        return identity
    }

    fun avatarImage(): BufferedImage? {
        if (!Files.isRegularFile(avatarFile)) return null
        return runCatching { Files.newInputStream(avatarFile).use(ImageIO::read) }.getOrNull()
    }

    /** Returns the final application-sized avatar, including the fallback initials rendering. */
    fun avatarPngBytes(designator: String): ByteArray {
        val rendered = renderUserAvatar(designator, avatarImage(), AVATAR_CACHE_SIZE)
        return ByteArrayOutputStream().use { output ->
            ImageIO.write(rendered, "png", output)
            output.toByteArray()
        }
    }

    fun clear() {
        listOf(
            PROVIDER_KEY,
            EMAIL_KEY,
            USERNAME_KEY,
            FULL_NAME_KEY,
            PHOTO_URL_KEY,
            USER_ID_KEY,
            ROLE_KEY,
            EXPIRES_AT_KEY,
        )
            .forEach(preferences::remove)
        runCatching { Files.deleteIfExists(avatarFile) }
        preferences.flush()
    }

    private fun saveAvatar(bytes: ByteArray?) {
        if (bytes == null || bytes.isEmpty()) {
            runCatching { Files.deleteIfExists(avatarFile) }
            return
        }
        val source = runCatching { ImageIO.read(ByteArrayInputStream(bytes)) }.getOrNull()
        if (source == null) {
            runCatching { Files.deleteIfExists(avatarFile) }
            return
        }
        val rendered = resizeProfilePhoto(source, AVATAR_CACHE_SIZE)
        avatarFile.parent?.let(Files::createDirectories)
        val temporary = avatarFile.resolveSibling("${avatarFile.fileName}.tmp")
        Files.newOutputStream(temporary).use { ImageIO.write(rendered, "png", it) }
        try {
            Files.move(temporary, avatarFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temporary, avatarFile, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    companion object {
        private const val PREF_NODE = "com/threadwork/app/identity"
        private const val PROVIDER_KEY = "provider"
        private const val EMAIL_KEY = "emailAddress"
        private const val USERNAME_KEY = "username"
        private const val FULL_NAME_KEY = "fullName"
        private const val PHOTO_URL_KEY = "profilePhotoUrl"
        private const val USER_ID_KEY = "userId"
        private const val ROLE_KEY = "role"
        private const val EXPIRES_AT_KEY = "expiresAt"
        private const val AVATAR_CACHE_SIZE = 64
        val SESSION_DURATION: Duration = Duration.ofDays(7)

        private fun defaultAvatarFile(): Path =
            Path.of(System.getProperty("user.home"), ".threadwork", "identity-avatar.png")
    }
}

object ThreadworkUserIdentity {
    val store = UserIdentityStore()

    fun designator(): String = store.load().designator()
}

data class OAuthUserProfile(
    val provider: OAuthProvider,
    val emailAddress: String = "",
    val username: String = "",
    val fullName: String = "",
    val profilePhotoUrl: String = "",
    val profilePhotoBytes: ByteArray? = null,
    val userId: String = "",
    val role: String = "",
)

fun userAvatarIcon(designator: String, profilePhoto: BufferedImage?, size: Int): Icon =
    ImageIcon(renderUserAvatar(designator, profilePhoto, size))

fun userAvatarIcon(designator: String, avatarData: String, size: Int): Icon =
    ImageIcon(renderUserAvatar(designator, decodeAvatarData(avatarData), size))

fun userAvatarImage(designator: String, avatarData: String, size: Int): BufferedImage =
    renderUserAvatar(designator, decodeAvatarData(avatarData), size)

fun avatarDataFromBytes(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

private fun decodeAvatarData(data: String): BufferedImage? {
    if (data.isBlank()) return null
    return runCatching {
        Base64.getDecoder().decode(data).inputStream().use(ImageIO::read)
    }.getOrNull()
}

internal fun avatarBaseColor(designator: String): Color {
    val digest = MessageDigest.getInstance("MD5").digest(designator.toByteArray(StandardCharsets.UTF_8))
    return Color(digest[0].toInt() and 0xff, digest[1].toInt() and 0xff, digest[2].toInt() and 0xff)
}

internal fun avatarInitials(designator: String): String {
    val parts = designator.split(Regex("[^\\p{L}\\p{N}]+"))
        .filter(String::isNotBlank)
    return when {
        parts.isEmpty() -> "?"
        parts.size == 1 -> parts.single().take(2).uppercase()
        else -> "${parts.first().first()}${parts.last().first()}".uppercase()
    }
}

private fun renderUserAvatar(designator: String, profilePhoto: BufferedImage?, size: Int): BufferedImage {
    val image = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
    val graphics = image.createGraphics()
    graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
    graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
    val base = avatarBaseColor(designator)
    val fill = contrastingColor(base)
    val inset = 1.5
    val circle = Ellipse2D.Double(inset, inset, size - inset * 2.0, size - inset * 2.0)
    graphics.color = fill
    graphics.fill(circle)
    if (profilePhoto != null) {
        val oldClip = graphics.clip
        graphics.clip = circle
        val scale = maxOf(size.toDouble() / profilePhoto.width, size.toDouble() / profilePhoto.height)
        val width = (profilePhoto.width * scale).toInt()
        val height = (profilePhoto.height * scale).toInt()
        graphics.drawImage(profilePhoto, (size - width) / 2, (size - height) / 2, width, height, null)
        graphics.clip = oldClip
    } else {
        graphics.color = base
        graphics.font = Font(Font.SANS_SERIF, Font.BOLD, (size * 0.38).toInt().coerceAtLeast(10))
        val initials = avatarInitials(designator)
        val metrics = graphics.fontMetrics
        graphics.drawString(
            initials,
            (size - metrics.stringWidth(initials)) / 2,
            (size - metrics.height) / 2 + metrics.ascent,
        )
    }
    graphics.color = base
    graphics.stroke = java.awt.BasicStroke(maxOf(1.5f, size / 18f))
    graphics.draw(circle)
    graphics.dispose()
    return image
}

private fun resizeProfilePhoto(source: BufferedImage, size: Int): BufferedImage {
    val image = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
    val graphics = image.createGraphics()
    graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
    val scale = maxOf(size.toDouble() / source.width, size.toDouble() / source.height)
    val width = (source.width * scale).toInt()
    val height = (source.height * scale).toInt()
    graphics.drawImage(source, (size - width) / 2, (size - height) / 2, width, height, null)
    graphics.dispose()
    return image
}

private fun contrastingColor(color: Color): Color {
    val luminance = (0.2126 * color.red + 0.7152 * color.green + 0.0722 * color.blue) / 255.0
    return if (luminance > 0.5) Color.BLACK else Color.WHITE
}
