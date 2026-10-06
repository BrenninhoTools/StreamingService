package com.brenninho.streamingservice.server

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

class DiscordWebhookTest {
    private class Recorded(val method: HttpMethod, val url: String, val body: String)

    private fun newRoom(registry: RoomRegistry) =
        (registry.open(null, null, null) as OpenResult.Opened).session.room

    @Test
    fun announcesThenEditsTheMessageWhenTheStreamEnds() = runBlocking {
        val calls = CopyOnWriteArrayList<Recorded>()
        val secondCall = CompletableDeferred<Unit>()
        val engine = MockEngine { request ->
            calls += Recorded(request.method, request.url.toString(), String(request.body.toByteArray()))
            if (calls.size == 2) secondCall.complete(Unit)
            respond(
                content = """{"id":"987654321"}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val announcer = DiscordAnnouncer(
            webhookUrl = "https://discord.com/api/webhooks/1/abc",
            publicUrl = "https://stream.example.com",
            client = HttpClient(engine),
            scope = this,
        )
        val registry = RoomRegistry(5, 5, this, graceMillis = 0)
        registry.onRoomClosed = announcer::closed

        val room = newRoom(registry)
        announcer.opened(room, "Ana")
        withTimeout(5_000) { while (room.announcementId == null) kotlinx.coroutines.delay(20) }
        assertEquals("987654321", room.announcementId)

        registry.close(room)
        withTimeout(5_000) { secondCall.await() }

        val post = calls[0]
        assertEquals(HttpMethod.Post, post.method)
        assertEquals("https://discord.com/api/webhooks/1/abc?wait=true", post.url)
        assertTrue("**Ana** is live" in post.body)
        assertTrue(room.code.value in post.body)
        assertTrue("\"allowed_mentions\":{\"parse\":[]}" in post.body)

        val patch = calls[1]
        assertEquals(HttpMethod.Patch, patch.method)
        assertEquals("https://discord.com/api/webhooks/1/abc/messages/987654321", patch.url)
        assertTrue("ended the stream" in patch.body)
    }

    @Test
    fun rateLimitsAnnouncements() = runBlocking {
        val calls = CopyOnWriteArrayList<Recorded>()
        val engine = MockEngine { request ->
            calls += Recorded(request.method, request.url.toString(), "")
            respond("""{"id":"1"}""", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        var now = 0L
        val announcer = DiscordAnnouncer("https://discord.com/api/webhooks/1/abc", null, HttpClient(engine), this, maxPerMinute = 2, clock = { now })
        val registry = RoomRegistry(10, 5, this, graceMillis = 0)

        repeat(3) { announcer.opened(newRoom(registry), "x") }
        withTimeout(5_000) { while (calls.size < 2) kotlinx.coroutines.delay(20) }
        kotlinx.coroutines.delay(200)
        assertEquals(2, calls.size) // the third was skipped

        now = 61_000 // a minute later the budget is back
        announcer.opened(newRoom(registry), "x")
        withTimeout(5_000) { while (calls.size < 3) kotlinx.coroutines.delay(20) }
        assertEquals(3, calls.size)
    }

    @Test
    fun failedWebhookDoesNotLeakAnId() = runBlocking {
        val engine = MockEngine { respond("nope", HttpStatusCode.BadRequest) }
        val announcer = DiscordAnnouncer("https://discord.com/api/webhooks/1/abc", null, HttpClient(engine), this)
        val room = newRoom(RoomRegistry(5, 5, this, graceMillis = 0))
        announcer.opened(room, "Ana")
        kotlinx.coroutines.delay(300)
        assertNull(room.announcementId)
    }
}
