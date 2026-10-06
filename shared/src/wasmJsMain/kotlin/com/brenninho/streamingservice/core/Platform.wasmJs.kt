package com.brenninho.streamingservice.core

import com.brenninho.streamingservice.protocol.RoomCode
import kotlinx.browser.localStorage
import kotlinx.browser.window

actual val platformName: String = "Web"

actual fun createScreenCapturer(): ScreenCapturer = WebScreenCapturer()

// Storage can be unavailable (private mode, blocked cookies): fall back to "nothing saved" instead of crashing.
actual fun createKeyValueStore(): KeyValueStore = object : KeyValueStore {
    override fun getString(key: String): String? = try {
        localStorage.getItem(PREFIX + key)
    } catch (e: Throwable) {
        null
    }

    override fun putString(key: String, value: String?) {
        try {
            if (value == null) localStorage.removeItem(PREFIX + key) else localStorage.setItem(PREFIX + key, value)
        } catch (e: Throwable) {
            // ignore
        }
    }
}

private const val PREFIX = "streamingservice."

/** Inside a Discord Activity every request must go through Discord's proxy, under `/.proxy`. */
fun isDiscordActivity(): Boolean = window.location.search.contains("frame_id=")

actual fun defaultServerUrl(): String {
    val origin = window.location.origin
    return if (isDiscordActivity()) "$origin/.proxy" else origin
}

actual fun launchRoomCode(): RoomCode? =
    window.location.search.removePrefix("?").split('&')
        .firstOrNull { it.startsWith("room=") }
        ?.substringAfter('=')
        ?.let(RoomCode::parse)
