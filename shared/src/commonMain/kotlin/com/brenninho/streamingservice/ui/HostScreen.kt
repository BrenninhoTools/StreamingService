package com.brenninho.streamingservice.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.brenninho.streamingservice.PresetRoom
import com.brenninho.streamingservice.core.AppSettings
import com.brenninho.streamingservice.core.HostController
import com.brenninho.streamingservice.core.HostOptions
import com.brenninho.streamingservice.core.HostPhase
import com.brenninho.streamingservice.core.HostState
import com.brenninho.streamingservice.core.QualityPreset
import com.brenninho.streamingservice.core.ScreenCapturer
import com.brenninho.streamingservice.core.StreamError
import com.brenninho.streamingservice.core.StreamTransport
import com.brenninho.streamingservice.protocol.ServerEndpoints
import com.brenninho.streamingservice.resources.Res
import com.brenninho.streamingservice.resources.host_bitrate
import com.brenninho.streamingservice.resources.host_connecting
import com.brenninho.streamingservice.resources.host_hint
import com.brenninho.streamingservice.resources.host_link
import com.brenninho.streamingservice.resources.host_live
import com.brenninho.streamingservice.resources.host_offline
import com.brenninho.streamingservice.resources.host_password
import com.brenninho.streamingservice.resources.host_preview_empty
import com.brenninho.streamingservice.resources.host_reconnecting
import com.brenninho.streamingservice.resources.host_room_code
import com.brenninho.streamingservice.resources.host_start
import com.brenninho.streamingservice.resources.host_stop
import com.brenninho.streamingservice.resources.host_title
import com.brenninho.streamingservice.resources.host_viewers
import com.brenninho.streamingservice.resources.preview_description
import com.brenninho.streamingservice.resources.settings_quality
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import org.jetbrains.compose.resources.stringResource

@Composable
fun HostScreen(
    capturer: ScreenCapturer,
    transport: StreamTransport?,
    endpoints: ServerEndpoints?,
    settings: AppSettings,
    presetRoom: PresetRoom?,
    scope: CoroutineScope,
    onBack: () -> Unit,
) {
    val controller = remember(transport) { transport?.let { HostController(capturer, it, scope) } }
    DisposableEffect(controller) { onDispose { controller?.stop() } }
    val idle = remember { MutableStateFlow(HostState()) }
    val state by (controller?.state ?: idle).collectAsState()
    val settingsState by settings.state.collectAsState()
    var password by remember { mutableStateOf("") }

    ScreenFrame(title = stringResource(Res.string.host_title), onBack = onBack) {
        FramePreview(
            frame = state.preview,
            placeholder = stringResource(Res.string.host_preview_empty),
            description = stringResource(Res.string.preview_description),
        )
        Spacer(Modifier.height(16.dp))

        if (state.isActive) {
            LiveDetails(state, endpoints)
        } else {
            Text(stringResource(Res.string.settings_quality), style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 8.dp)) {
                QualityPreset.entries.forEach { preset ->
                    FilterChip(
                        selected = settingsState.quality == preset,
                        onClick = { settings.update { it.copy(quality = preset) } },
                        label = { Text(stringResource(preset.label())) },
                    )
                }
            }
            if (presetRoom == null) {
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it.take(MAX_PASSWORD) },
                    label = { Text(stringResource(Res.string.host_password)) },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        ErrorText(state.error ?: if (controller == null) StreamError.InvalidServer else null)
        Spacer(Modifier.height(16.dp))
        if (state.isActive) {
            Button(
                onClick = { controller?.stop() },
                colors = ButtonDefaults.buttonColors(containerColor = LiveRed),
            ) { Text(stringResource(Res.string.host_stop)) }
        } else {
            Button(
                enabled = controller != null,
                onClick = {
                    val options = HostOptions(
                        room = presetRoom?.code,
                        password = presetRoom?.password ?: password.ifEmpty { null },
                        announce = settingsState.announceOnDiscord,
                        name = settingsState.displayName.ifBlank { null },
                    )
                    controller?.start(options, settingsState.quality.config)
                },
            ) { Text(stringResource(Res.string.host_start)) }
        }
    }
}

@Composable
private fun LiveDetails(state: HostState, endpoints: ServerEndpoints?) {
    val room = state.room
    if (room != null) {
        Text(stringResource(Res.string.host_room_code), style = MaterialTheme.typography.labelLarge)
        SelectionContainer {
            Text(
                room.value,
                style = MaterialTheme.typography.displayMedium,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Text(
            stringResource(Res.string.host_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        endpoints?.let { link ->
            Spacer(Modifier.height(8.dp))
            Text(stringResource(Res.string.host_link), style = MaterialTheme.typography.labelLarge)
            SelectionContainer { Text(link.joinLink(room), style = MaterialTheme.typography.bodyMedium) }
        }
        Spacer(Modifier.height(16.dp))
    }
    when (state.phase) {
        HostPhase.Live -> Text(stringResource(Res.string.host_live), style = MaterialTheme.typography.titleMedium, color = LiveRed)
        HostPhase.Connecting -> Text(stringResource(Res.string.host_connecting), style = MaterialTheme.typography.titleMedium)
        HostPhase.Reconnecting -> Text(stringResource(Res.string.host_reconnecting), style = MaterialTheme.typography.titleMedium)
        HostPhase.Idle -> Text(stringResource(Res.string.host_offline), style = MaterialTheme.typography.titleMedium)
    }
    Text(stringResource(Res.string.host_viewers, state.viewers), style = MaterialTheme.typography.bodyMedium)
    if (state.kbps > 0) Text(stringResource(Res.string.host_bitrate, state.kbps), style = MaterialTheme.typography.bodyMedium)
}

private const val MAX_PASSWORD = 64
