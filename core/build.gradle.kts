plugins {
    alias(libs.plugins.kotlin)
    id("org.jetbrains.intellij.platform.module")
    kotlin("plugin.serialization") version "2.1.20"
}

val spaceUsername: String? = findProperty("spaceUsername") as String?
val spacePassword: String? = findProperty("spacePassword") as String?

fun spaceCredentialsProvided() = spaceUsername != null && spacePassword != null

group = providers.gradleProperty("pluginGroup").get()
version = providers.gradleProperty("pluginVersion").get()

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    intellijPlatform {
        val platformType: String by project
        val platformVersion: String by project
        println("[:core Module]: creating platform=$platformType with version=$platformVersion")
        create(platformType, platformVersion)
    }

    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    testImplementation(platform("org.junit:junit-bom:5.10.0"))
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}