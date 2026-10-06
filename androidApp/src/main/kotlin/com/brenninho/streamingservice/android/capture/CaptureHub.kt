package com.brenninho.streamingservice.android.capture

import com.brenninho.streamingservice.core.VideoFrame
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow

/** Hand-off between [ScreenCaptureService] (produces frames) and [MediaProjectionCapturer] (consumes them). */
internal object CaptureHub {
    val frames = MutableSharedFlow<VideoFrame>(extraBufferCapacity = 2, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** Emitted when the capture ends on its own, e.g. the user taps "Stop" in the system UI. */
    val ended = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
}
