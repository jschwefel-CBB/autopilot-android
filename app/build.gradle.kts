plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.autopilot.testhostapp"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.autopilot.testhostapp"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    // Compose is enabled solely for the test fixture (ComposeFixtureActivity) that
    // reproduces the Compose-specific runner behaviors — OutlinedTextField whose
    // contentDescription lands on a non-focusable android.view.View wrapper, inside
    // a non-scrollable AlertDialog — so those get caught under the CI 5x gate. The
    // main TestHostApp stays classic Views.
    buildFeatures {
        compose = true
    }
}

// Pin the JVM toolchain so the build always compiles against JDK 17 regardless
// of the operator's system JDK. Without this, a newer system JDK (e.g. 26) that
// the bundled Gradle can't parse fails the build. foojay (settings.gradle.kts)
// provisions JDK 17 if it isn't already installed.
kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("com.google.android.material:material:1.12.0")

    // Compose — for the test fixture activity only (see buildFeatures.compose).
    val composeBom = platform("androidx.compose:compose-bom:2024.09.03")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.2")

    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.3.0")
    androidTestImplementation("com.google.code.gson:gson:2.11.0")
}
