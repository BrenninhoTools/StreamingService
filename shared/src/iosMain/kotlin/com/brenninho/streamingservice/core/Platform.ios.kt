package com.brenninho.streamingservice.core

import com.brenninho.streamingservice.protocol.RoomCode
import platform.Foundation.NSUserDefaults
import platform.UIKit.UIDevice

actual val platformName: String = "${UIDevice.currentDevice.systemName} ${UIDevice.currentDevice.systemVersion}"

/**
 * iOS can watch streams. Sharing the whole device screen needs a ReplayKit broadcast extension
 * (a separate app target), which is not part of the base yet.
 */
actual fun createScreenCapturer(): ScreenCapturer = UnsupportedScreenCapturer

actual fun createKeyValueStore(): KeyValueStore = object : KeyValueStore {
    private val defaults = NSUserDefaults.standardUserDefaults
    override fun getString(key: String): String? = defaults.stringForKey(key)
    override fun putString(key: String, value: String?) {
        if (value == null) defaults.removeObjectForKey(key) else defaults.setObject(value, forKey = key)
    }
}

/** The iOS simulator shares the Mac's network, so localhost reaches a locally running server. */
actual fun defaultServerUrl(): String = "localhost:8080"

actual fun launchRoomCode(): RoomCode? = null
