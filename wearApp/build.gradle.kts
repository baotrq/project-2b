import java.util.Properties

// SimpMusic for Wear OS: a remote for the phone app (now playing, transport, like, volume on the
// crown, queue, and the library to start on the phone). It plays nothing itself.
//
// The Data Layer only connects apps with the SAME package name AND the SAME signing key, so the
// application id, its per-build-type suffix and the signing config below mirror :androidApp
// exactly: debug ↔ com.maxrave.simpmusic.dev, release ↔ com.maxrave.simpmusic.bao, both signed
// with the shared bao key (or this machine's debug key when local.properties names none). Change
// one side, change the other.
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "com.maxrave.simpmusic.wear"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.maxrave.simpmusic"
        // Wear OS 3, the first version with Compose for Wear OS and the Data Layer as used here.
        minSdk = 30
        targetSdk = 36
        versionCode =
            libs.versions.version.code
                .get()
                .toInt()
        versionName =
            libs.versions.version.name
                .get()
    }

    val baoSigning =
        runCatching {
            Properties().apply { rootProject.file("local.properties").inputStream().use(::load) }
        }.getOrNull()
            ?.takeIf { !it.getProperty("BAO_KEYSTORE").isNullOrBlank() }
    signingConfigs {
        if (baoSigning != null) {
            create("bao") {
                storeFile = file(baoSigning.getProperty("BAO_KEYSTORE"))
                storePassword = baoSigning.getProperty("BAO_KEYSTORE_PASSWORD", "android")
                keyAlias = baoSigning.getProperty("BAO_KEY_ALIAS", "androiddebugkey")
                keyPassword = baoSigning.getProperty("BAO_KEY_PASSWORD", storePassword)
            }
        }
    }

    buildTypes {
        release {
            applicationIdSuffix = ".bao"
            signingConfig = signingConfigs.findByName("bao") ?: signingConfigs.getByName("debug")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
            applicationIdSuffix = ".dev"
            versionNameSuffix = "-dev"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(projects.wearProtocol)

    implementation(libs.core.ktx)
    implementation(libs.activity.compose)
    implementation(libs.coroutines.android)
    implementation(libs.kotlinx.coroutines.play.services)
    implementation(libs.androidx.lifecycle.runtimeCompose)

    // Compose for Wear OS (Material 3); the BOM aligns the androidx Compose UI it pulls in.
    val composeBom = platform(libs.compose.bom)
    implementation(composeBom)
    implementation(libs.wear.compose.material3)
    implementation(libs.wear.compose.foundation)
    implementation(libs.wear.compose.navigation)
    implementation(libs.ui.tooling.preview)

    // Data Layer to the phone, and "open on phone".
    implementation(libs.play.services.wearable)
    implementation(libs.wear.remote.interactions)

    // List thumbnails come straight from YouTube's image servers (the watch reaches the internet
    // through the phone); the now-playing cover arrives as a Data Layer asset instead.
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
}
