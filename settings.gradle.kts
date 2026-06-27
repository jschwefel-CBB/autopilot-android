pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

// Lets Gradle auto-provision the JDK named by the build's jvmToolchain (17) when
// it is not already installed — so the build does not depend on the operator's
// system JDK. (A system JDK newer than Gradle supports, e.g. 26, otherwise
// breaks the build with a cryptic version-parse error.)
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "autopilot-android"
include(":app")
