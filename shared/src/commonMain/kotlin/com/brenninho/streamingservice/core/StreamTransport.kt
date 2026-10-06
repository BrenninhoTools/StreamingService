package com.brenninho.streamingservice.core

import com.brenninho.streamingservice.protocol.RejectReason
import com.brenninho.streamingservice.protocol.RoomCode
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class HostOptions(
    /** A specific room code to use (Discord Activities), or null to let the server pick one. */
    val room: RoomCode? = null,
    /** Proof of ownership, set by the controller when it reconnects after a dropped connection. */
    val token: String? = null,
    val password: String? = null,
    /** Ask the server to announce the stream on Discord (only works if the server has a webhook). */
    val announce: Boolean = false,
    val name: String? = null,
) {
    fun reconnecting(room: RoomCode, token: String) =
        HostOptions(room, token, password, announce, name)
}

sealed interface HostEvent {
    data class RoomOpened(val code: RoomCode, val token: String) : HostEvent
    data class Viewers(val count: Int) : HostEvent
    data class Rejected(val reason: RejectReason) : HostEvent
}

sealed interface ViewerEvent {
    data object Joined : ViewerEvent
    class Frame(val frame: VideoFrame) : ViewerEvent
    data object Ended : ViewerEvent
    data class Rejected(val reason: RejectReason) : ViewerEvent
}

/**
 * Moves frames from a host to its viewers. Implementations: [WebSocketStreamTransport] (talks to the
 * relay server) and [InMemoryStreamTransport] (same process, for tests and demos).
 *
 * Both flows are cold: collecting connects, cancelling disconnects. A flow that completes or throws
 * on its own means the connection was lost, so callers may reconnect.
 */
interface StreamTransport {
    /** Publishes [frames] and reports room/audience events. Cancelling it ends the stream deliberately. */
    fun host(options: HostOptions, frames: Flow<VideoFrame>): Flow<HostEvent>

    fun watch(room: RoomCode, password: String?): Flow<ViewerEvent>
}

/** Same-process transport: host and viewer of one app instance can see each other. No network involved. */
class InMemoryStreamTransport : StreamTransport {
    private class Room(val password: String?) {
        val frames = MutableSharedFlow<VideoFrame>(replay = 1, extraBufferCapacity = 4, onBufferOverflow = BufferOverflow.DROP_OLDEST)
        val viewers = MutableStateFlow(0)
        val live = MutableStateFlow(true)
    }

    private val rooms = mutableMapOf<RoomCode, Room>()

    override fun host(options: HostOptions, frames: Flow<VideoFrame>): Flow<HostEvent> = callbackFlow {
        val code = options.room ?: RoomCode.generate()
        val room = Room(options.password)
        rooms[code] = room
        trySend(HostEvent.RoomOpened(code, "local"))
        val pump = launch { frames.collect { room.frames.emit(it) } }
        val counter = launch { room.viewers.collect { trySend(HostEvent.Viewers(it)) } }
        awaitClose {
            pump.cancel()
            counter.cancel()
            room.live.value = false
            if (rooms[code] === room) rooms.remove(code)
        }
    }

    override fun watch(room: RoomCode, password: String?): Flow<ViewerEvent> = callbackFlow {
        val target = rooms[room]
        when {
            target == null -> trySend(ViewerEvent.Rejected(RejectReason.NotFound))
            target.password != null && target.password != password -> trySend(ViewerEvent.Rejected(RejectReason.BadPassword))
            else -> {
                target.viewers.update { it + 1 }
                trySend(ViewerEvent.Joined)
                val pump = launch { target.frames.collect { trySend(ViewerEvent.Frame(it)) } }
                val watcher = launch {
                    target.live.collect { live ->
                        if (!live) {
                            trySend(ViewerEvent.Ended)
                            close()
                        }
                    }
                }
                awaitClose {
                    pump.cancel()
                    watcher.cancel()
                    target.viewers.update { it - 1 }
                }
                return@callbackFlow
            }
        }
        close()
        awaitClose { }
    }
}
