package com.brenninho.streamingservice.server

import com.brenninho.streamingservice.protocol.ClientMessages
import com.brenninho.streamingservice.protocol.FrameCodec
import com.brenninho.streamingservice.protocol.RejectReason
import com.brenninho.streamingservice.protocol.RoomCode
import com.brenninho.streamingservice.protocol.ServerMessage
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO as ClientCIO
import io.ktor.http.ContentType
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.application.install
import io.ktor.server.http.content.default
import io.ktor.server.http.content.staticFiles
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.pingPeriod
import io.ktor.server.websocket.timeout
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readBytes
import io.ktor.websocket.readText
import java.io.File
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.launch

/**
 * The relay: hosts push JPEG frames in, the server fans them out to every viewer of the room.
 *
 * - `GET  /health`              liveness probe
 * - `GET  /api/config`          public settings for the web app
 * - `WS   /ws/host`             start (or reclaim) a room. Query: room, token, password, announce, name
 * - `WS   /ws/watch/{code}`     watch a room. Query: password
 * - `GET  /`                    the web app (and its files), when [ServerConfig.staticDir] is set
 */
fun Application.streamingModule(
    config: ServerConfig,
    registry: RoomRegistry = RoomRegistry(config.maxRooms, config.maxViewersPerRoom, scope = this, graceMillis = config.hostGraceMillis),
    announcer: DiscordAnnouncer? = config.discordWebhookUrl?.let { url ->
        val client = HttpClient(ClientCIO)
        monitor.subscribe(ApplicationStopped) { client.close() }
        DiscordAnnouncer(url, config.publicUrl, client, scope = this)
    },
) {
    registry.onRoomClosed = { room -> announcer?.closed(room) }

    install(WebSockets) {
        pingPeriod = 15.seconds
        timeout = 30.seconds
        maxFrameSize = FrameCodec.MAX_FRAME_BYTES.toLong()
        masking = false
    }

    routing {
        get("/health") { call.respondText("ok") }

        get("/api/config") {
            val clientId = config.discordClientId?.let(DiscordMessages::jsonString) ?: "null"
            call.respondText("""{"discordClientId":$clientId}""", ContentType.Application.Json)
        }

        webSocket("/ws/host") { handleHost(registry, announcer) }
        webSocket("/ws/watch/{code}") { handleWatch(registry) }

        config.staticDir?.let { dir ->
            staticFiles("/", File(dir)) { default("index.html") }
        }
    }
}

private suspend fun DefaultWebSocketServerSession.reject(reason: RejectReason) {
    send(Frame.Text(ServerMessage.Error(reason).encode()))
    close(CloseReason(CloseReason.Codes.NORMAL, reason.wireName))
}

private suspend fun DefaultWebSocketServerSession.handleHost(registry: RoomRegistry, announcer: DiscordAnnouncer?) {
    val params = call.request.queryParameters
    val requested = params["room"]?.let { RoomCode.parse(it) ?: return reject(RejectReason.BadRoom) }
    val password = params["password"]?.takeIf { it.isNotEmpty() }?.take(MAX_PASSWORD_LENGTH)

    val opened = when (val result = registry.open(requested, password, params["token"])) {
        OpenResult.Taken -> return reject(RejectReason.RoomTaken)
        OpenResult.Full -> return reject(RejectReason.ServerBusy)
        is OpenResult.Opened -> result
    }
    val session = opened.session
    val room = session.room

    send(Frame.Text(ServerMessage.Room(room.code, room.token).encode()))
    if (!opened.reclaimed && params["announce"] == "1") announcer?.opened(room, params["name"])

    val pump = launch { for (event in session.events) send(Frame.Text(event.encode())) }
    var goodbye = false
    try {
        for (frame in incoming) {
            when (frame) {
                is Frame.Binary -> {
                    val bytes = frame.readBytes()
                    if (FrameCodec.isValid(bytes)) room.publish(bytes)
                }
                is Frame.Text -> if (frame.readText() == ClientMessages.BYE) {
                    goodbye = true
                    break
                }
                else -> Unit
            }
        }
    } finally {
        pump.cancel()
        registry.hostLeft(session, immediate = goodbye)
    }
}

private suspend fun DefaultWebSocketServerSession.handleWatch(registry: RoomRegistry) {
    val code = call.parameters["code"]?.let { RoomCode.parse(it) } ?: return reject(RejectReason.NotFound)
    val room = registry.find(code) ?: return reject(RejectReason.NotFound)
    if (!room.checkPassword(call.request.queryParameters["password"])) return reject(RejectReason.BadPassword)
    val viewer = room.addViewer() ?: return reject(RejectReason.RoomFull)

    // When the client goes away (or the host's room ends), leave() closes the frame channel and the loop below stops.
    val watchdog = launch {
        try {
            for (ignored in incoming) Unit
        } finally {
            viewer.leave()
        }
    }
    try {
        send(Frame.Text(ServerMessage.Joined.encode()))
        room.lastFrame?.let { send(Frame.Binary(true, it)) }
        for (frame in viewer.frames) send(Frame.Binary(true, frame))
        send(Frame.Text(ServerMessage.Ended.encode()))
    } finally {
        watchdog.cancel()
        viewer.leave()
    }
}

private const val MAX_PASSWORD_LENGTH = 128
