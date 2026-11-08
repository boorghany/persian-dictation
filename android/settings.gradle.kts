pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "PersianDictationAndroid"
include(":core", ":replay", ":app", ":windows")
// The Windows app lives at the repo root next to the Mac app, but shares this build for `:core`.
project(":windows").projectDir = file("../windows")
