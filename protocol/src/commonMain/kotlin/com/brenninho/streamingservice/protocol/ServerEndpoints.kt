package com.brenninho.streamingservice.protocol

/**
 * Builds the URLs of a relay server from whatever the user typed in the settings:
 * `https://stream.example.com`, `wss://stream.example.com/`, `localhost:8080`, ...
 *
 * A path prefix is kept, which is what lets the web app run behind Discord's `/.proxy` mapping.
 */
class ServerEndpoints private constructor(private val wsBase: String, private val httpBase: String) {

    /** [token] is only set when reclaiming a room after a dropped connection. */
    fun hostUrl(room: RoomCode?, password: String?, announce: Boolean, name: String?, token: String? = null): String {
        val query = buildList {
            if (room != null) add("room" to room.value)
            if (!token.isNullOrEmpty()) add("token" to token)
            if (!password.isNullOrEmpty()) add("password" to password)
            if (announce) add("announce" to "1")
            if (!name.isNullOrBlank()) add("name" to name.trim().take(MAX_NAME_LENGTH))
        }
        return "$wsBase/ws/host" + query.toQueryString()
    }

    fun watchUrl(room: RoomCode, password: String?): String {
        val query = if (password.isNullOrEmpty()) emptyList() else listOf("password" to password)
        return "$wsBase/ws/watch/${room.value}" + query.toQueryString()
    }

    /** Link that opens the web app straight into [room]. */
    fun joinLink(room: RoomCode): String = "$httpBase/?room=${room.value}"

    /** Where the web app reads the server's public configuration (e.g. the Discord client id). */
    fun configUrl(): String = "$httpBase/api/config"

    companion object {
        const val MAX_NAME_LENGTH = 32

        private val insecurePrefixes = listOf("localhost", "127.", "10.", "192.168.", "[::1]")

        /** Returns null when [input] does not look like a server address. */
        fun parse(input: String): ServerEndpoints? {
            val trimmed = input.trim()
            if (trimmed.isEmpty() || trimmed.any { it.isWhitespace() }) return null

            val schemeEnd = trimmed.indexOf("://")
            val scheme: String
            val rest: String
            if (schemeEnd >= 0) {
                scheme = trimmed.substring(0, schemeEnd).lowercase()
                rest = trimmed.substring(schemeEnd + 3).trimEnd('/')
            } else {
                rest = trimmed.trimEnd('/')
                scheme = if (insecurePrefixes.any { rest.startsWith(it) }) "http" else "https"
            }
            if (rest.isEmpty() || rest.startsWith("/")) return null

            val secure = when (scheme) {
                "https", "wss" -> true
                "http", "ws" -> false
                else -> return null
            }
            return ServerEndpoints(
                wsBase = (if (secure) "wss://" else "ws://") + rest,
                httpBase = (if (secure) "https://" else "http://") + rest,
            )
        }
    }
}

private fun List<Pair<String, String>>.toQueryString(): String =
    if (isEmpty()) "" else joinToString(separator = "&", prefix = "?") { (k, v) -> "$k=${urlEncode(v)}" }

/** Percent-encodes [value] as UTF-8 (RFC 3986 unreserved characters are kept). */
internal fun urlEncode(value: String): String = buildString {
    for (byte in value.encodeToByteArray()) {
        val c = byte.toInt() and 0xFF
        val unreserved = c in 'a'.code..'z'.code || c in 'A'.code..'Z'.code || c in '0'.code..'9'.code ||
            c == '-'.code || c == '_'.code || c == '.'.code || c == '~'.code
        if (unreserved) {
            append(c.toChar())
        } else {
            append('%')
            append("0123456789ABCDEF"[c shr 4])
            append("0123456789ABCDEF"[c and 0xF])
        }
    }
}
