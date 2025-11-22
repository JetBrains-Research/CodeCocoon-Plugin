plugins {
    alias(libs.plugins.kotlin)
    id("org.jetbrains.intellij.platform.module")
}

group = providers.gradleProperty("pluginGroup").get()
version = providers.gradleProperty("pluginVersion").get()

repositories {
    mavenCentral()

    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        val platformType: String by project
        val platformVersion: String by project

        println("platformType: $platformType, platformVersion: $platformVersion")

        when (platformType) {
            "IC" -> intellijIdeaCommunity(platformVersion)
            "IU" -> intellijIdeaUltimate(platformVersion)
            else -> intellijIdea(platformVersion)
        }
    }

    testImplementation(platform("org.junit:junit-bom:5.10.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}