plugins {
    alias(libs.plugins.kotlinJvm)
    application
}

application {
    applicationName = "streamingservice-server"
    mainClass.set("com.brenninho.streamingservice.server.MainKt")
}

dependencies {
    implementation(project(":protocol"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.cio)
    implementation(libs.ktor.server.websockets)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    runtimeOnly(libs.logback.classic)

    testImplementation(kotlin("test"))
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.ktor.client.websockets)
    testImplementation(libs.ktor.client.mock)
}

tasks.test {
    useJUnitPlatform()
}
