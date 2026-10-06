import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

dependencies {
    implementation(project(":shared"))
    implementation(compose.desktop.currentOs)
    implementation(libs.compose.components.resources)
    implementation(libs.kotlinx.coroutines.swing)
}

compose.desktop {
    application {
        mainClass = "com.brenninho.streamingservice.desktop.MainKt"

        nativeDistributions {
            // jpackage only builds the formats that match the host OS.
            targetFormats(TargetFormat.Msi, TargetFormat.Exe, TargetFormat.Dmg, TargetFormat.Deb, TargetFormat.Rpm)
            packageName = "StreamingService"
            packageVersion = providers.gradleProperty("app.version").get()
            description = "Share your screen online with anyone."
            vendor = "Brenninho"
            copyright = "Copyright (c) Brenninho"
            modules("java.instrument", "jdk.unsupported")

            windows {
                iconFile.set(project.file("icons/icon.ico"))
                menuGroup = "StreamingService"
                perUserInstall = true
                // Keep this UUID stable: Windows uses it to upgrade existing installs.
                upgradeUuid = "6f0d8c1e-3b7a-4c52-9a41-5d2e8f7b1c93"
            }
            macOS {
                iconFile.set(project.file("icons/icon.icns"))
                bundleID = "com.brenninho.streamingservice"
            }
            linux {
                iconFile.set(project.file("icons/icon.png"))
                shortcut = true
            }
        }
    }
}
