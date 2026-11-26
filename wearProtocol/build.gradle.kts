import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// What the phone and the watch say to each other over the Wear Data Layer. Plain data, no GMS:
// the phone app links it in both Full and FOSS builds (FOSS just never sends anything).
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

android {
    namespace = "org.simpmusic.wear.protocol"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    api(libs.kotlinx.serialization.json)
}
