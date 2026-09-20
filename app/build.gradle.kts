import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
}

// Release signing key, gitignored — never shared, never committed.
// See keystore.properties.example.
val keystoreProps = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) {
        file.inputStream().use { load(it) }
    }
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

    signingConfigs {
        // Only declared when there is a keystore.properties to fill it from:
        // an empty signing config fails validateSigningRelease outright,
        // whereas no signing config at all just leaves the release APK
        // unsigned, so a fresh clone can still build one.
        if (keystoreProps.getProperty("storeFile") != null) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("release")
            optimization {
                enable = false
            }
        }
    }

    lint {
        // lintVitalRelease otherwise fails assembleRelease over
        // ExpiredTargetSdkVersion, a Play Store-only requirement that
        // doesn't apply to this sideloaded appliance app (see targetSdk
        // comment above).
        checkReleaseBuilds = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
}

dependencies {
}