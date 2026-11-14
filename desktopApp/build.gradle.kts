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
    testImplementation(kotlin("test"))
}

compose.desktop {
    application {
        mainClass = "com.praveen.ridernotes.desktop.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Exe)
            packageName = "RiderNotesPC"
            packageVersion = "0.1.0"
            description = "Windows companion for Rider Notes backups"
            vendor = "Praveen Kumar V"
            modules("java.desktop")
            windows {
                menuGroup = "Rider Notes"
                shortcut = true
                perUserInstall = true
            }
        }
    }
}
