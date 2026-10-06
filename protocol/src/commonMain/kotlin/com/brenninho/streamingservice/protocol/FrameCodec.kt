package com.brenninho.streamingservice.protocol

/** A decoded video frame as it travels on the wire. */
class EncodedFrame(val width: Int, val height: Int, val jpeg: ByteArray)

/**
 * Binary layout of one video frame message:
 *
 * ```
 * byte 0      version (currently 1)
 * bytes 1-2   width,  unsigned big-endian
 * bytes 3-4   height, unsigned big-endian
 * bytes 5..   JPEG image
 * ```
 */
object FrameCodec {
    const val VERSION = 1
    const val HEADER_SIZE = 5

    /** Upper bound the server accepts for a single frame (header included). */
    const val MAX_FRAME_BYTES = 4 * 1024 * 1024

    fun encode(width: Int, height: Int, jpeg: ByteArray): ByteArray {
        require(width in 1..0xFFFF && height in 1..0xFFFF) { "Invalid frame size ${width}x$height" }
        val out = ByteArray(HEADER_SIZE + jpeg.size)
        out[0] = VERSION.toByte()
        out[1] = (width shr 8).toByte()
        out[2] = width.toByte()
        out[3] = (height shr 8).toByte()
        out[4] = height.toByte()
        jpeg.copyInto(out, HEADER_SIZE)
        return out
    }

    /** Returns null when [bytes] is not a valid frame message. */
    fun decode(bytes: ByteArray): EncodedFrame? {
        if (!isValid(bytes)) return null
        val width = ((bytes[1].toInt() and 0xFF) shl 8) or (bytes[2].toInt() and 0xFF)
        val height = ((bytes[3].toInt() and 0xFF) shl 8) or (bytes[4].toInt() and 0xFF)
        return EncodedFrame(width, height, bytes.copyOfRange(HEADER_SIZE, bytes.size))
    }

    /** Cheap sanity check used by the server before relaying: right version, sane size, JPEG magic bytes. */
    fun isValid(bytes: ByteArray): Boolean {
        if (bytes.size < HEADER_SIZE + 2 || bytes.size > MAX_FRAME_BYTES) return false
        if (bytes[0].toInt() != VERSION) return false
        val width = ((bytes[1].toInt() and 0xFF) shl 8) or (bytes[2].toInt() and 0xFF)
        val height = ((bytes[3].toInt() and 0xFF) shl 8) or (bytes[4].toInt() and 0xFF)
        if (width == 0 || height == 0) return false
        return bytes[HEADER_SIZE] == 0xFF.toByte() && bytes[HEADER_SIZE + 1] == 0xD8.toByte()
    }
}
