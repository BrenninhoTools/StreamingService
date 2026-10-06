package com.brenninho.streamingservice.core

import androidx.compose.ui.graphics.ImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.decodeToImageBitmap

/** One captured screen frame, JPEG-encoded so it is cheap to move across a transport. */
class VideoFrame(
    val width: Int,
    val height: Int,
    val jpeg: ByteArray,
)

/** Turns a received frame into something drawable. Replaceable so tests do not need a real JPEG decoder. */
typealias FrameDecoder = suspend (VideoFrame) -> ImageBitmap?

internal val DefaultFrameDecoder: FrameDecoder = { frame ->
    withContext(Dispatchers.Default) { runCatching { frame.jpeg.decodeToImageBitmap() }.getOrNull() }
}
