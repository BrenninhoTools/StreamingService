package com.brenninho.streamingservice.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.brenninho.streamingservice.PresetRoom
import com.brenninho.streamingservice.core.StreamError
import com.brenninho.streamingservice.core.StreamTransport
import com.brenninho.streamingservice.core.ViewerController
import com.brenninho.streamingservice.core.ViewerPhase
import com.brenninho.streamingservice.core.ViewerState
import com.brenninho.streamingservice.protocol.RoomCode
import com.brenninho.streamingservice.resources.Res
import com.brenninho.streamingservice.resources.host_password
import com.brenninho.streamingservice.resources.preview_description
import com.brenninho.streamingservice.resources.viewer_connecting
import com.brenninho.streamingservice.resources.viewer_enter_code
import com.brenninho.streamingservice.resources.viewer_ended
import com.brenninho.streamingservice.resources.viewer_idle
import com.brenninho.streamingservice.resources.viewer_join
import com.brenninho.streamingservice.resources.viewer_leave
import com.brenninho.streamingservice.resources.viewer_title
import com.brenninho.streamingservice.resources.viewer_waiting
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import org.jetbrains.compose.resources.stringResource

@Composable
fun ViewerScreen(
    transport: StreamTransport?,
    presetRoom: PresetRoom?,
    initialRoom: RoomCode?,
    scope: CoroutineScope,
    onBack: () -> Unit,
) {
    val controller = remember(transport) { transport?.let { ViewerController(it, scope) } }
    DisposableEffect(controller) { onDispose { controller?.leave() } }
    val idle = remember { MutableStateFlow(ViewerState()) }
    val state by (controller?.state ?: idle).collectAsState()
    var code by remember { mutableStateOf(presetRoom?.code?.value ?: initialRoom?.value.orEmpty()) }
    var password by remember { mutableStateOf("") }

    // In a Discord Activity there is nothing to type: join the call's room and wait for someone to share.
    LaunchedEffect(controller, presetRoom) {
        if (presetRoom != null) controller?.join(presetRoom.code.value, presetRoom.password, waitForHost = true)
    }

    ScreenFrame(title = stringResource(Res.string.viewer_title), onBack = onBack) {
        FramePreview(
            frame = state.frame,
            placeholder = stringResource(placeholderFor(state)),
            description = stringResource(Res.string.preview_description),
        )
        Spacer(Modifier.height(16.dp))
        if (presetRoom == null) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = code,
                    onValueChange = { code = it.uppercase().take(RoomCode.LENGTH) },
                    label = { Text(stringResource(Res.string.viewer_enter_code)) },
                    singleLine = true,
                    enabled = !state.isActive,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
                    modifier = Modifier.weight(1f),
                )
                if (state.isActive) {
                    OutlinedButton(onClick = { controller?.leave() }) { Text(stringResource(Res.string.viewer_leave)) }
                } else {
                    Button(enabled = controller != null, onClick = { controller?.join(code, password) }) {
                        Text(stringResource(Res.string.viewer_join))
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text(stringResource(Res.string.host_password)) },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
                enabled = !state.isActive,
                modifier = Modifier.fillMaxWidth(),
            )
        } else if (state.isActive) {
            OutlinedButton(onClick = { controller?.leave() }) { Text(stringResource(Res.string.viewer_leave)) }
        }
        Spacer(Modifier.height(8.dp))
        ErrorText(state.error ?: if (controller == null) StreamError.InvalidServer else null)
    }
}

private fun placeholderFor(state: ViewerState) = when (state.phase) {
    ViewerPhase.Connecting -> Res.string.viewer_connecting
    ViewerPhase.HostEnded -> Res.string.viewer_ended
    ViewerPhase.Idle -> Res.string.viewer_idle
    ViewerPhase.WaitingForHost, ViewerPhase.Watching -> Res.string.viewer_waiting
}
