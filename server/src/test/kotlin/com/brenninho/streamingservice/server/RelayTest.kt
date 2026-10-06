package com.brenninho.streamingservice.server

import com.brenninho.streamingservice.protocol.ClientMessages
import com.brenninho.streamingservice.protocol.FrameCodec
import com.brenninho.streamingservice.protocol.RejectReason
import com.brenninho.streamingservice.protocol.ServerMessage
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readBytes
import io.ktor.websocket.readText
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

class RelayTest {
    private val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 7, 7, 7, 0xFF.toByte(), 0xD9.toByte())
    private fun frame(marker: Byte = 7) = FrameCodec.encode(64, 48, jpeg.copyOf().also { it[2] = marker })

    private fun relayTest(config: ServerConfig = ServerConfig(hostGraceMillis = 1_500), block: suspend ApplicationTestBuilder.(HttpClient) -> Unit) =
        runBlocking {
            withTimeout(30.seconds) {
                testApplication {
                    application { streamingModule(config) }
                    val client = createClient { install(WebSockets) }
                    block(client)
                }
            }
        }

    /** Reads the next text message and parses it. */
    private suspend fun ReceiveChannel<Frame>.nextMessage(): ServerMessage {
        val frame = withTimeout(5.seconds) { receive() }
        assertIs<Frame.Text>(frame)
        return assertNotNull(ServerMessage.parse(frame.readText()))
    }

    private suspend fun ReceiveChannel<Frame>.nextBinary(): ByteArray {
        val frame = withTimeout(5.seconds) { receive() }
        assertIs<Frame.Binary>(frame)
        return frame.readBytes()
    }

    @Test
    fun healthEndpoint() = relayTest { client ->
        val response = client.get("/health")
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("ok", response.bodyAsText())
    }

    @Test
    fun configEndpointExposesDiscordClientId() = relayTest(ServerConfig(discordClientId = "12345")) { client ->
        assertEquals("""{"discordClientId":"12345"}""", client.get("/api/config").bodyAsText())
    }

    @Test
    fun configEndpointWithoutDiscord() = relayTest { client ->
        assertEquals("""{"discordClientId":null}""", client.get("/api/config").bodyAsText())
    }

    @Test
    fun hostFramesReachViewersAndViewerCountReachesHost() = relayTest { client ->
        val code = CompletableDeferred<String>()
        val viewerJoined = CompletableDeferred<Unit>()
        val hostDone = CompletableDeferred<Unit>()
        coroutineScope {
            launch {
                client.webSocket("/ws/host") {
                    val room = incoming.nextMessage()
                    assertIs<ServerMessage.Room>(room)
                    assertTrue(room.token.isNotEmpty())
                    assertEquals(0, (incoming.nextMessage() as ServerMessage.Viewers).count)
                    code.complete(room.code.value)
                    viewerJoined.await()
                    assertEquals(1, (incoming.nextMessage() as ServerMessage.Viewers).count)
                    send(Frame.Text("garbage that is not a frame"))
                    send(Frame.Binary(true, byteArrayOf(1, 2, 3))) // invalid, must not be relayed
                    send(Frame.Binary(true, frame(1)))
                    send(Frame.Binary(true, frame(2)))
                    hostDone.await()
                    send(Frame.Text(ClientMessages.BYE))
                }
            }
            client.webSocket("/ws/watch/${code.await()}") {
                assertEquals(ServerMessage.Joined, incoming.nextMessage())
                viewerJoined.complete(Unit)
                // First frame must be the valid one: the garbage was dropped by the server.
                assertContentEquals(frame(1), incoming.nextBinary())
                assertContentEquals(frame(2), incoming.nextBinary())
                hostDone.complete(Unit)
                assertEquals(ServerMessage.Ended, incoming.nextMessage())
            }
        }
    }

    @Test
    fun lateJoinerGetsLastFrame() = relayTest { client ->
        val code = CompletableDeferred<String>()
        val finished = CompletableDeferred<Unit>()
        coroutineScope {
            launch {
                client.webSocket("/ws/host") {
                    code.complete((incoming.nextMessage() as ServerMessage.Room).code.value)
                    send(Frame.Binary(true, frame(9)))
                    delay(300) // let the server store the frame before the viewer joins
                    code.let { }
                    finished.await()
                    send(Frame.Text(ClientMessages.BYE))
                }
            }
            val roomCode = code.await()
            delay(100)
            client.webSocket("/ws/watch/$roomCode") {
                assertEquals(ServerMessage.Joined, incoming.nextMessage())
                assertContentEquals(frame(9), incoming.nextBinary())
                finished.complete(Unit)
            }
        }
    }

    @Test
    fun unknownRoomIsRejected() = relayTest { client ->
        client.webSocket("/ws/watch/ABC234") {
            assertEquals(ServerMessage.Error(RejectReason.NotFound), incoming.nextMessage())
        }
        client.webSocket("/ws/watch/not-a-code") {
            assertEquals(ServerMessage.Error(RejectReason.NotFound), incoming.nextMessage())
        }
    }

    @Test
    fun passwordProtectsRoom() = relayTest { client ->
        val code = CompletableDeferred<String>()
        val done = CompletableDeferred<Unit>()
        coroutineScope {
            launch {
                client.webSocket("/ws/host?password=s3cret") {
                    code.complete((incoming.nextMessage() as ServerMessage.Room).code.value)
                    done.await()
                    send(Frame.Text(ClientMessages.BYE))
                }
            }
            val roomCode = code.await()
            client.webSocket("/ws/watch/$roomCode") {
                assertEquals(ServerMessage.Error(RejectReason.BadPassword), incoming.nextMessage())
            }
            client.webSocket("/ws/watch/$roomCode?password=wrong") {
                assertEquals(ServerMessage.Error(RejectReason.BadPassword), incoming.nextMessage())
            }
            client.webSocket("/ws/watch/$roomCode?password=s3cret") {
                assertEquals(ServerMessage.Joined, incoming.nextMessage())
            }
            done.complete(Unit)
        }
    }

    @Test
    fun requestedRoomCodeCannotBeHijacked() = relayTest { client ->
        val done = CompletableDeferred<Unit>()
        val opened = CompletableDeferred<Unit>()
        coroutineScope {
            launch {
                client.webSocket("/ws/host?room=ABC234") {
                    assertEquals("ABC234", (incoming.nextMessage() as ServerMessage.Room).code.value)
                    opened.complete(Unit)
                    done.await()
                    send(Frame.Text(ClientMessages.BYE))
                }
            }
            opened.await()
            client.webSocket("/ws/host?room=ABC234") {
                assertEquals(ServerMessage.Error(RejectReason.RoomTaken), incoming.nextMessage())
            }
            client.webSocket("/ws/host?room=bad") {
                assertEquals(ServerMessage.Error(RejectReason.BadRoom), incoming.nextMessage())
            }
            done.complete(Unit)
        }
    }

    @Test
    fun hostCanReconnectWithinGracePeriodAndViewersStay() = relayTest { client ->
        val code = CompletableDeferred<String>()
        val token = CompletableDeferred<String>()
        val hostDropped = CompletableDeferred<Unit>()
        val reclaimed = CompletableDeferred<Unit>()
        coroutineScope {
            launch {
                // First connection: drops without saying goodbye.
                client.webSocket("/ws/host") {
                    val room = incoming.nextMessage() as ServerMessage.Room
                    code.complete(room.code.value)
                    token.complete(room.token)
                    hostDropped.await()
                    close()
                }
                delay(200)
                client.webSocket("/ws/host?room=${code.await()}&token=${token.await()}") {
                    val room = incoming.nextMessage() as ServerMessage.Room
                    assertEquals(code.await(), room.code.value)
                    assertEquals(1, (incoming.nextMessage() as ServerMessage.Viewers).count)
                    reclaimed.complete(Unit)
                    send(Frame.Binary(true, frame(5)))
                    delay(500)
                    send(Frame.Text(ClientMessages.BYE))
                }
            }
            client.webSocket("/ws/watch/${code.await()}") {
                assertEquals(ServerMessage.Joined, incoming.nextMessage())
                hostDropped.complete(Unit)
                // The room survives the drop: the next thing the viewer sees is the new host's frame.
                assertContentEquals(frame(5), incoming.nextBinary())
                assertEquals(ServerMessage.Ended, incoming.nextMessage())
            }
            reclaimed.await()
        }
    }

    @Test
    fun roomEndsAfterGracePeriodWhenHostNeverReturns() = relayTest(ServerConfig(hostGraceMillis = 300)) { client ->
        val code = CompletableDeferred<String>()
        val hostDropped = CompletableDeferred<Unit>()
        coroutineScope {
            launch {
                client.webSocket("/ws/host") {
                    code.complete((incoming.nextMessage() as ServerMessage.Room).code.value)
                    hostDropped.await()
                    close()
                }
            }
            client.webSocket("/ws/watch/${code.await()}") {
                assertEquals(ServerMessage.Joined, incoming.nextMessage())
                hostDropped.complete(Unit)
                assertEquals(ServerMessage.Ended, incoming.nextMessage())
            }
        }
    }

    @Test
    fun wasmFilesAreServedWithTheCorrectContentType() = runBlocking {
        val dir = Files.createTempDirectory("webapp").toFile()
        try {
            dir.resolve("index.html").writeText("<html>hello</html>")
            dir.resolve("app.wasm").writeBytes(byteArrayOf(0, 0x61, 0x73, 0x6D))
            testApplication {
                application { streamingModule(ServerConfig(staticDir = dir.absolutePath)) }
                assertEquals("<html>hello</html>", client.get("/").bodyAsText())
                val wasm = client.get("/app.wasm")
                assertEquals("application/wasm", wasm.contentType()?.withoutParameters()?.toString())
                // Unknown paths fall back to the single-page app.
                assertEquals("<html>hello</html>", client.get("/some/route").bodyAsText())
            }
        } finally {
            dir.deleteRecursively()
        }
    }
}

class RoomRegistryTest {
    private fun registry(maxRooms: Int = 10, maxViewers: Int = 2, scope: kotlinx.coroutines.CoroutineScope) =
        RoomRegistry(maxRooms, maxViewers, scope, graceMillis = 0)

    @Test
    fun enforcesRoomAndViewerLimits() = runBlocking {
        val registry = registry(maxRooms = 1, scope = this)
        val opened = registry.open(null, null, null)
        assertIs<OpenResult.Opened>(opened)
        assertEquals(OpenResult.Full, registry.open(null, null, null))

        val room = opened.session.room
        assertNotNull(room.addViewer())
        assertNotNull(room.addViewer())
        assertNull(room.addViewer()) // maxViewers = 2
    }

    @Test
    fun closingARoomEndsItsViewersAndNotifies() = runBlocking {
        val registry = registry(scope = this)
        var closed: Room? = null
        registry.onRoomClosed = { closed = it }
        val room = (registry.open(null, null, null) as OpenResult.Opened).session.room
        val viewer = assertNotNull(room.addViewer())

        registry.close(room)

        assertTrue(viewer.frames.isClosedForReceive || viewer.frames.tryReceive().isClosed)
        assertEquals(room, closed)
        assertNull(registry.find(room.code))
        assertNull(room.addViewer())
    }

    @Test
    fun slowViewersDropOldFramesInsteadOfBlockingTheHost() = runBlocking {
        val registry = registry(scope = this)
        val room = (registry.open(null, null, null) as OpenResult.Opened).session.room
        val viewer = assertNotNull(room.addViewer())
        repeat(50) { room.publish(byteArrayOf(it.toByte())) }
        // Capacity is 2 with DROP_OLDEST: only the newest frames survive.
        assertEquals(48, viewer.frames.receive()[0].toInt())
        assertEquals(49, viewer.frames.receive()[0].toInt())
    }

    @Test
    fun tokenIsRequiredToReclaim() = runBlocking {
        val registry = registry(scope = this)
        val first = (registry.open(null, null, null) as OpenResult.Opened).session.room
        assertEquals(OpenResult.Taken, registry.open(first.code, null, "wrong"))
        assertEquals(OpenResult.Taken, registry.open(first.code, null, null))
        val again = registry.open(first.code, null, first.token)
        assertIs<OpenResult.Opened>(again)
        assertTrue(again.reclaimed)
    }
}

class DiscordAnnouncerTest {
    @Test
    fun liveMessageContainsCodeAndLink() {
        val text = DiscordMessages.live("Ana", "ABC234", false, "https://stream.example.com")
        assertTrue("**Ana** is live" in text)
        assertTrue("`ABC234`" in text)
        assertTrue("https://stream.example.com/?room=ABC234" in text)
    }

    @Test
    fun namesCannotInjectMarkdownOrMentions() {
        assertEquals("Someone", DiscordMessages.displayName(null))
        assertEquals("Someone", DiscordMessages.displayName("  **@@**  "))
        assertEquals("everyone", DiscordMessages.displayName("@everyone"))
        assertEquals(32, DiscordMessages.displayName("x".repeat(100)).length)
    }

    @Test
    fun payloadNeverAllowsMentionsAndEscapesJson() {
        val json = DiscordMessages.payload("a \"quoted\"\nline \\ back")
        assertEquals("""{"content":"a \"quoted\"\nline \\ back","allowed_mentions":{"parse":[]}}""", json)
    }

    @Test
    fun parsesMessageId() {
        assertEquals("1234567890", DiscordMessages.parseMessageId("""{"id": "1234567890","type":0}"""))
        assertNull(DiscordMessages.parseMessageId("{}"))
    }
}
