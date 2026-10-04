package com.bishnu.stresswelfarebridge.camera

import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.bishnu.stresswelfarebridge.ppg.FrameRead
import com.bishnu.stresswelfarebridge.ppg.PixelPlane
import com.bishnu.stresswelfarebridge.ppg.PpgBand
import com.bishnu.stresswelfarebridge.ppg.PpgRegion
import com.bishnu.stresswelfarebridge.ppg.PpgSample
import com.bishnu.stresswelfarebridge.ppg.RegionRead
import com.bishnu.stresswelfarebridge.ppg.readRegions

/**
 * CameraX [ImageAnalysis.Analyzer] that decodes each frame into a handful of candidate PPG scalars
 * and immediately closes it — the frame is never stored, written to disk or transmitted, so the
 * working set stays constant.
 *
 * **Channel/region decoding is structural, never assumed.** The original implementation read
 * `image.planes[0]` and picked byte offset 1 whenever `pixelStride >= 3`, i.e. it *assumed* the frame
 * was interleaved RGB. On a device that delivers YUV_420_888 that assumption is false — the planes
 * are luma, Cb and Cr and none of them is an RGB channel — so the value produced was not a green
 * measurement at all. Decoding now lives in [readRegions], which decides from the actual plane
 * geometry: three planes means YUV_420_888 with each band reconstructed via the BT.601 matrix, one
 * plane with pixelStride at least 3 means interleaved RGB(A), one plane with pixelStride 1 means luma.
 *
 * **Why several sources per frame.** A phone PPG is only as good as the patch of finger it is
 * actually looking at. One large central average is diluted whenever the finger is slightly
 * off-centre, and the most pulsatile colour band varies with skin tone, finger thickness and torch
 * exposure. Emitting a central patch plus four quadrants across four bands lets
 * [com.bishnu.stresswelfarebridge.ppg.PpgSourceSelector] choose on measured evidence. Per-frame cost
 * stays constant because each patch samples a bounded number of pixels.
 */
class CameraPpgProcessor(
    private val onSample: (PpgSample) -> Unit,
    private val onSources: ((timeMs: Double, read: RegionRead) -> Unit)? = null
) : ImageAnalysis.Analyzer {

    /** Mean intensity of the central region of the most recent frame (0–255). */
    @Volatile var meanLevel: Double = 0.0
        private set

    /** True when the central region is saturated (torch reflecting, or a finger pressed too hard). */
    @Volatile var clipped: Boolean = false
        private set

    /** True when the central region is too dark (no finger / lens obstructed). */
    @Volatile var tooDark: Boolean = false
        private set

    @Volatile var frameCount: Long = 0L
        private set

    /** Decoded description of the most recent frame: format, planes and ROI statistics. */
    @Volatile var lastFrame: FrameRead? = null
        private set

    /** Decoded multi-region/multi-band view of the most recent frame. */
    @Volatile var lastRegionRead: RegionRead? = null
        private set

    /** Frames whose layout could not be decoded — the real-device tell that a format is off. */
    @Volatile var undecodableFrames: Long = 0L
        private set

    /** Measured frames per second, from the image timestamps rather than the requested rate. */
    @Volatile var fps: Double = 0.0
        private set

    /** Raw central means captured at the very start of a session, for a before/after comparison. */
    @Volatile var headSamples: List<Double> = emptyList()
        private set

    private var lastTimestampMs: Double = Double.NaN
    private var headCount = 0

    override fun analyze(image: ImageProxy) {
        try {
            val read = extract(image)
            if (read != null) {
                frameCount++
                val tMs = image.imageInfo.timestamp / 1_000_000.0
                updateFps(tMs)
                val central = read.centralLevel()
                if (headCount < HEAD_SAMPLES) {
                    headCount++
                    headSamples = headSamples + central
                }
                // Legacy single-scalar path, kept so the existing pipeline keeps working; the scalar
                // is the central GREEN patch, the most conservative choice.
                onSample(PpgSample(tMs, central, central, central))
                onSources?.invoke(tMs, read)
            } else {
                undecodableFrames++
            }
        } catch (_: Throwable) {
            // A malformed frame is dropped; the measurement simply continues with the next one.
        } finally {
            image.close()
        }
    }

    /** Clears the per-session diagnostics (called when a new measurement starts). */
    fun resetDiagnostics() {
        headCount = 0
        headSamples = emptyList()
        lastTimestampMs = Double.NaN
        fps = 0.0
    }

    private fun updateFps(tMs: Double) {
        if (lastTimestampMs.isNaN()) {
            lastTimestampMs = tMs
            return
        }
        val dt = tMs - lastTimestampMs
        lastTimestampMs = tMs
        // A duplicate or rewound timestamp must not be allowed to distort the measured rate.
        if (dt <= 0.0 || dt > 1000.0) return
        val instant = 1000.0 / dt
        fps = if (fps <= 0.0) instant else 0.8 * fps + 0.2 * instant
    }

    private fun extract(image: ImageProxy): RegionRead? {
        val planes = image.planes
        if (image.width <= 0 || image.height <= 0 || planes.isEmpty()) return null
        val read = readRegions(
            planes = planes.map { PlaneAdapter(it) },
            format = image.format,
            width = image.width,
            height = image.height,
            regions = DEFAULT_REGIONS,
            bands = DEFAULT_BANDS
        ) ?: return null
        lastRegionRead = read
        val central = read.centralLevel()
        meanLevel = central
        clipped = central >= SATURATION_HIGH
        tooDark = central <= SATURATION_LOW
        lastFrame = FrameRead(
            mean = central,
            min = 0.0,
            max = 255.0,
            std = 0.0,
            count = read.samples.size,
            channel = read.channel,
            formatName = read.formatName,
            width = read.width,
            height = read.height,
            planeCount = read.planeCount,
            planeInfo = emptyList()
        )
        return read
    }

    /** Wraps a CameraX plane in the Android-free [PixelPlane] the reader expects. */
    private class PlaneAdapter(private val plane: ImageProxy.PlaneProxy) : PixelPlane {
        override val rowStride: Int get() = plane.rowStride
        override val pixelStride: Int get() = plane.pixelStride
        override val capacity: Int get() = plane.buffer.capacity()

        override fun byteAt(index: Int): Int {
            val buffer = plane.buffer
            // Absolute get() is bounded by the buffer's limit, which is what actually guards a
            // padded or truncated row; anything outside it is reported as unavailable so the reader
            // skips that pixel instead of reading a stale byte.
            if (index < 0 || index >= buffer.limit()) return -1
            return buffer.get(index).toInt() and 0xFF
        }
    }

    private companion object {
        /** Raw central means retained for the first-N debug dump. */
        const val HEAD_SAMPLES = 20
        const val SATURATION_HIGH = 252.0
        const val SATURATION_LOW = 12.0

        /**
         * Central patch plus the four central quadrants. The central patch is the best default, and
         * the quadrants exist so a finger that is not perfectly centred is still followed.
         */
        val DEFAULT_REGIONS = listOf(
            PpgRegion.CENTRAL,
            PpgRegion.CENTRE_NW,
            PpgRegion.CENTRE_NE,
            PpgRegion.CENTRE_SW,
            PpgRegion.CENTRE_SE
        )

        /** All four bands: which carries the pulse best is decided per measurement, not assumed. */
        val DEFAULT_BANDS = PpgBand.ordered
    }
}

