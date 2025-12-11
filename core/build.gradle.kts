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
    if (spaceCredentialsProvided()) {
        maven {
            url = uri("https://packages.jetbrains.team/maven/p/testing-agents/grazie-llm-interaction")
            credentials {
                username = spaceUsername
                password = spacePassword
            }
        }
        maven {
            url = uri("https://packages.jetbrains.team/maven/p/grazi/grazie-platform-public")
        }
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
    implementation("ai.koog:koog-agents:0.5.4-develop-20251211-0203") {
        exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-core")
    }
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation(kotlin("reflect"))
    if (spaceCredentialsProvided()) {
        implementation("org.jetbrains.research:grazie-llm-interaction:1.0-SNAPSHOT") {
            exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-core")
        }
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