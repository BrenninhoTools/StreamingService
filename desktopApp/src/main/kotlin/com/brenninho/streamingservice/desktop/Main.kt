package com.brenninho.streamingservice.desktop

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.brenninho.streamingservice.App
import com.brenninho.streamingservice.resources.Res
import com.brenninho.streamingservice.resources.app_icon
import com.brenninho.streamingservice.resources.app_name
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = stringResource(Res.string.app_name),
        icon = painterResource(Res.drawable.app_icon),
        state = rememberWindowState(size = DpSize(1000.dp, 760.dp)),
    ) {
        App()
    }
}
