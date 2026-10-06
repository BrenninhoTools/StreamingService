package com.brenninho.streamingservice.core

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

data class CaptureConfig(
    val fps: Int = 10,
    val maxWidth: Int = 1280,
    val jpegQuality: Float = 0.7f,
)

/** The user (or the OS) refused to let the app capture the screen. */
class CapturePermissionDenied : Exception("Screen capture permission was denied")

/** Platform-specific source of screen frames. */
interface ScreenCapturer {
    /** False on platforms where capturing is not implemented (or not allowed, e.g. headless). */
    val isSupported: Boolean

    /**
     * Cold flow: capturing starts on collection and stops when the collector is cancelled.
     * Completes normally when the user ends the capture from the OS/browser UI, and throws
     * [CapturePermissionDenied] if permission was refused.
     */
    fun frames(config: CaptureConfig): Flow<VideoFrame>
}

/** For platforms that can watch but not share yet (iOS). */
internal object UnsupportedScreenCapturer : ScreenCapturer {
    override val isSupported: Boolean = false
    override fun frames(config: CaptureConfig): Flow<VideoFrame> = emptyFlow()
}
