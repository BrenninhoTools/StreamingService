package com.brenninho.streamingservice.protocol

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RoomCodeTest {
    @Test
    fun generatedCodesAreValid() {
        repeat(200) {
            val code = RoomCode.generate()
            assertEquals(RoomCode.LENGTH, code.value.length)
            assertEquals(code, RoomCode.parse(code.value))
        }
    }

    @Test
    fun parseNormalizesAndRejectsBadInput() {
        assertEquals(RoomCode("ABC234"), RoomCode.parse("  abc234 "))
        assertNull(RoomCode.parse("ABC23")) // too short
        assertNull(RoomCode.parse("ABC2345")) // too long
        assertNull(RoomCode.parse("ABC10O")) // look-alike characters are not in the alphabet
        assertNull(RoomCode.parse(""))
    }

    @Test
    fun fromSeedIsDeterministicAndValid() {
        val a = RoomCode.fromSeed("instance-123")
        assertEquals(a, RoomCode.fromSeed("instance-123"))
        assertNotNull(RoomCode.parse(a.value))
        assertTrue(RoomCode.fromSeed("instance-124") != a)
    }
}

class ServerMessageTest {
    @Test
    fun encodeParseRoundTrip() {
        val messages = listOf(
            ServerMessage.Room(RoomCode("ABC234"), "tok3n"),
            ServerMessage.Viewers(7),
            ServerMessage.Joined,
            ServerMessage.Ended,
        ) + RejectReason.entries.map { ServerMessage.Error(it) }
        for (message in messages) assertEquals(message, ServerMessage.parse(message.encode()))
    }

    @Test
    fun malformedMessagesAreIgnored() {
        assertNull(ServerMessage.parse(""))
        assertNull(ServerMessage.parse("room:nope"))
        assertNull(ServerMessage.parse("room:ABC234")) // token is mandatory
        assertNull(ServerMessage.parse("room:ABC234:"))
        assertNull(ServerMessage.parse("viewers:-1"))
        assertNull(ServerMessage.parse("viewers:abc"))
        assertNull(ServerMessage.parse("error:unknown_reason"))
        assertNull(ServerMessage.parse("hello"))
    }
}

class FrameCodecTest {
    private val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1, 2, 3, 0xFF.toByte(), 0xD9.toByte())

    @Test
    fun roundTrip() {
        val bytes = FrameCodec.encode(1920, 1080, jpeg)
        val frame = assertNotNull(FrameCodec.decode(bytes))
        assertEquals(1920, frame.width)
        assertEquals(1080, frame.height)
        assertContentEquals(jpeg, frame.jpeg)
    }

    @Test
    fun rejectsInvalidFrames() {
        assertFalse(FrameCodec.isValid(ByteArray(0)))
        assertFalse(FrameCodec.isValid(byteArrayOf(1, 0, 1, 0, 1))) // header only
        val wrongVersion = FrameCodec.encode(10, 10, jpeg).also { it[0] = 9 }
        assertFalse(FrameCodec.isValid(wrongVersion))
        val notJpeg = FrameCodec.encode(10, 10, byteArrayOf(1, 2, 3, 4))
        assertFalse(FrameCodec.isValid(notJpeg))
        val zeroWidth = FrameCodec.encode(10, 10, jpeg).also { it[1] = 0; it[2] = 0 }
        assertFalse(FrameCodec.isValid(zeroWidth))
        assertNull(FrameCodec.decode(notJpeg))
    }
}

class ServerEndpointsTest {
    @Test
    fun parsesSchemesAndDefaults() {
        assertEquals("wss://stream.example.com/ws/watch/ABC234", watch("https://stream.example.com/"))
        assertEquals("wss://stream.example.com/ws/watch/ABC234", watch("stream.example.com"))
        assertEquals("ws://localhost:8080/ws/watch/ABC234", watch("localhost:8080"))
        assertEquals("ws://10.0.2.2:8080/ws/watch/ABC234", watch("http://10.0.2.2:8080"))
        assertEquals("wss://x.discordsays.com/.proxy/ws/watch/ABC234", watch("wss://x.discordsays.com/.proxy"))
    }

    @Test
    fun rejectsInvalidAddresses() {
        assertNull(ServerEndpoints.parse(""))
        assertNull(ServerEndpoints.parse("   "))
        assertNull(ServerEndpoints.parse("ftp://example.com"))
        assertNull(ServerEndpoints.parse("https://"))
        assertNull(ServerEndpoints.parse("exa mple.com"))
    }

    @Test
    fun hostUrlEncodesParameters() {
        val endpoints = assertNotNull(ServerEndpoints.parse("localhost:8080"))
        assertEquals("ws://localhost:8080/ws/host", endpoints.hostUrl(null, null, false, null))
        assertEquals(
            "ws://localhost:8080/ws/host?room=ABC234&token=t0k&password=p%40ss%20w%C3%B6rd&announce=1&name=Ana%20%26%20Bob",
            endpoints.hostUrl(RoomCode("ABC234"), "p@ss wörd", true, "Ana & Bob", token = "t0k"),
        )
    }

    @Test
    fun joinLinkPointsAtTheWebApp() {
        val endpoints = assertNotNull(ServerEndpoints.parse("wss://stream.example.com"))
        assertEquals("https://stream.example.com/?room=ABC234", endpoints.joinLink(RoomCode("ABC234")))
        assertEquals("https://stream.example.com/api/config", endpoints.configUrl())
    }

    private fun watch(input: String) =
        assertNotNull(ServerEndpoints.parse(input)).watchUrl(RoomCode("ABC234"), null)
}
