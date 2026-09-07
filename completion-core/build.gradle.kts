plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.serialization")
}

dependencies {
    api(project(":core"))
    api(project(":compiler-api"))
    implementation("io.github.bonede:tree-sitter:0.26.6")
    implementation("io.github.bonede:tree-sitter-c:0.24.1")
    implementation("io.github.bonede:tree-sitter-javascript:0.25.0")
    implementation("io.github.bonede:tree-sitter-php:0.24.2")
    implementation("io.github.bonede:tree-sitter-python:0.25.0")
    implementation("io.github.bonede:tree-sitter-java:0.23.5")
    implementation("io.github.bonede:tree-sitter-go:0.25.0")
    implementation("io.github.bonede:tree-sitter-kotlin:0.3.8.1")
    testImplementation(project(":storage-json"))
    testImplementation(kotlin("test"))
}
