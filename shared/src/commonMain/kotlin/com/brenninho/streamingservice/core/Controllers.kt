package com.brenninho.streamingservice.core

import androidx.compose.ui.graphics.ImageBitmap
import com.brenninho.streamingservice.protocol.RejectReason
import com.brenninho.streamingservice.protocol.RoomCode
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class StreamError {
    CaptureUnsupported,
    CaptureFailed,
    PermissionDenied,
    InvalidServer,
    InvalidCode,
    ConnectionFailed,
    RoomNotFound,
    RoomTaken,
    RoomFull,
    BadPassword,
    ServerBusy,
}

private fun RejectReason.toError(): StreamError = when (this) {
    RejectReason.NotFound, RejectReason.BadRoom -> StreamError.RoomNotFound
    RejectReason.RoomTaken -> StreamError.RoomTaken
    RejectReason.RoomFull -> StreamError.RoomFull
    RejectReason.BadPassword -> StreamError.BadPassword
    RejectReason.ServerBusy -> StreamError.ServerBusy
}

/** Pauses between reconnection attempts. Running out of them means the connection is considered lost. */
private val DefaultRetryDelays = listOf(1_000L, 2_000L, 4_000L, 8_000L, 10_000L)

/** How long stopping a stream waits for the goodbye to reach the server before cutting the connection. */
private const val GOODBYE_TIMEOUT_MILLIS = 2_000L

enum class HostPhase { Idle, Connecting, Live, Reconnecting }

data class HostState(
    val phase: HostPhase = HostPhase.Idle,
    val room: RoomCode? = null,
    val viewers: Int = 0,
    val preview: ImageBitmap? = null,
    /** Outgoing video bitrate in kilobits per second, averaged over the last second or so. */
    val kbps: Int = 0,
    val error: StreamError? = null,
) {
    val isActive: Boolean get() = phase != HostPhase.Idle
}

/**
 * Captures the screen and publishes it. The capture runs once and survives reconnections:
 * if the connection drops, the controller reconnects to the same room and keeps streaming.
 */
class HostController(
    private val capturer: ScreenCapturer,
    private val transport: StreamTransport,
    private val scope: CoroutineScope,
    private val decoder: FrameDecoder = DefaultFrameDecoder,
    private val retryDelaysMillis: List<Long> = DefaultRetryDelays,
) {
    private val _state = MutableStateFlow(HostState())
    val state: StateFlow<HostState> = _state.asStateFlow()
    private var job: Job? = null
    private var captureJob: Job? = null
    private var stopping = false

    fun start(options: HostOptions, config: CaptureConfig) {
        if (job != null) return
        if (!capturer.isSupported) {
            _state.value = HostState(error = StreamError.CaptureUnsupported)
            return
        }
        _state.value = HostState(phase = HostPhase.Connecting)
        job = scope.launch {
            val frames = Channel<VideoFrame>(capacity = 2, onBufferOverflow = BufferOverflow.DROP_OLDEST)
            try {
                coroutineScope {
                    val capture = launch { runCapture(config, frames) }
                    captureJob = capture
                    val session = launch { runSessions(options, frames) }
                    // Capture over (stopped, or failed): close the frame channel so the transport ends the
                    // stream gracefully. If the session ends first (server gave up), stop capturing.
                    capture.invokeOnCompletion { frames.close() }
                    session.invokeOnCompletion { capture.cancel() }
                }
            } finally {
                _state.update { it.copy(phase = HostPhase.Idle, viewers = 0, preview = null, kbps = 0) }
                captureJob = null
                stopping = false
                job = null
            }
        }
    }

    /**
     * Ends the stream. The server is told right away so viewers see "ended" instead of waiting for the
     * host to come back; if that goodbye does not complete in time, the connection is simply cut.
     */
    fun stop() {
        val running = job ?: return
        if (stopping) return
        stopping = true
        captureJob?.cancel()
        scope.launch {
            delay(GOODBYE_TIMEOUT_MILLIS)
            running.cancel()
        }
    }

    private suspend fun runCapture(config: CaptureConfig, out: SendChannel<VideoFrame>) {
        val clock = TimeSource.Monotonic
        var windowStart = clock.markNow()
        var windowBytes = 0
        var lastPreview = clock.markNow() - 1.seconds
        try {
            capturer.frames(config).collect { frame ->
                out.trySend(frame) // the channel drops its oldest frame when the network is slower than the capture
                windowBytes += frame.jpeg.size

                var kbps = _state.value.kbps
                val elapsed = windowStart.elapsedNow()
                if (elapsed >= 1.seconds) {
                    kbps = (windowBytes * 8L / elapsed.inWholeMilliseconds).toInt()
                    windowStart = clock.markNow()
                    windowBytes = 0
                }
                // The on-screen preview does not need more than ~8 fps.
                val preview = if (lastPreview.elapsedNow() >= 120.milliseconds) decoder(frame).also { lastPreview = clock.markNow() } else null
                _state.update { it.copy(preview = preview ?: it.preview, kbps = kbps) }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: CapturePermissionDenied) {
            fail(StreamError.PermissionDenied)
        } catch (e: Exception) {
            fail(StreamError.CaptureFailed)
        }
    }

    @OptIn(DelicateCoroutinesApi::class)
    private suspend fun runSessions(initial: HostOptions, frames: Channel<VideoFrame>) {
        var options = initial
        var everOpened = false
        var failures = 0
        while (true) {
            var rejected: RejectReason? = null
            try {
                transport.host(options, frames.receiveAsFlow()).collect { event ->
                    when (event) {
                        is HostEvent.RoomOpened -> {
                            everOpened = true
                            failures = 0
                            options = options.reconnecting(event.code, event.token)
                            _state.update { it.copy(phase = HostPhase.Live, room = event.code) }
                        }
                        is HostEvent.Viewers -> _state.update { it.copy(viewers = event.count) }
                        is HostEvent.Rejected -> rejected = event.reason
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Network failure: handled below like any other lost connection.
            }

            // The stream was ended on purpose (the capture is over): not a lost connection.
            if (frames.isClosedForSend) return

            rejected?.let { reason ->
                // Right after a drop the server may still hold the old connection: "taken" is worth another try.
                if (!(reason == RejectReason.RoomTaken && everOpened)) {
                    fail(reason.toError())
                    return
                }
            }

            // Never connected: give up quickly so a wrong server address does not spin for half a minute.
            val allowed = if (everOpened) retryDelaysMillis.size else minOf(2, retryDelaysMillis.size)
            if (failures >= allowed) {
                fail(StreamError.ConnectionFailed)
                return
            }
            _state.update { it.copy(phase = if (everOpened) HostPhase.Reconnecting else HostPhase.Connecting, viewers = 0) }
            delay(retryDelaysMillis[failures])
            failures++
        }
    }

    private fun fail(error: StreamError) {
        _state.update { it.copy(error = error) }
    }
}

enum class ViewerPhase { Idle, Connecting, WaitingForHost, Watching, HostEnded }

data class ViewerState(
    val phase: ViewerPhase = ViewerPhase.Idle,
    val frame: ImageBitmap? = null,
    val error: StreamError? = null,
) {
    val isActive: Boolean get() = phase != ViewerPhase.Idle && phase != ViewerPhase.HostEnded
}

/** Joins a room by code and exposes the frames it receives, reconnecting if the connection drops. */
class ViewerController(
    private val transport: StreamTransport,
    private val scope: CoroutineScope,
    private val decoder: FrameDecoder = DefaultFrameDecoder,
    private val retryDelaysMillis: List<Long> = DefaultRetryDelays,
    private val waitPollMillis: Long = 2_000,
) {
    private val _state = MutableStateFlow(ViewerState())
    val state: StateFlow<ViewerState> = _state.asStateFlow()
    private var job: Job? = null

    /**
     * @param waitForHost keep retrying while the room does not exist yet, and again after the host ends.
     * Used for preset rooms (Discord Activity), where viewers usually arrive before the host.
     */
    fun join(code: String, password: String? = null, waitForHost: Boolean = false) {
        if (job != null) return
        val room = RoomCode.parse(code)
        if (room == null) {
            _state.value = ViewerState(error = StreamError.InvalidCode)
            return
        }
        _state.value = ViewerState(phase = ViewerPhase.Connecting)
        job = scope.launch {
            try {
                watchLoop(room, password?.takeIf { it.isNotEmpty() }, waitForHost)
            } finally {
                _state.update { if (it.phase == ViewerPhase.HostEnded) it else it.copy(phase = ViewerPhase.Idle, frame = null) }
                job = null
            }
        }
    }

    fun leave() {
        job?.cancel()
    }

    private suspend fun watchLoop(room: RoomCode, password: String?, waitForHost: Boolean) {
        var failures = 0
        while (true) {
            var ended = false
            var rejected: RejectReason? = null
            try {
                transport.watch(room, password).collect { event ->
                    when (event) {
                        ViewerEvent.Joined -> {
                            failures = 0
                            _state.update { it.copy(phase = ViewerPhase.Watching, error = null) }
                        }
                        is ViewerEvent.Frame -> {
                            val bitmap = decoder(event.frame)
                            _state.update { it.copy(frame = bitmap ?: it.frame) }
                        }
                        ViewerEvent.Ended -> ended = true
                        is ViewerEvent.Rejected -> rejected = event.reason
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Network failure: handled below like any other lost connection.
            }

            if (ended) {
                if (!waitForHost) {
                    _state.update { it.copy(phase = ViewerPhase.HostEnded) }
                    return
                }
                _state.update { it.copy(phase = ViewerPhase.WaitingForHost, frame = null) }
                delay(waitPollMillis)
                continue
            }
            rejected?.let { reason ->
                if (reason == RejectReason.NotFound && waitForHost) {
                    _state.update { it.copy(phase = ViewerPhase.WaitingForHost) }
                    delay(waitPollMillis)
                    return@let
                }
                _state.update { it.copy(error = reason.toError()) }
                return
            }
            if (rejected != null) continue

            if (failures >= retryDelaysMillis.size) {
                _state.update { it.copy(error = StreamError.ConnectionFailed) }
                return
            }
            _state.update { it.copy(phase = ViewerPhase.Connecting) }
            delay(retryDelaysMillis[failures])
            failures++
        }
    }
}
