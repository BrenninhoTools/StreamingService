package com.brenninho.streamingservice.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.brenninho.streamingservice.core.StreamError
import com.brenninho.streamingservice.resources.Res
import com.brenninho.streamingservice.resources.back
import com.brenninho.streamingservice.resources.error_bad_password
import com.brenninho.streamingservice.resources.error_capture_failed
import com.brenninho.streamingservice.resources.error_capture_unsupported
import com.brenninho.streamingservice.resources.error_connection_failed
import com.brenninho.streamingservice.resources.error_invalid_code
import com.brenninho.streamingservice.resources.error_invalid_server
import com.brenninho.streamingservice.resources.error_permission_denied
import com.brenninho.streamingservice.resources.error_room_full
import com.brenninho.streamingservice.resources.error_room_not_found
import com.brenninho.streamingservice.resources.error_room_taken
import com.brenninho.streamingservice.resources.error_server_busy
import org.jetbrains.compose.resources.stringResource

/** Common layout of the inner screens: back button, title and a width-limited content column. */
@Composable
internal fun ScreenFrame(title: String, onBack: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(modifier = Modifier.widthIn(max = 720.dp).fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) { Text(stringResource(Res.string.back)) }
            }
            Text(title, style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(16.dp))
            content()
        }
    }
}

/** 16:9 area showing the latest frame, or [placeholder] while there is none. */
@Composable
internal fun FramePreview(frame: ImageBitmap?, placeholder: String, description: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(16f / 9f)
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        if (frame != null) {
            Image(bitmap = frame, contentDescription = description, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
        } else {
            Text(
                placeholder,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(24.dp),
            )
        }
    }
}

@Composable
internal fun ErrorText(error: StreamError?) {
    if (error == null) return
    val message = when (error) {
        StreamError.CaptureUnsupported -> stringResource(Res.string.error_capture_unsupported)
        StreamError.CaptureFailed -> stringResource(Res.string.error_capture_failed)
        StreamError.PermissionDenied -> stringResource(Res.string.error_permission_denied)
        StreamError.InvalidServer -> stringResource(Res.string.error_invalid_server)
        StreamError.InvalidCode -> stringResource(Res.string.error_invalid_code)
        StreamError.ConnectionFailed -> stringResource(Res.string.error_connection_failed)
        StreamError.RoomNotFound -> stringResource(Res.string.error_room_not_found)
        StreamError.RoomTaken -> stringResource(Res.string.error_room_taken)
        StreamError.RoomFull -> stringResource(Res.string.error_room_full)
        StreamError.BadPassword -> stringResource(Res.string.error_bad_password)
        StreamError.ServerBusy -> stringResource(Res.string.error_server_busy)
    }
    Text(message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
}
