package com.bishnu.stresswelfarebridge.ppg

import kotlin.math.sqrt

/** A colour/intensity band that can carry a photoplethysmogram. */
enum class PpgBand(val label: String) {
    GREEN("Green"),
    RED("Red"),
    BLUE("Blue"),
    LUMA("Luminance");

    companion object {
        /** Display/iteration order used when reporting which band was chosen. */
        val ordered: List<PpgBand> = listOf(GREEN, RED, BLUE, LUMA)
    }
}

/**
 * Spatial regions of a frame that are evaluated as candidate sampling areas.
 *
 * A fingertip on a phone rarely sits exactly on the geometric centre, and when it does not, a single
 * large central average is diluted by whatever background is visible instead. Keeping the four
 * central quadrants as separate candidates lets the selector follow the finger without the user
 * having to aim.
 */
enum class PpgRegion(val label: String) {
    /** The large central area — where a fingertip normally lands. */
    CENTRAL("central"),

    CENTRE_NW("centre-nw"),
    CENTRE_NE("centre-ne"),
    CENTRE_SW("centre-sw"),
    CENTRE_SE("centre-se");

    companion object {
        /** The region used until the selector has enough evidence to prefer another. */
        val DEFAULT = CENTRAL
    }
}

/** Mean/spread of one (region, band) pair for a single frame. */
data class RegionBandRead(
    val region: PpgRegion,
    val band: PpgBand,
    val mean: Double,
    val min: Double,
    val max: Double,
    val std: Double,
    val count: Int
) {
    /**
     * True when this patch is blown out or crushed and therefore carries no usable signal.
     *
     * This is the single most valuable per-frame diagnostic on a torch-lit fingertip: pressing hard
     * saturates the green channel completely, which produces a perfectly flat trace that no amount
     * of downstream filtering can rescue.
     */
    val clipped: Boolean
        get() = mean >= PpgAcquisition.SATURATION_HIGH || mean <= PpgAcquisition.SATURATION_LOW
}

/** One (region, band) pair, scored across a window of frames. */
data class PpgSourceKey(val region: PpgRegion, val band: PpgBand) {
    override fun toString(): String = "${band.label}/${region.label}"
}

/**
 * Multi-region, multi-band decoding of one camera frame.
 *
 * The previous implementation averaged one large central ROI on one band (green). That fails on a
 * real fingertip in two independent ways: the finger frequently does not cover the geometric centre
 * (so the average is diluted by background), and under a torch the band with the best usable contrast
 * is not always green. This type decodes all four bands across five regions so the selector can
 * choose on measured evidence instead of assumption.
 */
data class RegionRead(
    val formatName: String,
    val planeCount: Int,
    val channel: PpgChannel,
    val width: Int,
    val height: Int,
    val samples: List<RegionBandRead>
) {
    fun sample(region: PpgRegion, band: PpgBand): RegionBandRead? =
        samples.firstOrNull { it.region == region && it.band == band }

    /** Mean level of the large central patch — the "is the lens looking at a finger?" test. */
    fun centralLevel(band: PpgBand = PpgBand.GREEN): Double = sample(PpgRegion.CENTRAL, band)?.mean ?: 0.0

    fun layout(): String = "$formatName planes=$planeCount channel=${channel.name} ${width}x$height"
}

/** Pixel bounds (x0, y0, x1, y1) of each candidate region inside the frame. */
private fun regionBounds(region: PpgRegion, w: Int, h: Int): IntArray {
    val margin = 0.12
    val cx0 = (w * margin).toInt()
    val cy0 = (h * margin).toInt()
    val cx1 = (w * (1.0 - margin)).toInt().coerceAtLeast(cx0 + 2)
    val cy1 = (h * (1.0 - margin)).toInt().coerceAtLeast(cy0 + 2)
    if (region == PpgRegion.CENTRAL) return intArrayOf(cx0, cy0, cx1, cy1)

    // Quadrants with an inset, so each patch sits well inside the finger rather than straddling an
    // edge between finger tissue and background.
    val mx = (cx1 - cx0) / 8
    val my = (cy1 - cy0) / 8
    val midX = (cx0 + cx1) / 2
    val midY = (cy0 + cy1) / 2
    return when (region) {
        PpgRegion.CENTRE_NW -> intArrayOf(cx0 + mx, cy0 + my, midX, midY)
        PpgRegion.CENTRE_NE -> intArrayOf(midX, cy0 + my, cx1 - mx, midY)
        PpgRegion.CENTRE_SW -> intArrayOf(cx0 + mx, midY, midX, cy1 - my)
        PpgRegion.CENTRE_SE -> intArrayOf(midX, midY, cx1 - mx, cy1 - my)
        PpgRegion.CENTRAL -> intArrayOf(cx0, cy0, cx1, cy1)
    }
}

/** One band's value at (x, y), honouring each plane's own strides and the chroma subsampling. */
private fun sampleBand(
    planes: List<PixelPlane>,
    channel: PpgChannel,
    band: PpgBand,
    x: Int,
    y: Int
): Double? {
    val p0 = planes[0]
    val index = y * p0.rowStride + x * p0.pixelStride
    return when (channel) {
        PpgChannel.RGBA_GREEN, PpgChannel.RGB_GREEN -> {
            if (band == PpgBand.LUMA) return p0.byteAt(index).takeIf { it >= 0 }?.toDouble()
            val offset = when (band) {
                PpgBand.RED -> 0
                PpgBand.GREEN -> 1
                PpgBand.BLUE -> 2
                PpgBand.LUMA -> 0
            }
            p0.byteAt(index + offset).takeIf { it >= 0 }?.toDouble()
        }

        PpgChannel.LUMA -> p0.byteAt(index).takeIf { it >= 0 }?.toDouble()

        PpgChannel.YUV_GREEN -> {
            val yValue = p0.byteAt(index)
            if (yValue < 0) return null
            if (band == PpgBand.LUMA) return yValue.toDouble()
            val cb = chromaAt(planes, 1, x, y)
            val cr = chromaAt(planes, 2, x, y)
            if (cb < 0 || cr < 0) return null
            yuvToBand(yValue, cb, cr, band)
        }

        PpgChannel.UNKNOWN -> null
    }
}

/** Chroma sample for the half-resolution Cb/Cr planes. */
private fun chromaAt(planes: List<PixelPlane>, index: Int, x: Int, y: Int): Int {
    if (index >= planes.size) return -1
    val p = planes[index]
    return p.byteAt((y / 2) * p.rowStride + (x / 2) * p.pixelStride)
}

/**
 * BT.601 limited-range reconstruction of a chosen band from luma and chroma.
 *
 * Red and blue are reconstructed as well as green so that a fingertip whose pulse happens to be
 * better expressed in another band is still usable, instead of being discarded by assumption.
 */
private fun yuvToBand(y: Int, cb: Int, cr: Int, band: PpgBand): Double {
    val luma = 1.164 * (y - 16.0)
    val v = when (band) {
        PpgBand.RED -> luma + 1.596 * (cr - 128.0)
        PpgBand.GREEN -> luma - 0.392 * (cb - 128.0) - 0.813 * (cr - 128.0)
        PpgBand.BLUE -> luma + 2.017 * (cb - 128.0)
        PpgBand.LUMA -> luma
    }
    return v.coerceIn(0.0, 255.0)
}

/** Statistics of one (region, band) patch. */
private fun readPatch(
    planes: List<PixelPlane>,
    channel: PpgChannel,
    region: PpgRegion,
    band: PpgBand,
    bounds: IntArray,
    step: Int
): RegionBandRead? {
    val x0 = bounds[0]
    val y0 = bounds[1]
    val x1 = bounds[2]
    val y1 = bounds[3]
    var sum = 0.0
    var sumSq = 0.0
    var minV = Double.MAX_VALUE
    var maxV = -Double.MAX_VALUE
    var count = 0

    var y = y0
    while (y < y1) {
        var x = x0
        while (x < x1) {
            val value = sampleBand(planes, channel, band, x, y)
            if (value != null) {
                sum += value
                sumSq += value * value
                if (value < minV) minV = value
                if (value > maxV) maxV = value
                count++
            }
            x += step
        }
        y += step
    }
    if (count == 0) return null

    val mean = sum / count
    val variance = (sumSq / count) - mean * mean
    return RegionBandRead(
        region = region,
        band = band,
        mean = mean,
        min = minV,
        max = maxV,
        std = if (variance > 0.0) sqrt(variance) else 0.0,
        count = count
    )
}

/**
 * Decodes every candidate region and band from one frame.
 *
 * Memory stays bounded: only running sums are kept, the frame buffers are read in place and closed
 * by the caller immediately afterwards. Nothing about the frame is retained, stored or transmitted.
 */
fun readRegions(
    planes: List<PixelPlane>,
    format: Int,
    width: Int,
    height: Int,
    regions: List<PpgRegion> = PpgRegion.entries,
    bands: List<PpgBand> = PpgBand.ordered,
    samplesPerPatch: Int = 1024
): RegionRead? {
    if (width <= 0 || height <= 0 || planes.isEmpty()) return null
    val channel = PpgFrameReader.classify(planes)
    if (channel == PpgChannel.UNKNOWN) return null

    val out = ArrayList<RegionBandRead>(regions.size * bands.size)
    for (region in regions) {
        val bounds = regionBounds(region, width, height)
        val rw = bounds[2] - bounds[0]
        val rh = bounds[3] - bounds[1]
        if (rw <= 0 || rh <= 0) continue
        val step = (minOf(rw, rh) / kotlin.math.sqrt(samplesPerPatch.toDouble()))
            .toInt().coerceAtLeast(1)
        for (band in bands) {
            readPatch(planes, channel, region, band, bounds, step)?.let(out::add)
        }
    }
    if (out.isEmpty()) return null
    return RegionRead(
        formatName = PpgFrameReader.formatName(format),
        planeCount = planes.size,
        channel = channel,
        width = width,
        height = height,
        samples = out
    )
}

