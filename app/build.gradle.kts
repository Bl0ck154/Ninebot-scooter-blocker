plugins {
    id("com.android.application")
}

android {
    namespace = "com.bl0ck154.ninebotblocker"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.bl0ck154.ninebotblocker"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "0.2.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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
