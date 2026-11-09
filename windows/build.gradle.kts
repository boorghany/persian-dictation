// Windows tray app: the shared Kotlin engine (`:core`) plus a Win32 front end (JNA).
// Built on the Mac (`windows/build.sh`), packaged with jpackage inside the Windows VM.
plugins {
    id("org.jetbrains.kotlin.jvm")
    application
}

kotlin {
    jvmToolchain(17)
}

/** Set by hand for every change (shared sequence with the Mac and Android apps, see CLAUDE.md). */
val appVersion = "1.0.4"
val buildNumber = file("build-number.txt").takeIf { it.exists() }?.readText()?.trim() ?: "0"

dependencies {
    implementation(project(":core"))
    implementation("net.java.dev.jna:jna-platform:5.15.0")
}

application {
    mainClass.set("local.voice2text.windows.MainKt")
    applicationName = "PersianDictation"
}

tasks.jar {
    archiveFileName.set("PersianDictation.jar")
}

val generateVersion by tasks.registering {
    val out = layout.buildDirectory.dir("generated/version")
    inputs.property("version", appVersion)
    inputs.property("build", buildNumber)
    outputs.dir(out)
    doLast {
        val file = out.get().file("persiandictation-version.properties").asFile
        file.parentFile.mkdirs()
        file.writeText("version=$appVersion\nbuild=$buildNumber\n")
    }
}

sourceSets.main {
    resources.srcDir(generateVersion)
}
