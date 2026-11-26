import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// FOSS twin of wearBridge: the same public API, no Play services, does nothing.
plugins {
    alias(libs.plugins.android.library)
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

android {
    namespace = "org.simpmusic.wear.bridge"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
