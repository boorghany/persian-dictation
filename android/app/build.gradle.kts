import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Every build gets the next build number (see build.sh), shown in the app as "0.1.0 (build N)".
val buildNumber = (findProperty("buildNumber") as String?)?.toInt() ?: 1

// Release (Cafe Bazaar) signing key: signing/keystore.properties + signing/release.jks, git-ignored.
// Losing it means Bazaar won't accept updates, so it must be backed up outside this folder.
val releaseKey = rootProject.file("signing/keystore.properties").takeIf { it.exists() }?.let { f ->
    Properties().apply { f.inputStream().use { load(it) } }
}

android {
    namespace = "local.voice2text.persiandictation"
    compileSdk = 35

    defaultConfig {
        // Published on Cafe Bazaar under this id (0.18.0+); the code's package stays local.voice2text.
        applicationId = "ir.boorghany.persiandictation"
        minSdk = 28 // InputMethodService.switchToPreviousInputMethod()
        targetSdk = 35
        versionCode = buildNumber
        versionName = "0.27.0"
    }

    signingConfigs {
        if (releaseKey != null) create("release") {
            storeFile = rootProject.file(releaseKey.getProperty("storeFile"))
            storePassword = releaseKey.getProperty("storePassword")
            keyAlias = releaseKey.getProperty("keyAlias")
            keyPassword = releaseKey.getProperty("keyPassword")
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation(project(":core"))
}
