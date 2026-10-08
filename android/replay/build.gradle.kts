// Mac-side tool: replay a recording through the shared engine (`./gradlew :replay:run --args="file.wav"`).
plugins {
    id("org.jetbrains.kotlin.jvm")
    application
}

kotlin {
    jvmToolchain(17)
}

application {
    mainClass.set("local.voice2text.replay.MainKt")
}

dependencies {
    implementation(project(":core"))
}
