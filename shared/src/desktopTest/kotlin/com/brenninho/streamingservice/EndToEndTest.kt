package com.brenninho.streamingservice

import com.brenninho.streamingservice.core.CaptureConfig
import com.brenninho.streamingservice.core.HostController
import com.brenninho.streamingservice.core.HostOptions
import com.brenninho.streamingservice.core.HostPhase
import com.brenninho.streamingservice.core.ScreenCapturer
import com.brenninho.streamingservice.core.VideoFrame
import com.brenninho.streamingservice.core.ViewerController
import com.brenninho.streamingservice.core.ViewerEvent
import com.brenninho.streamingservice.core.ViewerPhase
import com.brenninho.streamingservice.core.WebSocketStreamTransport
import com.brenninho.streamingservice.core.createStreamingHttpClient
import com.brenninho.streamingservice.protocol.RejectReason
import com.brenninho.streamingservice.protocol.ServerEndpoints
import com.brenninho.streamingservice.server.ServerConfig
import com.brenninho.streamingservice.server.streamingModule
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/** A capturer that "films" a counter: every frame is a tiny fake JPEG whose third byte is its index. */
private class CountingCapturer : ScreenCapturer {
    override val isSupported = true
    override fun frames(config: CaptureConfig): Flow<VideoFrame> = flow {
        var i = 0
        while (true) {
            emit(VideoFrame(16, 9, byteArrayOf(0xFF.toByte(), 0xD8.toByte(), (i++ % 100).toByte(), 0xFF.toByte(), 0xD9.toByte())))
            delay(40)
        }
    }
}

/** Real client code talking to the real relay server over a real socket. */
class EndToEndTest {
    private fun withServer(block: suspend (ServerEndpoints, WebSocketStreamTransport, CoroutineScope) -> Unit) = runBlocking {
        // A long grace period proves that "stop" closes the room through the goodbye message, not by timing out.
        val server = embeddedServer(CIO, port = 0) { streamingModule(ServerConfig(hostGraceMillis = 120_000)) }.start(wait = false)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val client = createStreamingHttpClient()
        try {
            val port = server.engine.resolvedConnectors().first().port
            val endpoints = assertNotNull(ServerEndpoints.parse("localhost:$port"))
            withTimeout(30.seconds) { block(endpoints, WebSocketStreamTransport(endpoints, client), scope) }
        } finally {
            scope.cancel()
            client.close()
            server.stop(100, 500)
        }
    }

    private suspend fun HostController.awaitState(timeoutSeconds: Int = 10, predicate: (com.brenninho.streamingservice.core.HostState) -> Boolean) =
        withTimeout(timeoutSeconds.seconds) { state.first(predicate) }

    @Test
    fun framesFlowFromHostToViewerAndStoppingEndsTheRoomAtOnce() = withServer { _, transport, scope ->
        val host = HostController(CountingCapturer(), transport, scope, decoder = { null })
        host.start(HostOptions(password = "pw", name = "Test"), CaptureConfig(fps = 25))

        val live = host.awaitState { it.phase == HostPhase.Live && it.room != null }
        val room = assertNotNull(live.room)

        // A viewer with the wrong password is turned away.
        val denied = transport.watch(room, "wrong").first()
        assertEquals(ViewerEvent.Rejected(RejectReason.BadPassword), denied)

        val events = transport.watch(room, "pw")
        assertEquals(ViewerEvent.Joined, events.first())
        val frame = withTimeout(10.seconds) { events.filterIsInstance<ViewerEvent.Frame>().first() }.frame
        assertEquals(16, frame.width)
        assertEquals(9, frame.height)
        assertContentEquals(byteArrayOf(0xFF.toByte(), 0xD8.toByte()), frame.jpeg.copyOfRange(0, 2))

        // The host sees its audience.
        val viewer = ViewerController(transport, scope, decoder = { null })
        viewer.join(room.value, "pw")
        withTimeout(10.seconds) { viewer.state.first { it.phase == ViewerPhase.Watching } }
        host.awaitState { it.viewers >= 1 }

        host.stop()
        // The viewer is told the stream ended right away (the grace period is two minutes).
        withTimeout(10.seconds) { viewer.state.first { it.phase == ViewerPhase.HostEnded } }
        host.awaitState { it.phase == HostPhase.Idle }
    }

    @Test
    fun unknownRoomIsRejectedByTheServer() = withServer { _, transport, scope ->
        val viewer = ViewerController(transport, scope, decoder = { null })
        viewer.join("ZZZ999")
        val state = withTimeout(10.seconds) { viewer.state.first { it.error != null } }
        assertEquals(com.brenninho.streamingservice.core.StreamError.RoomNotFound, state.error)
    }

    @Test
    fun hostCannotUseARoomThatIsTaken() = withServer { _, transport, scope ->
        val first = HostController(CountingCapturer(), transport, scope, decoder = { null })
        first.start(HostOptions(room = com.brenninho.streamingservice.protocol.RoomCode("ABC234")), CaptureConfig())
        first.awaitState { it.phase == HostPhase.Live }

        val second = HostController(CountingCapturer(), transport, scope, decoder = { null })
        second.start(HostOptions(room = com.brenninho.streamingservice.protocol.RoomCode("ABC234")), CaptureConfig())
        val state = second.awaitState { it.error != null }
        assertEquals(com.brenninho.streamingservice.core.StreamError.RoomTaken, state.error)
        first.stop()
    }

    @Test
    fun unreachableServerFailsInsteadOfHanging() = runBlocking {
        val client = createStreamingHttpClient()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            // Nothing listens on port 1.
            val transport = WebSocketStreamTransport(assertNotNull(ServerEndpoints.parse("localhost:1")), client)
            val viewer = ViewerController(transport, scope, decoder = { null }, retryDelaysMillis = listOf(50, 50))
            viewer.join("ABC234")
            val state = withTimeout(20.seconds) { viewer.state.first { it.error != null } }
            assertEquals(com.brenninho.streamingservice.core.StreamError.ConnectionFailed, state.error)
        } finally {
            scope.cancel()
            client.close()
        }
    }
}
