package com.brenninho.streamingservice.core

import com.brenninho.streamingservice.protocol.RoomCode
import java.util.prefs.Preferences

actual val platformName: String = "Desktop (${System.getProperty("os.name")})"

actual fun createScreenCapturer(): ScreenCapturer = DesktopScreenCapturer()

actual fun createKeyValueStore(): KeyValueStore = object : KeyValueStore {
    private val prefs = Preferences.userRoot().node("com/brenninho/streamingservice")
    override fun getString(key: String): String? = prefs.get(key, null)
    override fun putString(key: String, value: String?) {
        if (value == null) prefs.remove(key) else prefs.put(key, value)
    }
}

actual fun defaultServerUrl(): String = "localhost:8080"

actual fun launchRoomCode(): RoomCode? = null
