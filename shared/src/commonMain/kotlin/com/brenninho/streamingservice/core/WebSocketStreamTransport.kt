package com.brenninho.streamingservice.core

import com.brenninho.streamingservice.protocol.ClientMessages
import com.brenninho.streamingservice.protocol.FrameCodec
import com.brenninho.streamingservice.protocol.RoomCode
import com.brenninho.streamingservice.protocol.ServerEndpoints
import com.brenninho.streamingservice.protocol.ServerMessage
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readBytes
import io.ktor.websocket.readText
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch

/** Creates the HTTP client the transport needs. Share one instance for the lifetime of the app. */
fun createStreamingHttpClient(): HttpClient = HttpClient {
    install(WebSockets)
}

/**
 * Talks to the relay server (`server` module) over WebSockets.
 *
 * [host] ends gracefully when the `frames` flow completes (with a goodbye to the server) and ends abruptly
 * when it is cancelled. Controllers complete `frames` to stop, and cancel only as a last resort.
 */
class WebSocketStreamTransport(
    private val endpoints: ServerEndpoints,
    private val client: HttpClient,
) : StreamTransport {

    override fun host(options: HostOptions, frames: Flow<VideoFrame>): Flow<HostEvent> = channelFlow {
        val events: ProducerScope<HostEvent> = this
        val url = endpoints.hostUrl(options.room, options.password, options.announce, options.name, options.token)
        client.webSocket(urlString = url) {
            val sender = launch {
                frames.collect { frame -> send(Frame.Binary(true, FrameCodec.encode(frame.width, frame.height, frame.jpeg))) }
            }
            val reader = launch {
                for (message in incoming) {
                    if (message !is Frame.Text) continue
                    when (val parsed = ServerMessage.parse(message.readText())) {
                        is ServerMessage.Room -> events.send(HostEvent.RoomOpened(parsed.code, parsed.token))
                        is ServerMessage.Viewers -> events.send(HostEvent.Viewers(parsed.count))
                        is ServerMessage.Error -> events.send(HostEvent.Rejected(parsed.reason))
                        else -> Unit
                    }
                }
            }
            // The server hung up: stop sending, the flow ends and the caller may reconnect.
            reader.invokeOnCompletion { sender.cancel() }

            sender.join()
            if (reader.isActive) {
                // `frames` ended on purpose (the user stopped sharing), so say goodbye and the room closes right away
                // instead of waiting for the host to come back. This needs a graceful end: if our own coroutine were
                // cancelled, Ktor would tear the socket down before the message could be written.
                runCatching {
                    send(Frame.Text(ClientMessages.BYE))
                    flush()
                    close(CloseReason(CloseReason.Codes.NORMAL, "bye"))
                }
            }
            reader.cancel()
        }
    }

    override fun watch(room: RoomCode, password: String?): Flow<ViewerEvent> = channelFlow {
        val events: ProducerScope<ViewerEvent> = this
        client.webSocket(urlString = endpoints.watchUrl(room, password)) {
            for (message in incoming) {
                when (message) {
                    is Frame.Binary -> FrameCodec.decode(message.readBytes())?.let {
                        events.send(ViewerEvent.Frame(VideoFrame(it.width, it.height, it.jpeg)))
                    }
                    is Frame.Text -> when (val parsed = ServerMessage.parse(message.readText())) {
                        ServerMessage.Joined -> events.send(ViewerEvent.Joined)
                        ServerMessage.Ended -> events.send(ViewerEvent.Ended)
                        is ServerMessage.Error -> events.send(ViewerEvent.Rejected(parsed.reason))
                        else -> Unit
                    }
                    else -> Unit
                }
            }
        }
    }
}
