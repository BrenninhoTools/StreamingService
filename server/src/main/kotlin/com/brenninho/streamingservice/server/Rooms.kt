package com.brenninho.streamingservice.server

import com.brenninho.streamingservice.protocol.RoomCode
import com.brenninho.streamingservice.protocol.ServerMessage
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet
import kotlin.random.Random
import kotlin.random.asKotlinRandom
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** One viewer's subscription. Slow viewers drop old frames instead of slowing the host down. */
class ViewerHandle internal constructor(private val room: Room) {
    val frames = Channel<ByteArray>(capacity = 2, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** Idempotent. Closing [frames] also wakes up whoever is waiting on it. */
    fun leave() {
        room.removeViewer(this)
        frames.close()
    }
}

/** One connection of a host to its room. A reconnecting host gets a new session on the same [Room]. */
class HostSession internal constructor(val room: Room) {
    /** Audience updates for the host. Closed when a newer session takes over or the room ends. */
    val events = Channel<ServerMessage>(Channel.UNLIMITED)
}

class Room internal constructor(
    val code: RoomCode,
    val token: String,
    private val password: String?,
    private val maxViewers: Int,
) {
    private val lock = Any()
    private val viewers = CopyOnWriteArraySet<ViewerHandle>()
    private var session: HostSession? = null
    private var ended = false

    internal var graceJob: Job? = null

    /** Latest frame, replayed to viewers that join mid-stream. */
    @Volatile
    var lastFrame: ByteArray? = null
        private set

    /** Set by the Discord announcer so it can edit its message when the stream ends. */
    @Volatile
    var announcementId: String? = null

    /** Display name the host announced itself with (only used for Discord messages). */
    @Volatile
    var hostName: String? = null

    val hasPassword: Boolean get() = password != null
    val viewerCount: Int get() = viewers.size

    fun checkPassword(candidate: String?): Boolean {
        val expected = password ?: return true
        return candidate != null && constantTimeEquals(expected, candidate)
    }

    fun checkToken(candidate: String?): Boolean = candidate != null && constantTimeEquals(token, candidate)

    internal fun attachHost(): HostSession = synchronized(lock) {
        graceJob?.cancel()
        session?.events?.close()
        HostSession(this).also {
            session = it
            it.events.trySend(ServerMessage.Viewers(viewers.size))
        }
    }

    internal fun isCurrent(candidate: HostSession): Boolean = synchronized(lock) { session === candidate }

    fun addViewer(): ViewerHandle? = synchronized(lock) {
        if (ended || viewers.size >= maxViewers) return null
        ViewerHandle(this).also {
            viewers += it
            session?.events?.trySend(ServerMessage.Viewers(viewers.size))
        }
    }

    internal fun removeViewer(handle: ViewerHandle) = synchronized(lock) {
        if (viewers.remove(handle)) session?.events?.trySend(ServerMessage.Viewers(viewers.size))
    }

    fun publish(frame: ByteArray) {
        lastFrame = frame
        for (viewer in viewers) viewer.frames.trySend(frame)
    }

    internal fun end() {
        val closing = synchronized(lock) {
            ended = true
            graceJob?.cancel()
            session?.events?.close()
            viewers.toList().also { viewers.clear() }
        }
        for (viewer in closing) viewer.frames.close()
    }
}

sealed interface OpenResult {
    /** [reclaimed] is true when a returning host took back its existing room. */
    data class Opened(val session: HostSession, val reclaimed: Boolean) : OpenResult
    data object Taken : OpenResult
    data object Full : OpenResult
}

/**
 * All live rooms. Pure in-memory logic with no networking, so it is easy to unit test.
 *
 * @param scope runs the grace timers; cancelling it (server shutdown) cancels pending room expirations.
 */
class RoomRegistry(
    private val maxRooms: Int,
    private val maxViewersPerRoom: Int,
    private val scope: CoroutineScope,
    private val graceMillis: Long = 20_000,
    private val random: Random = SecureRandom().asKotlinRandom(),
) {
    private val rooms = ConcurrentHashMap<RoomCode, Room>()

    /** Called once per room when it ends, whatever the reason. */
    @Volatile
    var onRoomClosed: (Room) -> Unit = {}

    val roomCount: Int get() = rooms.size

    fun find(code: RoomCode): Room? = rooms[code]

    /**
     * Opens a room for a host.
     *
     * @param requested a specific code to use, or null to get a random one
     * @param token the room token, to take back a room after a dropped connection
     */
    fun open(requested: RoomCode?, password: String?, token: String?): OpenResult = synchronized(this) {
        if (requested != null) {
            val existing = rooms[requested]
            if (existing != null) {
                return if (existing.checkToken(token)) OpenResult.Opened(existing.attachHost(), reclaimed = true) else OpenResult.Taken
            }
        }
        if (rooms.size >= maxRooms) return OpenResult.Full
        val code = requested ?: generateUnusedCode() ?: return OpenResult.Full
        val room = Room(code, generateToken(), password, maxViewersPerRoom)
        rooms[code] = room
        OpenResult.Opened(room.attachHost(), reclaimed = false)
    }

    /**
     * The host's connection ended. With [immediate] (the host said goodbye) the room closes now,
     * otherwise it lingers for the grace period so the host can reconnect.
     */
    fun hostLeft(session: HostSession, immediate: Boolean) {
        val room = session.room
        if (!room.isCurrent(session)) return // a newer connection already took over
        if (immediate || graceMillis <= 0) {
            close(room)
        } else {
            room.graceJob = scope.launch {
                delay(graceMillis)
                if (room.isCurrent(session)) close(room)
            }
        }
    }

    fun close(room: Room) {
        if (rooms.remove(room.code, room)) {
            room.end()
            onRoomClosed(room)
        }
    }

    private fun generateUnusedCode(): RoomCode? =
        (1..20).map { RoomCode.generate(random) }.firstOrNull { !rooms.containsKey(it) }

    private fun generateToken(): String = buildString {
        repeat(24) { append(TOKEN_ALPHABET[random.nextInt(TOKEN_ALPHABET.length)]) }
    }

    private companion object {
        const val TOKEN_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
    }
}

private fun constantTimeEquals(a: String, b: String): Boolean =
    MessageDigest.isEqual(a.toByteArray(Charsets.UTF_8), b.toByteArray(Charsets.UTF_8))
