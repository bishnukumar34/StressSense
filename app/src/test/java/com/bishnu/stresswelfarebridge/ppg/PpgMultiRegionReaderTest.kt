package com.bishnu.stresswelfarebridge.ppg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for multi-region, multi-band frame decoding.
 *
 * These exist because the previous single-central-green-pixel pipeline was the weakest link in the
 * whole camera chain: if the finger was not perfectly centred, or the torch had driven one band into
 * saturation, there was no alternative left to fall back to.
 */
class PpgMultiRegionReaderTest {

    private class FakePlane(
        override val rowStride: Int,
        override val pixelStride: Int,
        private val bytes: ByteArray
    ) : PixelPlane {
        override val capacity: Int get() = bytes.size
        override fun byteAt(index: Int): Int =
            if (index < 0 || index >= bytes.size) -1 else bytes[index].toInt() and 0xFF
    }

    private val w = 32
    private val h = 32

    /** RGBA frame with a per-pixel colour chosen by [colorAt]. */
    private fun rgba(rowPadding: Int = 0, colorAt: (x: Int, y: Int) -> Triple<Int, Int, Int>): PixelPlane {
        val rowStride = w * 4 + rowPadding
        val bytes = ByteArray(rowStride * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val (r, g, b) = colorAt(x, y)
                val i = y * rowStride + x * 4
                bytes[i] = r.toByte(); bytes[i + 1] = g.toByte()
                bytes[i + 2] = b.toByte(); bytes[i + 3] = 255.toByte()
            }
        }
        return FakePlane(rowStride, 4, bytes)
    }

    private fun yuv(yValue: Int, cb: Int, cr: Int): List<PixelPlane> {
        val luma = ByteArray(w * h) { yValue.toByte() }
        val cw = w / 2
        val ch = h / 2
        val planeCb = ByteArray(cw * ch) { cb.toByte() }
        val planeCr = ByteArray(cw * ch) { cr.toByte() }
        return listOf(FakePlane(w, 1, luma), FakePlane(cw, 1, planeCb), FakePlane(cw, 1, planeCr))
    }

    // ------------------------------------------------------------------ extraction

    @Test
    fun everyRegionAndBandIsDecodedFromAnRgbaFrame() {
        val read = readRegions(listOf(rgba { _, _ -> Triple(10, 200, 30) }), PpgFrameReader.FORMAT_RGBA_8888, w, h)
        assertNotNull(read)
        // 5 regions x 4 bands.
        assertEquals(PpgRegion.entries.size * PpgBand.ordered.size, read!!.samples.size)
        for (region in PpgRegion.entries) {
            assertEquals(200.0, read.sample(region, PpgBand.GREEN)!!.mean, 1e-9)
            assertEquals(10.0, read.sample(region, PpgBand.RED)!!.mean, 1e-9)
            assertEquals(30.0, read.sample(region, PpgBand.BLUE)!!.mean, 1e-9)
        }
        assertEquals(PpgChannel.RGBA_GREEN, read.channel)
    }

    @Test
    fun rowStridePaddingIsHonouredAcrossEveryPatch() {
        // 7 bytes of padding per row: reading with width*pixelStride instead of rowStride would
        // sample padding and produce a different (wrong) level.
        val read = readRegions(
            listOf(rgba(rowPadding = 7) { _, _ -> Triple(5, 123, 250) }),
            PpgFrameReader.FORMAT_RGBA_8888, w, h
        )
        assertNotNull(read)
        for (sample in read!!.samples) {
            val expected = when (sample.band) {
                PpgBand.RED -> 5.0
                PpgBand.GREEN -> 123.0
                PpgBand.BLUE -> 250.0
                PpgBand.LUMA -> 5.0 // luma is the first byte of the RGBA pixel
            }
            assertEquals("band ${sample.band} region ${sample.region}", expected, sample.mean, 1e-9)
        }
    }

    @Test
    fun pixelStrideIsHonouredForAThreeBytePixelLayout() {
        val rowStride = w * 3
        val bytes = ByteArray(rowStride * h)
        for (y in 0 until h) for (x in 0 until w) {
            val i = y * rowStride + x * 3
            bytes[i] = 10.toByte(); bytes[i + 1] = 220.toByte(); bytes[i + 2] = 30.toByte()
        }
        val read = readRegions(listOf(FakePlane(rowStride, 3, bytes)), PpgFrameReader.FORMAT_RGBA_8888, w, h)
        assertNotNull(read)
        assertEquals(PpgChannel.RGB_GREEN, read!!.channel)
        assertEquals(220.0, read.sample(PpgRegion.CENTRAL, PpgBand.GREEN)!!.mean, 1e-9)
    }

    @Test
    fun yuvIsDecodedAsRealBandsNotAsAGreenRgbGuess() {
        // Y=100, Cb=95, Cr=107 decodes to (R=64, G=128, B=32) with the BT.601 matrix.
        val read = readRegions(yuv(100, 95, 107), PpgFrameReader.FORMAT_YUV_420_888, w, h)
        assertNotNull(read)
        assertEquals(PpgChannel.YUV_GREEN, read!!.channel)
        assertEquals(128.0, read.sample(PpgRegion.CENTRAL, PpgBand.GREEN)!!.mean, 2.5)
        assertEquals(64.0, read.sample(PpgRegion.CENTRAL, PpgBand.RED)!!.mean, 4.0)
        assertEquals(32.0, read.sample(PpgRegion.CENTRAL, PpgBand.BLUE)!!.mean, 4.0)
        // Luma is available as its own band and is not the same number as green.
        assertEquals(100.0, read.sample(PpgRegion.CENTRAL, PpgBand.LUMA)!!.mean, 1e-9)
    }

    @Test
    fun aFingerOnlyInOneQuadrantIsFoundByThatQuadrant() {
        // Dark everywhere except the north-west central quadrant, which is bright finger tissue.
        val read = readRegions(
            listOf(rgba { x, y -> if (x < w / 2 && y < h / 2) Triple(180, 190, 200) else Triple(8, 9, 10) }),
            PpgFrameReader.FORMAT_RGBA_8888, w, h
        )
        assertNotNull(read)
        assertTrue(
            "the finger quadrant must read much brighter than the rest",
            read!!.sample(PpgRegion.CENTRE_NW, PpgBand.GREEN)!!.mean >
                read.sample(PpgRegion.CENTRE_SE, PpgBand.GREEN)!!.mean + 100
        )
    }

    @Test
    fun aSaturatedPatchIsFlaggedSoItCannotWinSelection() {
        val read = readRegions(
            listOf(rgba { _, _ -> Triple(255, 255, 255) }), PpgFrameReader.FORMAT_RGBA_8888, w, h
        )
        assertTrue(read!!.samples.all { it.clipped })
    }

    @Test
    fun aCrushedPatchIsAlsoFlagged() {
        val read = readRegions(
            listOf(rgba { _, _ -> Triple(1, 2, 3) }), PpgFrameReader.FORMAT_RGBA_8888, w, h
        )
        assertTrue(read!!.samples.all { it.clipped })
    }

    @Test
    fun aUsablePatchIsNotFlaggedAsClipped() {
        val read = readRegions(
            listOf(rgba { _, _ -> Triple(120, 130, 140) }), PpgFrameReader.FORMAT_RGBA_8888, w, h
        )
        assertTrue(read!!.samples.none { it.clipped })
    }

    @Test
    fun undecodableGeometryIsRefusedRatherThanMisread() {
        assertNull(readRegions(emptyList(), PpgFrameReader.FORMAT_RGBA_8888, w, h))
        assertNull(readRegions(listOf(FakePlane(64, 2, ByteArray(2048))), PpgFrameReader.FORMAT_RGBA_8888, w, h))
        assertNull(readRegions(listOf(rgba { _, _ -> Triple(1, 2, 3) }), PpgFrameReader.FORMAT_RGBA_8888, 0, h))
    }

    @Test
    fun everyRegionProducesSamplesAndTheyAreDistinctPatches() {
        val read = readRegions(listOf(rgba { _, _ -> Triple(10, 10, 10) }), PpgFrameReader.FORMAT_RGBA_8888, w, h)
        assertNotNull(read)
        for (region in PpgRegion.entries) {
            val sample = read!!.sample(region, PpgBand.GREEN)
            assertNotNull("region $region produced no samples", sample)
            assertTrue("region $region had no pixels", sample!!.count > 0)
        }
    }

    @Test
    fun regionPatchesDoNotAllCoverTheWholeFrame() {
        // A quadrant must be a genuinely smaller patch than the central one, otherwise the
        // "several candidate regions" idea is really just the same pixels counted repeatedly.
        val read = readRegions(listOf(rgba { _, _ -> Triple(10, 10, 10) }), PpgFrameReader.FORMAT_RGBA_8888, w, h)
        val central = read!!.sample(PpgRegion.CENTRAL, PpgBand.GREEN)!!.count
        val quadrant = read.sample(PpgRegion.CENTRE_NW, PpgBand.GREEN)!!.count
        assertTrue("quadrant ($quadrant) should be much smaller than central ($central)", quadrant * 3 < central)
    }
}

