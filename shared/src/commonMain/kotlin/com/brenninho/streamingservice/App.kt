package com.brenninho.streamingservice

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.brenninho.streamingservice.core.AppSettings
import com.brenninho.streamingservice.core.KeyValueStore
import com.brenninho.streamingservice.core.ScreenCapturer
import com.brenninho.streamingservice.core.WebSocketStreamTransport
import com.brenninho.streamingservice.core.createKeyValueStore
import com.brenninho.streamingservice.core.createScreenCapturer
import com.brenninho.streamingservice.core.createStreamingHttpClient
import com.brenninho.streamingservice.core.defaultServerUrl
import com.brenninho.streamingservice.core.launchRoomCode
import com.brenninho.streamingservice.protocol.RoomCode
import com.brenninho.streamingservice.protocol.ServerEndpoints
import com.brenninho.streamingservice.ui.HomeScreen
import com.brenninho.streamingservice.ui.HostScreen
import com.brenninho.streamingservice.ui.SettingsScreen
import com.brenninho.streamingservice.ui.StreamingTheme
import com.brenninho.streamingservice.ui.ViewerScreen

/**
 * A room that is decided by the environment rather than by the user: everyone in a Discord Activity
 * lands in the same room, protected by a secret only the call's participants know.
 */
class PresetRoom(val code: RoomCode, val password: String)

/** What the host platform provides to the shared app. Every field has a sensible platform default. */
class AppEnvironment(
    /** Android passes a MediaProjection-based capturer here, since it needs an Activity. */
    val capturer: ScreenCapturer = createScreenCapturer(),
    val store: KeyValueStore = createKeyValueStore(),
    val defaultServerUrl: String = defaultServerUrl(),
    /** Room from a share link (`?room=CODE`): opens the viewer with the code filled in. */
    val initialRoom: RoomCode? = launchRoomCode(),
    val presetRoom: PresetRoom? = null,
)

private enum class Screen { Home, Host, Viewer, Settings }

/** Root of the app, shared by every platform. */
@Composable
fun App(environment: AppEnvironment = remember { AppEnvironment() }) {
    StreamingTheme {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            val settings = remember(environment) { AppSettings(environment.store, environment.defaultServerUrl) }
            val settingsState by settings.state.collectAsState()

            // Controllers outlive their screens: leaving the host screen must still finish the goodbye to the server.
            val appScope = rememberCoroutineScope()
            val httpClient = remember { createStreamingHttpClient() }
            DisposableEffect(httpClient) { onDispose { httpClient.close() } }

            // A preset room (Discord) pins the server too: requests must go through Discord's proxy.
            val serverUrl = if (environment.presetRoom != null) environment.defaultServerUrl else settingsState.serverUrl
            val endpoints = remember(serverUrl) { ServerEndpoints.parse(serverUrl) }
            val transport = remember(endpoints, httpClient) { endpoints?.let { WebSocketStreamTransport(it, httpClient) } }

            var screen by remember { mutableStateOf(if (environment.initialRoom != null) Screen.Viewer else Screen.Home) }
            val goHome = { screen = Screen.Home }
            when (screen) {
                Screen.Home -> HomeScreen(
                    activityMode = environment.presetRoom != null,
                    onShare = { screen = Screen.Host },
                    onWatch = { screen = Screen.Viewer },
                    onSettings = { screen = Screen.Settings },
                )
                Screen.Host -> HostScreen(environment.capturer, transport, endpoints, settings, environment.presetRoom, appScope, goHome)
                Screen.Viewer -> ViewerScreen(transport, environment.presetRoom, environment.initialRoom, appScope, goHome)
                Screen.Settings -> SettingsScreen(settings, serverLocked = environment.presetRoom != null, onBack = goHome)
            }
        }
    }
}
