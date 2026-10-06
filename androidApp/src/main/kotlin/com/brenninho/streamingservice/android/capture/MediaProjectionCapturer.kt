package com.brenninho.streamingservice.android.capture

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.activity.result.ActivityResult
import com.brenninho.streamingservice.core.CaptureConfig
import com.brenninho.streamingservice.core.CapturePermissionDenied
import com.brenninho.streamingservice.core.ScreenCapturer
import com.brenninho.streamingservice.core.VideoFrame
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Captures the whole screen with MediaProjection.
 *
 * @param requestPermission shows Android's "start recording?" dialog (it needs an Activity) and returns its
 * result, or null if the dialog could not be shown.
 */
internal class MediaProjectionCapturer(
    private val context: Context,
    private val requestPermission: suspend () -> ActivityResult?,
) : ScreenCapturer {
    override val isSupported: Boolean = true

    override fun frames(config: CaptureConfig): Flow<VideoFrame> = callbackFlow {
        val result = requestPermission()
        val data = result?.data
        if (result == null || result.resultCode != Activity.RESULT_OK || data == null) throw CapturePermissionDenied()

        // Subscribe before the service starts so no early frame (or an immediate stop) is missed.
        launch(start = CoroutineStart.UNDISPATCHED) { CaptureHub.frames.collect { send(it) } }
        launch(start = CoroutineStart.UNDISPATCHED) {
            CaptureHub.ended.first()
            close()
        }

        val intent = Intent(context, ScreenCaptureService::class.java)
            .putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, result.resultCode)
            .putExtra(ScreenCaptureService.EXTRA_DATA, data)
            .putExtra(ScreenCaptureService.EXTRA_FPS, config.fps)
            .putExtra(ScreenCaptureService.EXTRA_MAX_WIDTH, config.maxWidth)
            .putExtra(ScreenCaptureService.EXTRA_QUALITY, config.jpegQuality)
        if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent) else context.startService(intent)

        awaitClose { context.stopService(Intent(context, ScreenCaptureService::class.java)) }
    }
}
