package com.brenninho.streamingservice.android.capture

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.SystemClock
import android.view.WindowManager
import com.brenninho.streamingservice.android.R
import com.brenninho.streamingservice.core.VideoFrame
import java.io.ByteArrayOutputStream
import kotlin.math.min

/**
 * Foreground service that owns the MediaProjection. Android requires one (type `mediaProjection`)
 * for as long as the screen is being captured, and shows its notification while sharing.
 *
 * Frames go to [CaptureHub]. The projection is released when the service is stopped.
 */
internal class ScreenCaptureService : Service() {
    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var thread: HandlerThread? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val data: Intent? = when {
            intent == null -> null
            Build.VERSION.SDK_INT >= 33 -> intent.getParcelableExtra(EXTRA_DATA, Intent::class.java)
            else -> @Suppress("DEPRECATION") intent.getParcelableExtra(EXTRA_DATA)
        }
        if (intent == null || data == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        // Must be in the foreground before the projection is created (Android 14+ enforces the order).
        enterForeground()
        stopCapture()
        startCapture(
            resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0),
            data = data,
            fps = intent.getIntExtra(EXTRA_FPS, 10).coerceIn(1, 60),
            maxWidth = intent.getIntExtra(EXTRA_MAX_WIDTH, 1280),
            quality = intent.getFloatExtra(EXTRA_QUALITY, 0.7f),
        )
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopCapture()
        super.onDestroy()
    }

    private fun enterForeground() {
        val manager = getSystemService(NotificationManager::class.java)
        val builder = if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, getString(R.string.capture_channel), NotificationManager.IMPORTANCE_LOW),
            )
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION") Notification.Builder(this)
        }
        val notification = builder
            .setContentTitle(getString(R.string.capture_notification_title))
            .setContentText(getString(R.string.capture_notification_text))
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun startCapture(resultCode: Int, data: Intent, fps: Int, maxWidth: Int, quality: Float) {
        val captureThread = HandlerThread("screen-capture").also { it.start() }
        thread = captureThread
        val handler = Handler(captureThread.looper)

        val manager = getSystemService(MediaProjectionManager::class.java)
        val newProjection = try {
            manager.getMediaProjection(resultCode, data)
        } catch (e: SecurityException) {
            null
        }
        if (newProjection == null) {
            CaptureHub.ended.tryEmit(Unit)
            stopSelf()
            return
        }
        projection = newProjection
        // Required since Android 14, and how we learn that the user stopped the capture from the system UI.
        newProjection.registerCallback(
            object : MediaProjection.Callback() {
                override fun onStop() {
                    CaptureHub.ended.tryEmit(Unit)
                    stopSelf()
                }
            },
            handler,
        )

        val (realWidth, realHeight, density) = displaySize()
        val scale = min(1f, maxWidth.toFloat() / realWidth)
        val width = (realWidth * scale).toInt().coerceAtLeast(2) and 1.inv() // even sizes encode better
        val height = (realHeight * scale).toInt().coerceAtLeast(2) and 1.inv()

        val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        imageReader = reader
        virtualDisplay = newProjection.createVirtualDisplay(
            "StreamingService",
            width,
            height,
            density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface,
            null,
            handler,
        )

        val minIntervalMs = 1000L / fps
        var lastFrameAt = 0L
        reader.setOnImageAvailableListener({ source ->
            val image = source.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                val now = SystemClock.elapsedRealtime()
                if (now - lastFrameAt < minIntervalMs) return@setOnImageAvailableListener
                lastFrameAt = now

                val plane = image.planes[0]
                val rowPadding = plane.rowStride - plane.pixelStride * width
                val padded = Bitmap.createBitmap(width + rowPadding / plane.pixelStride, height, Bitmap.Config.ARGB_8888)
                padded.copyPixelsFromBuffer(plane.buffer)
                val bitmap = if (rowPadding == 0) padded else Bitmap.createBitmap(padded, 0, 0, width, height).also { padded.recycle() }

                val out = ByteArrayOutputStream()
                bitmap.compress(Bitmap.CompressFormat.JPEG, (quality * 100).toInt().coerceIn(10, 100), out)
                bitmap.recycle()
                CaptureHub.frames.tryEmit(VideoFrame(width, height, out.toByteArray()))
            } finally {
                image.close()
            }
        }, handler)
    }

    private fun stopCapture() {
        imageReader?.setOnImageAvailableListener(null, null)
        virtualDisplay?.release()
        imageReader?.close()
        projection?.stop()
        thread?.quitSafely()
        virtualDisplay = null
        imageReader = null
        projection = null
        thread = null
    }

    /** Real screen size in pixels (including system bars) and its density. */
    private fun displaySize(): Triple<Int, Int, Int> {
        val density = resources.displayMetrics.densityDpi
        val windowManager = getSystemService(WindowManager::class.java)
        if (Build.VERSION.SDK_INT >= 30) {
            val bounds = windowManager.maximumWindowMetrics.bounds
            return Triple(bounds.width(), bounds.height(), density)
        }
        val metrics = android.util.DisplayMetrics()
        @Suppress("DEPRECATION") windowManager.defaultDisplay.getRealMetrics(metrics)
        return Triple(metrics.widthPixels, metrics.heightPixels, density)
    }

    companion object {
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_DATA = "data"
        const val EXTRA_FPS = "fps"
        const val EXTRA_MAX_WIDTH = "max_width"
        const val EXTRA_QUALITY = "quality"
        private const val CHANNEL_ID = "screen_capture"
        private const val NOTIFICATION_ID = 1
    }
}
