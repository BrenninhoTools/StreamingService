@file:JsModule("@discord/embedded-app-sdk")
@file:OptIn(ExperimentalWasmJsInterop::class)

package com.brenninho.streamingservice.web

import kotlin.js.ExperimentalWasmJsInterop
import kotlin.js.JsAny
import kotlin.js.Promise

/** The slice of Discord's Embedded App SDK this app uses: https://discord.com/developers/docs/developer-tools/embedded-app-sdk */
external class DiscordSDK(clientId: String) : JsAny {
    /** Identifies this running Activity. Every participant of the same Activity session sees the same value. */
    val instanceId: String

    /** Resolves once the handshake with the Discord client is done. */
    fun ready(): Promise<JsAny?>
}
