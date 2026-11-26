import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// The phone's end of the Wear OS Data Layer (Full build only: play-services-wearable is GMS).
// wearBridge-empty is the FOSS twin with the same public API that does nothing. Selected by
// isFullBuild in composeApp, the same way as cast / cast-empty.
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
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(libs.core.ktx)
    implementation(libs.coroutines.android)
    implementation(libs.kotlinx.coroutines.play.services)
    implementation(libs.play.services.wearable)
}
