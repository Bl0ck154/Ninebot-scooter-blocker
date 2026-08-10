plugins {
    id("com.android.application")
}

val stableDebugKeystore = rootProject.file("ci/ninebot-debug.keystore")
val releaseKeystore = rootProject.file("ci/release.keystore")
val releaseStorePassword = System.getenv("RELEASE_STORE_PASSWORD")
val releaseKeyAlias = System.getenv("RELEASE_KEY_ALIAS")
val releaseKeyPassword = System.getenv("RELEASE_KEY_PASSWORD")
val hasReleaseSigning = releaseKeystore.exists()
        && !releaseStorePassword.isNullOrBlank()
        && !releaseKeyAlias.isNullOrBlank()
        && !releaseKeyPassword.isNullOrBlank()

android {
    namespace = "com.bl0ck154.ninebotblocker"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.bl0ck154.ninebotblocker"
        minSdk = 26
        targetSdk = 35
        versionCode = 25
        versionName = "0.11.1"
    }

    signingConfigs {
        create("stableDebug") {
            storeFile = stableDebugKeystore
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        if (hasReleaseSigning) {
            create("releaseKey") {
                storeFile = releaseKeystore
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        debug {
            if (stableDebugKeystore.exists()) signingConfig = signingConfigs.getByName("stableDebug")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("releaseKey")
            } else if (stableDebugKeystore.exists()) {
                // Keep update compatibility with all APKs published so far.
                signingConfig = signingConfigs.getByName("stableDebug")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
