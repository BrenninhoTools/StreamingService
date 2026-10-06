package com.brenninho.streamingservice

import com.brenninho.streamingservice.core.CaptureConfig
import com.brenninho.streamingservice.core.CapturePermissionDenied
import com.brenninho.streamingservice.core.HostController
import com.brenninho.streamingservice.core.HostEvent
import com.brenninho.streamingservice.core.HostOptions
import com.brenninho.streamingservice.core.HostPhase
import com.brenninho.streamingservice.core.HostState
import com.brenninho.streamingservice.core.InMemoryStreamTransport
import com.brenninho.streamingservice.core.ScreenCapturer
import com.brenninho.streamingservice.core.StreamError
import com.brenninho.streamingservice.core.StreamTransport
import com.brenninho.streamingservice.core.VideoFrame
import com.brenninho.streamingservice.core.ViewerController
import com.brenninho.streamingservice.core.ViewerEvent
import com.brenninho.streamingservice.core.ViewerPhase
import com.brenninho.streamingservice.protocol.RejectReason
import com.brenninho.streamingservice.protocol.RoomCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.yield
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

private val code = RoomCode("ABC234")

/**
 * Lets everything that is due run, including background-scope work. `advanceUntilIdle()` does not do
 * that: it ignores `backgroundScope` tasks, which is where the controllers live in these tests.
 */
@OptIn(ExperimentalCoroutinesApi::class)
private fun TestScope.settle() {
    advanceTimeBy(60_000)
    runCurrent()
}

private fun frame(marker: Int = 0) = VideoFrame(8, 8, byteArrayOf(0xFF.toByte(), 0xD8.toByte(), marker.toByte()))

private class FakeCapturer(
    override val isSupported: Boolean = true,
    private val failure: Exception? = null,
) : ScreenCapturer {
    override fun frames(config: CaptureConfig): Flow<VideoFrame> = flow {
        failure?.let { throw it }
        var i = 0
        while (true) {
            emit(frame(i++))
            delay(100)
        }
    }
}

private class ScriptedTransport(
    private val onHost: suspend ProducerScope<HostEvent>.(attempt: Int, options: HostOptions) -> Unit = { _, _ -> },
    private val onWatch: suspend ProducerScope<ViewerEvent>.(attempt: Int) -> Unit = { },
) : StreamTransport {
    val hostOptions = mutableListOf<HostOptions>()
    private var watchAttempts = 0

    override fun host(options: HostOptions, frames: Flow<VideoFrame>): Flow<HostEvent> = channelFlow {
        hostOptions += options
        // Like the real transport: the session ends gracefully when the frames flow completes.
        val pump = launch {
            frames.collect { }
            close()
        }
        try {
            onHost(hostOptions.size - 1, options)
        } finally {
            pump.cancel()
        }
    }

    override fun watch(room: RoomCode, password: String?): Flow<ViewerEvent> = channelFlow {
        onWatch(watchAttempts++)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class HostControllerTest {
    private val noDecode: suspend (VideoFrame) -> Nothing? = { null }

    @Test
    fun becomesLiveWhenTheServerOpensARoom() = runTest {
        val transport = ScriptedTransport(onHost = { _, _ ->
            send(HostEvent.RoomOpened(code, "tok"))
            send(HostEvent.Viewers(3))
            awaitClose()
        })
        val controller = HostController(FakeCapturer(), transport, backgroundScope, noDecode)

        controller.start(HostOptions(), CaptureConfig())
        runCurrent()

        val state = controller.state.value
        assertEquals(HostPhase.Live, state.phase)
        assertEquals(code, state.room)
        assertEquals(3, state.viewers)
        assertNull(state.error)

        controller.stop()
        runCurrent()
        assertEquals(HostPhase.Idle, controller.state.value.phase)
    }

    @Test
    fun reconnectsToTheSameRoomWithItsToken() = runTest {
        val transport = ScriptedTransport(onHost = { attempt, _ ->
            send(HostEvent.RoomOpened(code, "tok"))
            yield() // let the controller process the event before the connection breaks
            if (attempt == 0) throw RuntimeException("connection reset") else awaitClose()
        })
        val controller = HostController(FakeCapturer(), transport, backgroundScope, noDecode, retryDelaysMillis = listOf(500))

        controller.start(HostOptions(password = "pw", name = "Ana"), CaptureConfig())
        runCurrent()
        assertEquals(HostPhase.Reconnecting, controller.state.value.phase)

        advanceTimeBy(600)
        runCurrent()

        assertEquals(HostPhase.Live, controller.state.value.phase)
        assertEquals(2, transport.hostOptions.size)
        val second = transport.hostOptions[1]
        assertEquals(code, second.room)
        assertEquals("tok", second.token)
        assertEquals("pw", second.password)
        assertEquals("Ana", second.name)
        controller.stop()
    }

    @Test
    fun givesUpAfterTheRetriesRunOut() = runTest {
        val transport = ScriptedTransport(onHost = { _, _ -> throw RuntimeException("unreachable") })
        val controller = HostController(FakeCapturer(), transport, backgroundScope, noDecode, retryDelaysMillis = listOf(10, 10))

        controller.start(HostOptions(), CaptureConfig())
        settle()

        assertEquals(StreamError.ConnectionFailed, controller.state.value.error)
        assertEquals(HostPhase.Idle, controller.state.value.phase)
        assertEquals(3, transport.hostOptions.size) // first try + two retries
    }

    @Test
    fun reportsRejections() = runTest {
        val transport = ScriptedTransport(onHost = { _, _ -> send(HostEvent.Rejected(RejectReason.RoomTaken)) })
        val controller = HostController(FakeCapturer(), transport, backgroundScope, noDecode)

        controller.start(HostOptions(), CaptureConfig())
        settle()

        assertEquals(StreamError.RoomTaken, controller.state.value.error)
        assertEquals(1, transport.hostOptions.size) // not retried
    }

    @Test
    fun unsupportedCapturerFailsImmediately() = runTest {
        val controller = HostController(FakeCapturer(isSupported = false), ScriptedTransport(), backgroundScope, noDecode)
        controller.start(HostOptions(), CaptureConfig())
        assertEquals(StreamError.CaptureUnsupported, controller.state.value.error)
        assertEquals(HostPhase.Idle, controller.state.value.phase)
    }

    @Test
    fun deniedPermissionStopsTheStream() = runTest {
        val transport = ScriptedTransport(onHost = { _, _ ->
            send(HostEvent.RoomOpened(code, "tok"))
            awaitClose()
        })
        val controller = HostController(FakeCapturer(failure = CapturePermissionDenied()), transport, backgroundScope, noDecode)

        controller.start(HostOptions(), CaptureConfig())
        settle()

        assertEquals(StreamError.PermissionDenied, controller.state.value.error)
        assertEquals(HostPhase.Idle, controller.state.value.phase)
    }

    @Test
    fun startingTwiceDoesNotOpenTwoSessions() = runTest {
        val transport = ScriptedTransport(onHost = { _, _ -> awaitClose() })
        val controller = HostController(FakeCapturer(), transport, backgroundScope, noDecode)
        controller.start(HostOptions(), CaptureConfig())
        controller.start(HostOptions(), CaptureConfig())
        runCurrent()
        assertEquals(1, transport.hostOptions.size)
        controller.stop()
    }

    @Test
    fun idleStateHasNoActivity() {
        assertTrue(!HostState().isActive)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class ViewerControllerTest {
    private val noDecode: suspend (VideoFrame) -> Nothing? = { null }

    @Test
    fun invalidCodeIsRejectedLocally() = runTest {
        val controller = ViewerController(ScriptedTransport(), backgroundScope, noDecode)
        controller.join("nope")
        assertEquals(StreamError.InvalidCode, controller.state.value.error)
        assertEquals(ViewerPhase.Idle, controller.state.value.phase)
    }

    @Test
    fun watchesUntilTheHostEnds() = runTest {
        val transport = ScriptedTransport(onWatch = {
            send(ViewerEvent.Joined)
            send(ViewerEvent.Frame(frame()))
            send(ViewerEvent.Ended)
        })
        val controller = ViewerController(transport, backgroundScope, noDecode)

        controller.join("abc234")
        settle()

        assertEquals(ViewerPhase.HostEnded, controller.state.value.phase)
        assertNull(controller.state.value.error)
    }

    @Test
    fun staysWatchingWhileTheStreamIsLive() = runTest {
        val transport = ScriptedTransport(onWatch = {
            send(ViewerEvent.Joined)
            awaitClose()
        })
        val controller = ViewerController(transport, backgroundScope, noDecode)
        controller.join("ABC234")
        runCurrent()
        assertEquals(ViewerPhase.Watching, controller.state.value.phase)
        assertTrue(controller.state.value.isActive)

        controller.leave()
        runCurrent()
        assertEquals(ViewerPhase.Idle, controller.state.value.phase)
    }

    @Test
    fun wrongPasswordIsReported() = runTest {
        val transport = ScriptedTransport(onWatch = { send(ViewerEvent.Rejected(RejectReason.BadPassword)) })
        val controller = ViewerController(transport, backgroundScope, noDecode)
        controller.join("ABC234", "nope")
        settle()
        assertEquals(StreamError.BadPassword, controller.state.value.error)
    }

    @Test
    fun unknownRoomIsReportedUnlessWaitingForTheHost() = runTest {
        val transport = ScriptedTransport(onWatch = { send(ViewerEvent.Rejected(RejectReason.NotFound)) })
        val controller = ViewerController(transport, backgroundScope, noDecode)
        controller.join("ABC234")
        settle()
        assertEquals(StreamError.RoomNotFound, controller.state.value.error)
    }

    @Test
    fun waitForHostKeepsPollingUntilTheRoomAppears() = runTest {
        val transport = ScriptedTransport(onWatch = { attempt ->
            if (attempt < 2) {
                send(ViewerEvent.Rejected(RejectReason.NotFound))
            } else {
                send(ViewerEvent.Joined)
                awaitClose()
            }
        })
        val controller = ViewerController(transport, backgroundScope, noDecode, waitPollMillis = 1_000)

        controller.join("ABC234", waitForHost = true)
        runCurrent()
        assertEquals(ViewerPhase.WaitingForHost, controller.state.value.phase)
        assertNull(controller.state.value.error)

        advanceTimeBy(2_500)
        runCurrent()
        assertEquals(ViewerPhase.Watching, controller.state.value.phase)
        controller.leave()
    }

    @Test
    fun reconnectsAfterADroppedConnection() = runTest {
        val transport = ScriptedTransport(onWatch = { attempt ->
            send(ViewerEvent.Joined)
            if (attempt == 0) throw RuntimeException("reset") else awaitClose()
        })
        val controller = ViewerController(transport, backgroundScope, noDecode, retryDelaysMillis = listOf(200))

        controller.join("ABC234")
        runCurrent()
        assertEquals(ViewerPhase.Connecting, controller.state.value.phase)
        advanceTimeBy(300)
        runCurrent()
        assertEquals(ViewerPhase.Watching, controller.state.value.phase)
        controller.leave()
    }
}

class InMemoryTransportTest {
    @Test
    fun hostAndViewerSeeEachOther() = runTest {
        val transport = InMemoryStreamTransport()
        val frames = flow {
            repeat(100) {
                emit(frame(it))
                delay(10)
            }
        }
        val opened = transport.host(HostOptions(), frames)
        var room: RoomCode? = null
        val hostJob = launch {
            opened.collect { if (it is HostEvent.RoomOpened) room = it.code }
        }
        runCurrent()
        val code = assertNotNull(room)

        val first = transport.watch(code, null).first { it is ViewerEvent.Frame }
        assertTrue(first is ViewerEvent.Frame)
        hostJob.cancel()
    }

    @Test
    fun unknownRoomIsNotFound() = runTest {
        val event = InMemoryStreamTransport().watch(code, null).first()
        assertEquals(ViewerEvent.Rejected(RejectReason.NotFound), event)
    }
}
