@file:OptIn(ExperimentalEncodingApi::class, ExperimentalWasmJsInterop::class)

package com.brenninho.streamingservice.core

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.js.ExperimentalWasmJsInterop
import kotlin.js.JsBoolean
import kotlin.js.JsString
import kotlin.js.Promise
import kotlin.time.TimeSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.await
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

// The browser side of screen capture. State lives on globalThis so the Kotlin side stays stateless.

@JsFun("() => !!(navigator.mediaDevices && navigator.mediaDevices.getDisplayMedia)")
private external fun jsSupported(): Boolean

/** Opens the browser's screen picker. Rejects if the user cancels. */
@JsFun(
    """async () => {
    const g = globalThis.__streamingCapture = globalThis.__streamingCapture || {};
    if (g.stream) g.stream.getTracks().forEach(t => t.stop());
    const stream = await navigator.mediaDevices.getDisplayMedia({ video: { frameRate: 15 }, audio: false });
    const video = document.createElement('video');
    video.muted = true;
    video.srcObject = stream;
    await video.play();
    g.stream = stream;
    g.video = video;
    g.canvas = document.createElement('canvas');
    return true;
}""",
)
private external fun jsStart(): Promise<JsBoolean>

/**
 * Grabs the current picture as "width,height,base64Jpeg".
 * Returns "" once the user stopped sharing from the browser UI, and "wait" while no picture is ready yet.
 */
@JsFun(
    """async (maxWidth, quality) => {
    const g = globalThis.__streamingCapture;
    if (!g || !g.stream) return '';
    const track = g.stream.getVideoTracks()[0];
    if (!track || track.readyState !== 'live') return '';
    const vw = g.video.videoWidth, vh = g.video.videoHeight;
    if (!vw || !vh) return 'wait';
    const scale = Math.min(1, maxWidth / vw);
    const w = Math.max(1, Math.round(vw * scale)), h = Math.max(1, Math.round(vh * scale));
    g.canvas.width = w;
    g.canvas.height = h;
    g.canvas.getContext('2d').drawImage(g.video, 0, 0, w, h);
    const blob = await new Promise(resolve => g.canvas.toBlob(resolve, 'image/jpeg', quality));
    if (!blob) return 'wait';
    const dataUrl = await new Promise(resolve => {
        const reader = new FileReader();
        reader.onload = () => resolve(reader.result);
        reader.readAsDataURL(blob);
    });
    return w + ',' + h + ',' + dataUrl.substring(dataUrl.indexOf(',') + 1);
}""",
)
private external fun jsGrab(maxWidth: Int, quality: Double): Promise<JsString>

@JsFun("() => { const g = globalThis.__streamingCapture; if (g && g.stream) { g.stream.getTracks().forEach(t => t.stop()); g.stream = null; } }")
private external fun jsStop()

/** Shares a screen, window or tab through the browser's `getDisplayMedia`. Desktop browsers only. */
internal class WebScreenCapturer : ScreenCapturer {
    override val isSupported: Boolean = jsSupported()

    override fun frames(config: CaptureConfig): Flow<VideoFrame> = flow {
        try {
            jsStart().await<JsBoolean>()
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
            throw CapturePermissionDenied()
        }
        try {
            val interval = 1000L / config.fps.coerceIn(1, 60)
            while (true) {
                val startedAt = TimeSource.Monotonic.markNow()
                val result = jsGrab(config.maxWidth, config.jpegQuality.toDouble()).await<JsString>().toString()
                if (result.isEmpty()) return@flow // the user clicked "Stop sharing" in the browser
                if (result != "wait") parse(result)?.let { emit(it) }
                delay((interval - startedAt.elapsedNow().inWholeMilliseconds).coerceAtLeast(1))
            }
        } finally {
            jsStop()
        }
    }

    private fun parse(raw: String): VideoFrame? {
        val first = raw.indexOf(',')
        val second = raw.indexOf(',', first + 1)
        if (first < 0 || second < 0) return null
        val width = raw.substring(0, first).toIntOrNull() ?: return null
        val height = raw.substring(first + 1, second).toIntOrNull() ?: return null
        return VideoFrame(width, height, Base64.decode(raw.substring(second + 1)))
    }
}
