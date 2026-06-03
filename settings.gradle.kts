pluginManagement {
    repositories {
        gradlePluginPortal()
    }
}

plugins {
    // Auto-resolve a matching JDK toolchain via Foojay so the build works on any
    // machine without a hand-installed JDK.
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        // velocity-api (a -SNAPSHOT) lives in the PaperMC repo.
        maven("https://repo.papermc.io/repository/maven-public/") {
            name = "papermc"
        }
    }
}

rootProject.name = "velocity-playercount"
