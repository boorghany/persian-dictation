// The speech engine in plain Kotlin/JVM, shared by the Android app and the Mac replay tool.
plugins {
    id("org.jetbrains.kotlin.jvm")
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    // HTTP/2 with one shared connection (see SpeechStream). `api` so the app module gets it too.
    api("com.squareup.okhttp3:okhttp:4.12.0")
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}
