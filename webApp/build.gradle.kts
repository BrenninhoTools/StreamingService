import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

kotlin {
    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        outputModuleName.set("streamingservice")
        browser {
            commonWebpackConfig {
                outputFileName = "streamingservice.js"
            }
        }
        binaries.executable()
    }

    sourceSets {
        wasmJsMain.dependencies {
            implementation(project(":shared"))
            implementation(libs.compose.runtime)
            implementation(libs.compose.ui)
            implementation(libs.compose.foundation)
            implementation(libs.compose.components.resources)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.ktor.client.core)
            // Bundled by webpack into the app, so the Discord Activity needs no external script (Discord's CSP forbids them).
            implementation(npm("@discord/embedded-app-sdk", libs.versions.discord.sdk.get()))
        }
    }
}
