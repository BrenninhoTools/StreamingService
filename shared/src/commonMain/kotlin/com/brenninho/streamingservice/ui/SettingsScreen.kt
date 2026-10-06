package com.brenninho.streamingservice.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.brenninho.streamingservice.core.AppSettings
import com.brenninho.streamingservice.core.QualityPreset
import com.brenninho.streamingservice.protocol.ServerEndpoints
import com.brenninho.streamingservice.resources.Res
import com.brenninho.streamingservice.resources.quality_high
import com.brenninho.streamingservice.resources.quality_low
import com.brenninho.streamingservice.resources.quality_medium
import com.brenninho.streamingservice.resources.settings_discord
import com.brenninho.streamingservice.resources.settings_discord_desc
import com.brenninho.streamingservice.resources.settings_name
import com.brenninho.streamingservice.resources.settings_quality
import com.brenninho.streamingservice.resources.settings_server
import com.brenninho.streamingservice.resources.settings_server_hint
import com.brenninho.streamingservice.resources.settings_server_invalid
import com.brenninho.streamingservice.resources.settings_server_locked
import com.brenninho.streamingservice.resources.settings_title
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

@Composable
fun SettingsScreen(settings: AppSettings, serverLocked: Boolean, onBack: () -> Unit) {
    val state by settings.state.collectAsState()
    val serverValid = ServerEndpoints.parse(state.serverUrl) != null

    ScreenFrame(title = stringResource(Res.string.settings_title), onBack = onBack) {
        OutlinedTextField(
            value = state.serverUrl,
            onValueChange = { value -> settings.update { it.copy(serverUrl = value) } },
            label = { Text(stringResource(Res.string.settings_server)) },
            supportingText = {
                Text(
                    when {
                        serverLocked -> stringResource(Res.string.settings_server_locked)
                        !serverValid -> stringResource(Res.string.settings_server_invalid)
                        else -> stringResource(Res.string.settings_server_hint)
                    },
                )
            },
            isError = !serverValid && !serverLocked,
            enabled = !serverLocked,
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = state.displayName,
            onValueChange = { value -> settings.update { it.copy(displayName = value.take(ServerEndpoints.MAX_NAME_LENGTH)) } },
            label = { Text(stringResource(Res.string.settings_name)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(16.dp))
        Text(stringResource(Res.string.settings_quality), style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
            QualityPreset.entries.forEach { preset ->
                FilterChip(
                    selected = state.quality == preset,
                    onClick = { settings.update { it.copy(quality = preset) } },
                    label = { Text(stringResource(preset.label())) },
                )
            }
        }
        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Switch(
                checked = state.announceOnDiscord,
                onCheckedChange = { checked -> settings.update { it.copy(announceOnDiscord = checked) } },
            )
            androidx.compose.foundation.layout.Column {
                Text(stringResource(Res.string.settings_discord), style = MaterialTheme.typography.titleSmall)
                Text(
                    stringResource(Res.string.settings_discord_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

internal fun QualityPreset.label(): StringResource = when (this) {
    QualityPreset.Low -> Res.string.quality_low
    QualityPreset.Medium -> Res.string.quality_medium
    QualityPreset.High -> Res.string.quality_high
}
