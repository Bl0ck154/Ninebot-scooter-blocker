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
        versionCode = 19
        versionName = "0.9.2"
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
            isMinifyEnabled = false
            if (hasReleaseSigning) signingConfig = signingConfigs.getByName("releaseKey")
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
