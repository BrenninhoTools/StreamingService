package com.brenninho.streamingservice.server

import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer

fun main() {
    val config = ServerConfig.fromEnv(System.getenv())
    embeddedServer(CIO, port = config.port, host = "0.0.0.0") {
        streamingModule(config)
    }.start(wait = true)
}
