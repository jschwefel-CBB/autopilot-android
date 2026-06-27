plugins {
    id("com.android.application") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.1.0" apply false
    // Compose Compiler plugin (required with Kotlin 2.0+); used by the Compose
    // test fixture in :app. Version tracks the Kotlin version.
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.0" apply false
}
