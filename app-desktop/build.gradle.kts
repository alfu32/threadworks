import org.gradle.language.jvm.tasks.ProcessResources

plugins {
    id("org.jetbrains.kotlin.jvm")
    application
}

val oauthResourceDirectory = layout.buildDirectory.dir("generated/resources/oauth")
val oauthEnvironmentProperties = linkedMapOf(
    "threadwork.oauth.google.clientId" to "THREADWORK_GOOGLE_CLIENT_ID",
    "threadwork.oauth.google.clientSecret" to "THREADWORK_GOOGLE_CLIENT_SECRET",
    "threadwork.oauth.github.clientId" to "THREADWORK_GITHUB_CLIENT_ID",
    "threadwork.oauth.github.clientSecret" to "THREADWORK_GITHUB_CLIENT_SECRET",
    "threadwork.oauth.microsoft.clientId" to "THREADWORK_MICROSOFT_CLIENT_ID",
    "threadwork.oauth.microsoft.tenant" to "THREADWORK_MICROSOFT_TENANT",
)

val generateOAuthProperties by tasks.registering {
    group = "build setup"
    description = "Generates packaged OAuth configuration from environment variables."
    val outputFile = oauthResourceDirectory.map { it.file("threadwork-oauth.properties") }
    outputs.file(outputFile)
    oauthEnvironmentProperties.values.forEach { environment ->
        inputs.property(environment, providers.environmentVariable(environment)).optional(true)
    }
    doLast {
        val target = outputFile.get().asFile
        target.parentFile.mkdirs()
        val properties = oauthEnvironmentProperties.mapNotNull { (property, environment) ->
            providers.environmentVariable(environment).orNull
                ?.trim()
                ?.takeIf(String::isNotBlank)
                ?.let { property to it }
        }
        target.writeText(
            properties.joinToString(separator = "\n", postfix = if (properties.isEmpty()) "" else "\n") { (property, value) ->
                "${property.escapePropertiesValue()}=${value.escapePropertiesValue()}"
            },
        )
    }
}

tasks.named<ProcessResources>("processResources") {
    dependsOn(generateOAuthProperties)
    from(oauthResourceDirectory)
}

fun String.escapePropertiesValue(): String = replace("\\", "\\\\")
    .replace("\n", "\\n")
    .replace("\r", "\\r")
    .replace("=", "\\=")
    .replace(":", "\\:")

dependencies {
    implementation(project(":core"))
    implementation(project(":storage-json"))
    implementation(project(":completion-core"))
    implementation(project(":compilers-impl"))
    implementation(project(":builtin-archetype"))
    implementation(project(":assets"))
    implementation("com.formdev:flatlaf:3.7.2")
    implementation("com.vladsch.flexmark:flexmark:0.64.8")
    implementation("com.vladsch.flexmark:flexmark-ext-tables:0.64.8")
    implementation("com.openhtmltopdf:openhtmltopdf-pdfbox:1.0.10")
    implementation("org.apache.xmlgraphics:fop:2.10")
    implementation("org.apache.xmlgraphics:batik-transcoder:1.17")
    implementation(files(rootProject.file("lib/tinycc-embed.jar")))
    implementation(files(rootProject.file("lib/quickjs-cli.jar")))
    testImplementation(kotlin("test"))
}

application {
    mainClass.set("com.threadwork.app.MainKt")
}

val releaseNumber = rootProject.extra["threadworkReleaseNumber"] as String
val gitCommitId = rootProject.extra["threadworkGitCommitId"] as String
val gitTag = rootProject.extra["threadworkGitTag"] as String?
val buildDate = rootProject.extra["threadworkBuildDate"] as String

tasks.register<Jar>("fatJar") {
    group = "distribution"
    description = "Builds a runnable fat jar in the root dist directory."
    dependsOn(project(":core").tasks.named("generateVersionSource"))
    dependsOn(configurations.runtimeClasspath)

    archiveBaseName.set("threadwork")
    archiveVersion.set(releaseNumber)
    archiveClassifier.set("")
    destinationDirectory.set(rootProject.layout.projectDirectory.dir("dist"))
    duplicatesStrategy = org.gradle.api.file.DuplicatesStrategy.EXCLUDE
    doFirst {
        destinationDirectory.get().asFile.mkdirs()
    }

    manifest {
        attributes["Main-Class"] = application.mainClass.get()
        attributes["Implementation-Version"] = releaseNumber
        attributes["Git-Commit"] = gitCommitId
        attributes["Build-Date"] = buildDate
        gitTag?.let { attributes["Git-Tag"] = it }
    }

    from(sourceSets.main.get().output)
    from({
        configurations.runtimeClasspath.get()
            .filter { it.isFile && it.name.endsWith(".jar") }
            .map(::zipTree)
    })
}
