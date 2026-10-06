package com.brenninho.streamingservice.core

import android.app.Application
import android.content.Context
import android.os.Build
import com.brenninho.streamingservice.protocol.RoomCode

/** The host app sets this once, before the first screen is composed. */
object AndroidAppContext {
    lateinit var application: Application
}

actual val platformName: String = "Android ${Build.VERSION.RELEASE}"

/**
 * Sharing needs MediaProjection, which must be started from an Activity. The Android app passes its
 * own capturer through `AppEnvironment`; this default only exists so shared code stays platform-agnostic.
 */
actual fun createScreenCapturer(): ScreenCapturer = UnsupportedScreenCapturer

actual fun createKeyValueStore(): KeyValueStore = object : KeyValueStore {
    private val prefs = AndroidAppContext.application.getSharedPreferences("streamingservice", Context.MODE_PRIVATE)
    override fun getString(key: String): String? = prefs.getString(key, null)
    override fun putString(key: String, value: String?) {
        prefs.edit().apply { if (value == null) remove(key) else putString(key, value) }.apply()
    }
}

/** 10.0.2.2 is how the Android emulator reaches the development machine. */
actual fun defaultServerUrl(): String = "10.0.2.2:8080"

actual fun launchRoomCode(): RoomCode? = null
