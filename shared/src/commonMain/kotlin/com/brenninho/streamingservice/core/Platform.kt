package com.brenninho.streamingservice.core

import com.brenninho.streamingservice.protocol.RoomCode

/** Human-readable name of the platform the app is running on. */
expect val platformName: String

/** The platform's default screen capturer (unsupported where capturing is not implemented). */
expect fun createScreenCapturer(): ScreenCapturer

/** Persistent storage for [AppSettings]: SharedPreferences, java.util.prefs, NSUserDefaults or localStorage. */
expect fun createKeyValueStore(): KeyValueStore

/** Server address to suggest on first launch. The web app uses the origin it was served from. */
expect fun defaultServerUrl(): String

/** Room the app was opened with (a `?room=CODE` link on the web), or null. */
expect fun launchRoomCode(): RoomCode?
