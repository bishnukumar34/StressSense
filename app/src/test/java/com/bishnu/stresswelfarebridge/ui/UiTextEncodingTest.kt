package com.bishnu.stresswelfarebridge.ui

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Regression guard against mojibake reaching the UI.
 *
 * The corruption seen on a real device was not a rendering bug: the Kotlin
 * sources held the *bytes* of those characters re-decoded as Windows-1252, so every affected string
 * literally contained U+00E2, U+20AC and U+201D. Because those are valid code points, the compiler
 * and the IDE were happy — only the screen showed the damage. This test walks the source tree and
 * fails if the tell-tale lead characters ever come back, which is the only reliable guard because the
 * files are compiled as UTF-8 and cannot be validated by the compiler.
 *
 * Legitimate characters that merely *look* similar are explicitly allowed:
 *  - U+2014 EM DASH, U+2013 EN DASH, U+2022 BULLET, U+2026 HORIZONTAL ELLIPSIS, U+2190/2192 ARROWS,
 *    math symbols (sigma, sigma, superscript two, plus-minus, multiplication, division, minus,
 *    approximately-equal, greater-or-equal, one-quarter) and the U+25CF/U+25CB status dots,
 *  - the zone colour emoji in `commander/StressZone.kt`.
 * Forbidden is the classic UTF-8-read-as-CP1252 pattern: any U+00E2 / U+00C2 / U+00C3 lead byte.
 */
class UiTextEncodingTest {

    /**
     * Mojibake produced when UTF-8 bytes are decoded as Windows-1252 and re-encoded as UTF-8.
     *
     * Built from code points rather than written literally, because a literal mojibake sample inside this file
     * would (correctly) trip the very check defined below.
     */
    /** U+FFFD, the replacement character a decoder emits for bytes it cannot interpret. */
    private val REPLACEMENT_CHAR: String = 0xFFFD.toChar().toString()

    private val MOJIBAKE_MARKERS: List<String> = buildList {
        // U+00E2 / U+00C2 / U+00C3 are the CP1252 renderings of UTF-8 lead bytes E2 / C2 / C3.
        add(0x00E2.toChar().toString()) // lead byte E2: em dash, bullet, subscript
        add(0x00C2.toChar().toString()) // lead byte C2: e.g. copyright, degree
        add(0x00C3.toChar().toString()) // lead byte C3: e.g. multiplication, e-acute
        add(REPLACEMENT_CHAR)
    }

    private fun sourceFiles(): List<File> {
        val root = File("src")
        if (!root.exists()) return emptyList()
        return root.walkTopDown()
            .filter { it.isFile && it.extension in setOf("kt", "xml", "json") }
            .toList()
    }

    @Test
    fun sourceTreeIsAvailableToScan() {
        assertTrue("test must run with app/ as the working directory", sourceFiles().isNotEmpty())
    }

    @Test
    fun noSourceFileContainsMojibakeSequences() {
        val offenders = mutableListOf<String>()
        for (file in sourceFiles()) {
            val text = runCatching { file.readText(Charsets.UTF_8) }.getOrNull() ?: continue
            MOJIBAKE_MARKERS.forEach { marker ->
                if (text.contains(marker)) offenders += "${file.path} contains '$marker'"
            }
            if (text.contains(REPLACEMENT_CHAR)) offenders += "${file.path} contains U+FFFD"
        }
        assertTrue(
            "Mojibake found — re-save these files as UTF-8 and repair the strings:\n" +
                offenders.joinToString("\n"),
            offenders.isEmpty()
        )
    }

    @Test
    fun resourceFilesAreValidUtf8() {
        val offenders = mutableListOf<String>()
        for (file in sourceFiles().filter { it.extension == "xml" }) {
            val bytes = runCatching { file.readBytes() }.getOrNull() ?: continue
            val decoded = runCatching { String(bytes, Charsets.UTF_8) }.getOrNull() ?: continue
            if (decoded.contains(REPLACEMENT_CHAR)) offenders += file.path
        }
        assertTrue("these XML files are not valid UTF-8:\n" + offenders.joinToString("\n"), offenders.isEmpty())
    }
}
