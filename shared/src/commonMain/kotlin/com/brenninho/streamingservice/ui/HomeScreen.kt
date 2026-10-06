package com.brenninho.streamingservice.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.brenninho.streamingservice.core.platformName
import com.brenninho.streamingservice.resources.Res
import com.brenninho.streamingservice.resources.activity_banner
import com.brenninho.streamingservice.resources.app_icon
import com.brenninho.streamingservice.resources.app_name
import com.brenninho.streamingservice.resources.app_tagline
import com.brenninho.streamingservice.resources.home_settings
import com.brenninho.streamingservice.resources.home_share
import com.brenninho.streamingservice.resources.home_watch
import com.brenninho.streamingservice.resources.running_on
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

@Composable
fun HomeScreen(activityMode: Boolean, onShare: () -> Unit, onWatch: () -> Unit, onSettings: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Image(painterResource(Res.drawable.app_icon), contentDescription = null, modifier = Modifier.size(112.dp))
        Spacer(Modifier.height(16.dp))
        Text(stringResource(Res.string.app_name), style = MaterialTheme.typography.displaySmall)
        Text(
            stringResource(Res.string.app_tagline),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (activityMode) {
            Spacer(Modifier.height(12.dp))
            Text(
                stringResource(Res.string.activity_banner),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                textAlign = TextAlign.Center,
            )
        }
        Spacer(Modifier.height(32.dp))
        Button(onClick = onShare, modifier = Modifier.widthIn(max = 360.dp).fillMaxWidth()) {
            Text(stringResource(Res.string.home_share))
        }
        Spacer(Modifier.height(12.dp))
        OutlinedButton(onClick = onWatch, modifier = Modifier.widthIn(max = 360.dp).fillMaxWidth()) {
            Text(stringResource(Res.string.home_watch))
        }
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = onSettings) { Text(stringResource(Res.string.home_settings)) }
        Spacer(Modifier.height(24.dp))
        Text(
            stringResource(Res.string.running_on, platformName),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
