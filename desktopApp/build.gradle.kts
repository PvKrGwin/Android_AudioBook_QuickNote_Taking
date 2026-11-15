import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.jetbrains.compose)
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(libs.json)
    val javafxVersion = "17.0.14"
    implementation("org.openjfx:javafx-base:$javafxVersion:win")
    implementation("org.openjfx:javafx-graphics:$javafxVersion:win")
    implementation("org.openjfx:javafx-media:$javafxVersion:win")
    testImplementation(kotlin("test"))
}

compose.desktop {
    application {
        mainClass = "com.praveen.ridernotes.desktop.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Exe)
            packageName = "RiderNotesPC"
            packageVersion = "0.1.6"
            description = "Windows companion for Rider Notes backups"
            vendor = "Praveen Kumar V"
            // Compose Desktop and JavaFX Media need more than java.desktop at runtime.
            // Keep these explicit so jpackage cannot produce a launcher whose JVM starts
            // without the modules required by the UI/audio stack.
            modules(
                "java.desktop",
                "java.logging",
                "java.management",
                "java.naming",
                "java.prefs",
                "java.rmi",
                "java.scripting",
                "java.sql",
                "java.xml",
                "jdk.unsupported"
            )
            windows {
                console = false
                menuGroup = "Rider Notes"
                shortcut = true
                perUserInstall = true
                iconFile.set(project.file("src/main/resources/rider_notes.ico"))
            }
        }
    }
}
