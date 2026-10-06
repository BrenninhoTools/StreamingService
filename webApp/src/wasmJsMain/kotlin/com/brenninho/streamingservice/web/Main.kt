@file:OptIn(ExperimentalComposeUiApi::class, ExperimentalWasmJsInterop::class)

package com.brenninho.streamingservice.web

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import com.brenninho.streamingservice.App
import com.brenninho.streamingservice.AppEnvironment
import com.brenninho.streamingservice.PresetRoom
import com.brenninho.streamingservice.core.createStreamingHttpClient
import com.brenninho.streamingservice.core.defaultServerUrl
import com.brenninho.streamingservice.core.isDiscordActivity
import com.brenninho.streamingservice.protocol.RoomCode
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlin.js.ExperimentalWasmJsInterop
import kotlin.js.JsAny
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.await
import kotlinx.coroutines.launch

fun main() {
    MainScope().launch {
        val environment = discordActivityEnvironment() ?: AppEnvironment()
        ComposeViewport(document.body!!) { App(environment) }
    }
}

/**
 * Inside a Discord Activity, joins everyone in the call to one room: the room code is derived from the
 * Activity's instance id, and the instance id itself is the room password. Only participants of the call
 * can learn it, so strangers cannot watch or hijack the room.
 *
 * Returns null outside Discord, or when the server has no Discord application configured, in which case the
 * app runs as a normal web page.
 */
private suspend fun discordActivityEnvironment(): AppEnvironment? {
    if (!isDiscordActivity()) return null
    return try {
        // Inside the Activity iframe every request must go through Discord's proxy.
        val client = createStreamingHttpClient()
        val config = try {
            client.get("${window.location.origin}/.proxy/api/config").bodyAsText()
        } finally {
            client.close()
        }
        val clientId = Regex("\"discordClientId\"\\s*:\\s*\"([^\"]+)\"").find(config)?.groupValues?.get(1) ?: return null

        val sdk = DiscordSDK(clientId)
        sdk.ready().await<JsAny?>()
        val instanceId = sdk.instanceId

        AppEnvironment(
            defaultServerUrl = defaultServerUrl(),
            presetRoom = PresetRoom(RoomCode.fromSeed(instanceId), password = "activity-$instanceId"),
        )
    } catch (e: Throwable) {
        null
    }
}
