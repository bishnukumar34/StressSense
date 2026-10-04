package com.bishnu.stresswelfarebridge.ppg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the frame decoder — the part of the pipeline that used to guess.
 *
 * These run entirely on synthetic byte buffers, which is the point: the "is plane 0 really green?"
 * question must be answerable without a physical phone, and a device that delivers a different
 * layout than requested must be *decoded differently*, not silently misread.
 */
class PpgFrameReaderTest {

    /** Minimal plane backed by a byte array; returns -1 outside the buffer, like the CameraX one. */
    private class FakePlane(
        override val rowStride: Int,
        override val pixelStride: Int,
        private val bytes: ByteArray
    ) : PixelPlane {
        override val capacity: Int get() = bytes.size
        override fun byteAt(index: Int): Int =
            if (index < 0 || index >= bytes.size) -1 else bytes[index].toInt() and 0xFF
    }

    private fun rgbaFrame(
        width: Int,
        height: Int,
        r: Int,
        g: Int,
        b: Int,
        rowPadding: Int = 0
    ): FakePlane {
        val rowStride = width * 4 + rowPadding
        val bytes = ByteArray(rowStride * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val i = y * rowStride + x * 4
                bytes[i] = r.toByte()
                bytes[i + 1] = g.toByte()
                bytes[i + 2] = b.toByte()
                bytes[i + 3] = 255.toByte()
            }
        }
        return FakePlane(rowStride, 4, bytes)
    }

    private fun yuvFrame(
        width: Int,
        height: Int,
        yValue: Int,
        cb: Int,
        cr: Int
    ): List<FakePlane> {
        val luma = ByteArray(width * height) { yValue.toByte() }
        val chromaW = width / 2
        val chromaH = height / 2
        val chroma = ByteArray(chromaW * chromaH) { cb.toByte() }
        val chroma2 = ByteArray(chromaW * chromaH) { cr.toByte() }
        return listOf(
            FakePlane(width, 1, luma),
            FakePlane(chromaW, 1, chroma),
            FakePlane(chromaW, 1, chroma2)
        )
    }

    // ------------------------------------------------------------------ layout classification

    @Test
    fun layoutIsDecidedByPlaneGeometryNotByTheRequestedFormat() {
        assertEquals(PpgChannel.RGBA_GREEN, PpgFrameReader.classify(listOf(rgbaFrame(8, 8, 0, 0, 0))))
        assertEquals(
            PpgChannel.RGB_GREEN,
            PpgFrameReader.classify(listOf(FakePlane(24, 3, ByteArray(24 * 8))))
        )
        assertEquals(PpgChannel.LUMA, PpgFrameReader.classify(listOf(FakePlane(8, 1, ByteArray(8 * 8)))))

        // The trap: three planes mean luma/Cb/Cr, whatever the caller asked for. Reading plane 0 as
        // "green RGB" here is exactly the bug this classification exists to prevent.
        assertEquals(PpgChannel.YUV_GREEN, PpgFrameReader.classify(yuvFrame(8, 8, 100, 128, 128)))

        // Nothing recognisable is refused rather than guessed at.
        assertEquals(PpgChannel.UNKNOWN, PpgFrameReader.classify(listOf(FakePlane(16, 2, ByteArray(128)))))
        assertEquals(PpgChannel.UNKNOWN, PpgFrameReader.classify(emptyList()))
    }

    // ------------------------------------------------------------------ channel extraction

    @Test
    fun interleavedRgbaFrameYieldsTheGreenByteNotRedBlueOrAlpha() {
        val read = PpgFrameReader.read(
            planes = listOf(rgbaFrame(16, 16, r = 10, g = 200, b = 30)),
            format = PpgFrameReader.FORMAT_RGBA_8888,
            width = 16,
            height = 16
        )
        assertNotNull(read)
        assertEquals(PpgChannel.RGBA_GREEN, read!!.channel)
        assertEquals(200.0, read.mean, 1e-9)
        assertEquals(200.0, read.min, 1e-9)
        assertEquals(200.0, read.max, 1e-9)
    }

    @Test
    fun rowStridePaddingIsHonouredSoRowsAreNotSkewed() {
        // 13 bytes of padding per row: reading with width*pixelStride instead of rowStride would
        // sample the padding and produce a completely different (and wrong) level.
        val read = PpgFrameReader.read(
            planes = listOf(rgbaFrame(16, 16, r = 5, g = 123, b = 250, rowPadding = 13)),
            format = PpgFrameReader.FORMAT_RGBA_8888,
            width = 16,
            height = 16
        )
        assertNotNull(read)
        assertEquals(123.0, read!!.mean, 1e-9)
    }

    @Test
    fun yuvFrameIsConvertedToRealGreenWithTheBt601Matrix() {
        // R=64, G=128, B=32 encoded to limited-range YUV must decode back to green ≈ 128.
        val read = PpgFrameReader.read(
            planes = yuvFrame(16, 16, yValue = 100, cb = 95, cr = 107),
            format = PpgFrameReader.FORMAT_YUV_420_888,
            width = 16,
            height = 16
        )
        assertNotNull(read)
        assertEquals(PpgChannel.YUV_GREEN, read!!.channel)
        assertEquals(128.0, read.mean, 2.0)
    }

    @Test
    fun neutralChromaReturnsLumaScaledGreenNotTheRawByte() {
        // Y = 235 with neutral chroma is the top of the limited-range BT.601 scale, so green lands
        // at ~255. Android's 1.164 coefficient is a rounded form of 255/219, so the result is
        // 254.916 rather than exactly 255 — the tolerance below is that rounding, not a defect.
        val read = PpgFrameReader.read(
            planes = yuvFrame(8, 8, yValue = 235, cb = 128, cr = 128),
            format = PpgFrameReader.FORMAT_YUV_420_888,
            width = 8,
            height = 8
        )
        assertNotNull(read)
        assertEquals(255.0, read!!.mean, 0.1)
        // A decoder that wrongly treated plane 0 as "green RGB" would have returned 235 here.
        assertTrue("expected the reconstructed green, got ${read.mean}", read.mean > 235.0)
    }

    @Test
    fun singleBytePlaneFallsBackToLuma() {
        val plane = FakePlane(8, 1, ByteArray(8 * 8) { 77.toByte() })
        val read = PpgFrameReader.read(listOf(plane), PpgFrameReader.FORMAT_YUV_420_888, 8, 8)
        assertNotNull(read)
        assertEquals(PpgChannel.LUMA, read!!.channel)
        assertEquals(77.0, read.mean, 1e-9)
    }

    @Test
    fun yuvToGreenMatchesTheAndroidBt601Coefficients() {
        // End points of the limited range. 1.164 is Android's rounded 255/219, so the white point
        // lands at 254.916; the 0.1 tolerance is that rounding, not a matrix error.
        assertEquals(0.0, PpgFrameReader.yuvToGreen(16, 128, 128), 1e-9)
        assertEquals(255.0, PpgFrameReader.yuvToGreen(235, 128, 128), 0.1)
        assertEquals(128.0, PpgFrameReader.yuvToGreen(126, 128, 128), 0.1)
        // A strongly red pixel (high Cr, low Cb) must pull green well below luma.
        assertTrue(PpgFrameReader.yuvToGreen(126, 64, 200) < 128.0)
        // A strongly blue pixel (high Cb) must pull green below luma as well.
        assertTrue(PpgFrameReader.yuvToGreen(126, 200, 128) < 128.0)
    }


    // ------------------------------------------------------------------ robustness + statistics

    @Test
    fun saturatedFrameIsReportedAsSaturatedWithZeroSpread() {
        val read = PpgFrameReader.read(
            planes = listOf(rgbaFrame(16, 16, r = 255, g = 255, b = 255)),
            format = PpgFrameReader.FORMAT_RGBA_8888,
            width = 16,
            height = 16
        )
        assertNotNull(read)
        assertEquals(255.0, read!!.mean, 1e-9)
        assertEquals(0.0, read.std, 1e-9)
        assertTrue(read.mean >= PpgSignalDiagnostics.SATURATED_LEVEL)
    }

    @Test
    fun aPulsatileFrameExposesItsSpreadSoFlatSignalsAreDetectable() {
        // Half the ROI dark, half bright: a real spread, unlike a blown-out or unlit frame.
        val width = 16
        val height = 16
        val rowStride = width * 4
        val bytes = ByteArray(rowStride * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val i = y * rowStride + x * 4
                val v = if (x < width / 2) 20 else 220
                bytes[i] = v.toByte(); bytes[i + 1] = v.toByte(); bytes[i + 2] = v.toByte()
            }
        }
        val read = PpgFrameReader.read(
            planes = listOf(FakePlane(rowStride, 4, bytes)),
            format = PpgFrameReader.FORMAT_RGBA_8888,
            width = width,
            height = height
        )
        assertNotNull(read)
        assertTrue("expected a real spread, got ${read!!.std}", read.std > 10.0)
        assertTrue(read.min < read.max)
    }

    @Test
    fun truncatedOrUnrecognisedFramesAreRefusedRatherThanGuessed() {
        // A buffer far too small to contain the ROI: every read is out of range.
        val tiny = FakePlane(4, 4, ByteArray(8))
        assertNull(PpgFrameReader.read(listOf(tiny), PpgFrameReader.FORMAT_RGBA_8888, 16, 16))

        // Unrecognised layout: refused outright.
        assertNull(
            PpgFrameReader.read(
                listOf(FakePlane(32, 2, ByteArray(512))),
                PpgFrameReader.FORMAT_RGBA_8888,
                16,
                16
            )
        )

        // Degenerate geometry: refused, no exception.
        assertNull(
            PpgFrameReader.read(
                listOf(rgbaFrame(8, 8, 1, 2, 3)),
                PpgFrameReader.FORMAT_RGBA_8888,
                0,
                8
            )
        )
        assertNull(PpgFrameReader.read(emptyList(), PpgFrameReader.FORMAT_RGBA_8888, 8, 8))
    }

    @Test
    fun theRegionOfInterestIsTheCentralPartOfTheFrame() {
        // Bright only in the outer border, dark in the centre: the ROI must ignore the border.
        val width = 16
        val height = 16
        val rowStride = width * 4
        val bytes = ByteArray(rowStride * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val v = if (x < 2 || x >= width - 2 || y < 2 || y >= height - 2) 250 else 100
                val i = y * rowStride + x * 4
                bytes[i] = v.toByte(); bytes[i + 1] = v.toByte(); bytes[i + 2] = v.toByte()
            }
        }
        val read = PpgFrameReader.read(
            planes = listOf(FakePlane(rowStride, 4, bytes)),
            format = PpgFrameReader.FORMAT_RGBA_8888,
            width = width,
            height = height
        )
        assertNotNull(read)
        assertEquals("the centre of this frame is uniformly 100", 100.0, read!!.mean, 1e-9)
    }
}

