package com.brenninho.streamingservice.protocol

/** Why the server refused a host or viewer. The wire name is what travels in `error:<name>`. */
enum class RejectReason(val wireName: String) {
    NotFound("not_found"),
    BadRoom("bad_room"),
    RoomTaken("room_taken"),
    RoomFull("room_full"),
    BadPassword("bad_password"),
    ServerBusy("server_busy"),
    ;

    companion object {
        fun fromWire(name: String): RejectReason? = entries.firstOrNull { it.wireName == name }
    }
}

/** Text messages a client may send. Video frames from a host travel as binary messages instead. */
object ClientMessages {
    /** Host: "I am stopping on purpose", so the server ends the room at once instead of waiting for a reconnect. */
    const val BYE = "bye"
}

/**
 * Text control messages sent by the server over the WebSocket. Video frames travel as binary
 * messages (see [FrameCodec]), never as text.
 *
 * - host receives: [Room] once connected, then [Viewers] whenever the audience changes
 * - viewer receives: [Joined], then frames, then [Ended] when the host stops
 * - either side may receive [Error] right before the server closes the connection
 */
sealed interface ServerMessage {
    /** [token] proves ownership of the room: a host that lost its connection sends it to take the room back. */
    data class Room(val code: RoomCode, val token: String) : ServerMessage
    data class Viewers(val count: Int) : ServerMessage
    data object Joined : ServerMessage
    data object Ended : ServerMessage
    data class Error(val reason: RejectReason) : ServerMessage

    fun encode(): String = when (this) {
        is Room -> "room:${code.value}:$token"
        is Viewers -> "viewers:$count"
        Joined -> "joined"
        Ended -> "ended"
        is Error -> "error:${reason.wireName}"
    }

    companion object {
        /** Returns null for anything that is not a well-formed message, so callers can ignore it. */
        fun parse(text: String): ServerMessage? {
            val separator = text.indexOf(':')
            val kind = if (separator < 0) text else text.substring(0, separator)
            val argument = if (separator < 0) "" else text.substring(separator + 1)
            return when (kind) {
                "room" -> {
                    val code = RoomCode.parse(argument.substringBefore(':'))
                    val token = argument.substringAfter(':', missingDelimiterValue = "")
                    if (code != null && token.isNotEmpty()) Room(code, token) else null
                }
                "viewers" -> argument.toIntOrNull()?.takeIf { it >= 0 }?.let(::Viewers)
                "joined" -> Joined
                "ended" -> Ended
                "error" -> RejectReason.fromWire(argument)?.let(::Error)
                else -> null
            }
        }
    }
}
