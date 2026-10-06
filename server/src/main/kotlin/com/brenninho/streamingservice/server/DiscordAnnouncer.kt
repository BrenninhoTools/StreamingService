package com.brenninho.streamingservice.server

import io.ktor.client.HttpClient
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory

/**
 * Posts "X is live" to a Discord channel through a webhook, and edits the message when the stream ends.
 *
 * The webhook URL never leaves the server. Announcements are rate limited so nobody can use the
 * server to flood the channel by opening and closing rooms.
 */
class DiscordAnnouncer(
    private val webhookUrl: String,
    private val publicUrl: String?,
    private val client: HttpClient,
    private val scope: CoroutineScope,
    private val maxPerMinute: Int = 5,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val log = LoggerFactory.getLogger(DiscordAnnouncer::class.java)
    private val recent = ArrayDeque<Long>()

    fun opened(room: Room, hostName: String?) {
        if (!allowed()) {
            log.warn("Discord announcement skipped: rate limit reached")
            return
        }
        room.hostName = hostName
        val content = DiscordMessages.live(hostName, room.code.value, room.hasPassword, publicUrl)
        scope.launch {
            try {
                val response = client.post(webhookUrl.withQuery("wait=true")) {
                    contentType(ContentType.Application.Json)
                    setBody(DiscordMessages.payload(content))
                }
                if (response.status.isSuccess()) {
                    room.announcementId = DiscordMessages.parseMessageId(response.bodyAsText())
                } else {
                    log.warn("Discord webhook answered {}", response.status)
                }
            } catch (e: Exception) {
                log.warn("Discord announcement failed: {}", e.message)
            }
        }
    }

    fun closed(room: Room) {
        val messageId = room.announcementId ?: return
        scope.launch {
            try {
                client.patch(webhookUrl.substringBefore('?') + "/messages/$messageId") {
                    contentType(ContentType.Application.Json)
                    setBody(DiscordMessages.payload(DiscordMessages.ended(room.hostName)))
                }
            } catch (e: Exception) {
                log.warn("Discord message update failed: {}", e.message)
            }
        }
    }

    @Synchronized
    private fun allowed(): Boolean {
        val now = clock()
        while (recent.isNotEmpty() && now - recent.first() > 60_000) recent.removeFirst()
        if (recent.size >= maxPerMinute) return false
        recent.addLast(now)
        return true
    }

    private fun String.withQuery(query: String) = if ('?' in this) "$this&$query" else "$this?$query"
}

/** Message texts and JSON building, kept free of I/O so they can be tested directly. */
internal object DiscordMessages {
    private const val MAX_NAME = 32

    fun live(hostName: String?, code: String, passwordProtected: Boolean, publicUrl: String?): String = buildString {
        append("🔴 **${displayName(hostName)}** is live on StreamingService!")
        append("\nRoom code: `$code`")
        if (passwordProtected) append(" (password protected)")
        if (publicUrl != null) append("\nWatch: $publicUrl/?room=$code")
    }

    fun ended(hostName: String?): String = "⚫ **${displayName(hostName)}** ended the stream."

    /** Plain text only: drops control characters and Markdown so a name cannot restyle the message. */
    fun displayName(raw: String?): String {
        val cleaned = raw.orEmpty()
            .filter { !it.isISOControl() && it !in "*_~`|>\\[]()@#" }
            .trim()
            .take(MAX_NAME)
        return cleaned.ifEmpty { "Someone" }
    }

    /** `allowed_mentions` with an empty `parse` list guarantees the message can never ping @everyone or roles. */
    fun payload(content: String): String = """{"content":${jsonString(content)},"allowed_mentions":{"parse":[]}}"""

    fun parseMessageId(responseBody: String): String? =
        Regex("\"id\"\\s*:\\s*\"(\\d+)\"").find(responseBody)?.groupValues?.get(1)

    fun jsonString(value: String): String = buildString {
        append('"')
        for (c in value) {
            when {
                c == '"' -> append("\\\"")
                c == '\\' -> append("\\\\")
                c == '\n' -> append("\\n")
                c == '\r' -> append("\\r")
                c == '\t' -> append("\\t")
                c < ' ' -> append("\\u%04x".format(c.code))
                else -> append(c)
            }
        }
        append('"')
    }
}
