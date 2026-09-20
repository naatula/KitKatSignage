plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "fi.naatula.kitkatsignage"

    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "fi.naatula.kitkatsignage"

        // Android 4.4 / KitKat — the oldest platform this app targets.
        minSdk = 19

        // This is a sideloaded appliance app, not a Play Store app.
        // Keeping it lower avoids opting in to unnecessary new-Android
        // behavioural restrictions.
        targetSdk = 28

        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
}

dependencies {
}