package com.bishnu.stresswelfarebridge.ppg

import kotlin.math.sqrt

/**
 * Android-free view of one camera image plane, so frame decoding can be unit-tested on the JVM
 * without a device. [CameraPpgProcessor] adapts `ImageProxy.ImageProxyPlane` onto this.
 */
interface PixelPlane {
    val rowStride: Int
    val pixelStride: Int
    val capacity: Int

    /** Byte value 0–255 at [index], or -1 when that index lies outside the plane. */
    fun byteAt(index: Int): Int
}

/**
 * Which signal a frame's "green" value is actually taken from.
 *
 * This type exists because `pixelStride >= 3 ⇒ the byte at offset 1 is green` is an *assumption*,
 * and it is exactly the assumption that breaks silently when a device delivers a YUV_420_888
 * triplet: there plane 0 is luma, plane 1 is Cb and plane 2 is Cr, and none of them is an RGB
 * channel. The layout is therefore decided structurally (plane count first, then pixel stride) and
 * reported verbatim, so a real device can never be misread.
 */
enum class PpgChannel(val label: String) {
    /** Interleaved 4-byte pixel (RGBA_8888): byte offset 1 is green. */
    RGBA_GREEN("green byte of an RGBA_8888 pixel (offset 1 of 4 bytes)"),

    /** Interleaved 3-byte pixel (RGB_888): byte offset 1 is green. */
    RGB_GREEN("green byte of an RGB_888 pixel (offset 1 of 3 bytes)"),

    /** YUV_420_888: green reconstructed from luma + Cb + Cr with the BT.601 matrix. */
    YUV_GREEN("green reconstructed from YUV_420_888 (Y + Cb + Cr, BT.601)"),

    /** Single-byte plane with no chroma planes: luma used directly as the intensity signal. */
    LUMA("Y / luma plane used directly as the intensity signal"),

    /** Nothing recognisable — the frame is refused rather than misread. */
    UNKNOWN("unrecognised plane layout — frame refused")
}

/** One frame reduced to a single intensity scalar, plus the evidence of how it was obtained. */
data class FrameRead(
    val mean: Double,
    val min: Double,
    val max: Double,
    val std: Double,
    val count: Int,
    val channel: PpgChannel,
    val formatName: String,
    val width: Int,
    val height: Int,
    val planeCount: Int,
    val planeInfo: List<String>
) {
    /** Compact one-line description of the acquisition layout, for the device log. */
    fun layout(): String = "$formatName planes=$planeCount channel=${channel.name} $planeInfo"
}

/**
 * Turns one camera frame into a single PPG intensity scalar over a central region of interest.
 *
 * Decoding rules (all structural, none a guess about RGB):
 *  1. three planes ⇒ YUV_420_888. Plane 0 is luma, planes 1/2 are Cb/Cr at half resolution, and the
 *     green channel is *reconstructed* with the BT.601 matrix. Treating plane 0 as "green RGB" here
 *     would be wrong, so it never is.
 *  2. one plane with pixelStride ≥ 3 ⇒ interleaved RGB(A); byte offset 1 is green.
 *  3. one plane with pixelStride 1 ⇒ luma only, used as the intensity signal.
 *  4. anything else is refused ([PpgChannel.UNKNOWN]) instead of being decoded by guesswork.
 *
 * Every read is bounds-checked against the plane capacity, so a truncated or padded frame yields
 * fewer samples rather than an exception or a garbage value.
 */
object PpgFrameReader {

    /** `android.media.ImageFormat` values, duplicated so this file stays Android-free. */
    const val FORMAT_RGBA_8888 = 1
    const val FORMAT_RGB_565 = 4
    const val FORMAT_YUV_420_888 = 35
    const val FORMAT_YUV_422_888 = 20
    const val FORMAT_YUV_444_888 = 39
    const val FORMAT_JPEG = 256

    /** Central region used for the reading, as a fraction trimmed from each edge. */
    const val ROI_FRACTION = 0.20

    /** Target number of pixels sampled per frame (keeps per-frame cost constant). */
    const val TARGET_SAMPLES = 64

    fun formatName(format: Int): String = when (format) {
        FORMAT_RGBA_8888 -> "RGBA_8888"
        FORMAT_RGB_565 -> "RGB_565"
        FORMAT_YUV_420_888 -> "YUV_420_888"
        FORMAT_YUV_422_888 -> "YUV_422_888"
        FORMAT_YUV_444_888 -> "YUV_444_888"
        FORMAT_JPEG -> "JPEG"
        else -> "format#$format"
    }

    /** Decides the layout from the plane geometry, not from the requested format. */
    fun classify(planes: List<PixelPlane>): PpgChannel {
        if (planes.isEmpty()) return PpgChannel.UNKNOWN
        val stride = planes[0].pixelStride
        return when {
            planes.size >= 3 -> PpgChannel.YUV_GREEN
            stride >= 4 -> PpgChannel.RGBA_GREEN
            stride == 3 -> PpgChannel.RGB_GREEN
            stride == 1 -> PpgChannel.LUMA
            else -> PpgChannel.UNKNOWN
        }
    }

    /** Green (or luma) value of the pixel at (x, y), or null when any required byte is out of range. */
    private fun sample(planes: List<PixelPlane>, channel: PpgChannel, x: Int, y: Int): Double? {
        val p0 = planes[0]
        val index = y * p0.rowStride + x * p0.pixelStride
        val yValue = p0.byteAt(index)
        if (yValue < 0) return null
        return when (channel) {
            PpgChannel.RGBA_GREEN, PpgChannel.RGB_GREEN ->
                p0.byteAt(index + GREEN_OFFSET).takeIf { it >= 0 }?.toDouble()

            PpgChannel.LUMA -> yValue.toDouble()

            PpgChannel.YUV_GREEN -> {
                val cb = chromaByte(planes, 1, x, y)
                val cr = chromaByte(planes, 2, x, y)
                if (cb < 0 || cr < 0) null else yuvToGreen(yValue, cb, cr)
            }

            PpgChannel.UNKNOWN -> null
        }
    }

    /** Chroma is subsampled by two in both axes in YUV_420_888, hence the halving. */
    private fun chromaByte(planes: List<PixelPlane>, index: Int, x: Int, y: Int): Int {
        if (index >= planes.size) return -1
        val p = planes[index]
        return p.byteAt((y / 2) * p.rowStride + (x / 2) * p.pixelStride)
    }

    /**
     * BT.601 limited-range YUV → green, the matrix Android uses for YUV_420_888.
     *
     * A finger PPG is dominated by blood absorption in green, so decoding the real G channel
     * matters: luma alone mixes in 0.299R + 0.114B and dilutes the pulsatile component.
     */
    fun yuvToGreen(y: Int, cb: Int, cr: Int): Double {
        val luma = 1.164 * (y - 16.0)
        return (luma - 0.391 * (cb - 128.0) - 0.813 * (cr - 128.0)).coerceIn(0.0, 255.0)
    }


    /**
     * Reduces a frame to its ROI mean green intensity, returning the evidence needed to judge
     * whether the camera is delivering a usable pulsatile signal at all (level, spread, layout).
     */
    fun read(
        planes: List<PixelPlane>,
        format: Int,
        width: Int,
        height: Int,
        roiFraction: Double = ROI_FRACTION,
        targetSamples: Int = TARGET_SAMPLES
    ): FrameRead? {
        if (width <= 0 || height <= 0 || planes.isEmpty()) return null
        val channel = classify(planes)
        if (channel == PpgChannel.UNKNOWN) return null

        val x0 = (width * roiFraction).toInt()
        val x1 = (width * (1.0 - roiFraction)).toInt().coerceAtLeast(x0 + 1)
        val y0 = (height * roiFraction).toInt()
        val y1 = (height * (1.0 - roiFraction)).toInt().coerceAtLeast(y0 + 1)
        val step = (minOf(x1 - x0, y1 - y0) / targetSamples).coerceAtLeast(1)

        var sum = 0.0
        var sumSq = 0.0
        var minV = Double.MAX_VALUE
        var maxV = -Double.MAX_VALUE
        var count = 0
        var y = y0
        while (y < y1) {
            var x = x0
            while (x < x1) {
                val v = sample(planes, channel, x, y)
                if (v != null) {
                    sum += v
                    sumSq += v * v
                    if (v < minV) minV = v
                    if (v > maxV) maxV = v
                    count++
                }
                x += step
            }
            y += step
        }
        if (count == 0) return null

        val mean = sum / count
        // Spread of the ROI values in this single frame. A uniformly saturated frame has a level at
        // the ceiling and a near-zero spread — together the signature of "no pulsatile data here".
        val variance = (sumSq / count) - mean * mean

        return FrameRead(
            mean = mean,
            min = minV,
            max = maxV,
            std = if (variance > 0.0) sqrt(variance) else 0.0,
            count = count,
            channel = channel,
            formatName = formatName(format),
            width = width,
            height = height,
            planeCount = planes.size,
            planeInfo = planes.mapIndexed { i, p ->
                "p$i[rowStride=${p.rowStride},pixelStride=${p.pixelStride},cap=${p.capacity}]"
            }
        )
    }

    /** Byte offset of G inside an interleaved RGB(A) pixel. */
    private const val GREEN_OFFSET = 1
}

