plugins {
    id("org.jetbrains.kotlin.jvm")
}

apply(plugin = "org.jetbrains.kotlin.plugin.serialization")

dependencies {
    implementation(project(":core"))
    implementation(project(":storage-json"))
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    testImplementation(kotlin("test"))
}
