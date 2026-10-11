plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Every build gets the next build number (see build.sh), shown in the app as "0.1.0 (build N)".
val buildNumber = (findProperty("buildNumber") as String?)?.toInt() ?: 1

android {
    namespace = "local.voice2text.persiandictation"
    compileSdk = 35

    defaultConfig {
        applicationId = "local.voice2text.persiandictation"
        minSdk = 28 // InputMethodService.switchToPreviousInputMethod()
        targetSdk = 35
        versionCode = buildNumber
        versionName = "0.11.0"
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
