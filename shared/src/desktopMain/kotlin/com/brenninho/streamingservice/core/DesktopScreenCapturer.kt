package com.brenninho.streamingservice.core

import java.awt.GraphicsEnvironment
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.Robot
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

/** Captures the primary monitor with [Robot]. Works on Windows, macOS and Linux (X11). */
internal class DesktopScreenCapturer : ScreenCapturer {
    override val isSupported: Boolean = !GraphicsEnvironment.isHeadless()

    override fun frames(config: CaptureConfig): Flow<VideoFrame> = flow {
        val robot = Robot()
        val bounds: Rectangle = GraphicsEnvironment.getLocalGraphicsEnvironment()
            .defaultScreenDevice.defaultConfiguration.bounds
        val interval = 1000L / config.fps.coerceIn(1, 60)
        while (true) {
            val startedAt = System.currentTimeMillis()
            val image = robot.createScreenCapture(bounds).scaledToFit(config.maxWidth)
            emit(
                VideoFrame(
                    width = image.width,
                    height = image.height,
                    jpeg = image.toJpeg(config.jpegQuality),
                ),
            )
            delay((interval - (System.currentTimeMillis() - startedAt)).coerceAtLeast(1))
        }
    }.flowOn(Dispatchers.IO)
}

private fun BufferedImage.scaledToFit(maxWidth: Int): BufferedImage {
    if (width <= maxWidth) return this
    val scaledHeight = height * maxWidth / width
    val scaled = BufferedImage(maxWidth, scaledHeight, BufferedImage.TYPE_INT_RGB)
    val graphics = scaled.createGraphics()
    try {
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        graphics.drawImage(this, 0, 0, maxWidth, scaledHeight, null)
    } finally {
        graphics.dispose()
    }
    return scaled
}

private fun BufferedImage.toJpeg(quality: Float): ByteArray {
    val writer = ImageIO.getImageWritersByFormatName("jpeg").next()
    val params = writer.defaultWriteParam.apply {
        compressionMode = ImageWriteParam.MODE_EXPLICIT
        compressionQuality = quality.coerceIn(0.1f, 1f)
    }
    val out = ByteArrayOutputStream()
    try {
        ImageIO.createImageOutputStream(out).use { stream ->
            writer.output = stream
            writer.write(null, IIOImage(this, null, null), params)
        }
    } finally {
        writer.dispose()
    }
    return out.toByteArray()
}

