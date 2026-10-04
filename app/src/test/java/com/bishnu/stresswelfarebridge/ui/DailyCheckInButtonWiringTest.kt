package com.bishnu.stresswelfarebridge.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The Daily Check-in save button must be driven by [com.bishnu.stresswelfarebridge.questionnaire.DailyCheckInForm]
 * and must never be gated on a stress score.
 *
 * The behavioural rules are asserted by real unit tests in `DailyCheckInEditTest`. This file guards
 * the wiring itself, which a pure logic test cannot see: if someone later inlines a different
 * `enabled = ...` expression, these assertions fail even though every logic test still passes.
 */
class DailyCheckInButtonWiringTest {

    private fun screen(): String {
        val candidates = listOf(
            File("src/main/java/com/bishnu/stresswelfarebridge/questionnaire/ui/DailyCheckInScreen.kt"),
            File("app/src/main/java/com/bishnu/stresswelfarebridge/questionnaire/ui/DailyCheckInScreen.kt")
        )
        val f = candidates.firstOrNull { it.exists() }
            ?: error("could not locate DailyCheckInScreen.kt")
        return f.readText()
    }

    @Test
    fun theSaveButtonIsEnabledFromTheSharedFormRule() {
        val src = screen()
        assertTrue("the button must use the shared rule", src.contains("DailyCheckInForm.canSave("))
        assertTrue(src.contains("enabled = canSave"))
    }

    @Test
    fun theButtonIsNeverGatedOnAStressScoreOrOnCompletion() {
        val src = screen()
        val enabledExpr = Regex("""enabled\s*=\s*([^\n]+)""").findAll(src).map { it.groupValues[1] }.toList()
        enabledExpr.forEach { expr ->
            assertFalse("must not gate on a stress score: $expr", expr.contains("Score"))
            assertFalse("must not gate on completion: $expr", expr.contains("completedToday"))
            assertFalse("must not gate on the stress-score null case: $expr", expr.contains("dailyStressScore"))
        }
    }

    @Test
    fun theDailyCheckInScreenStillHasNoStressScoreAnywhere() {
        assertFalse(screen().contains("dailyStressScore"))
        assertFalse(screen().contains("/ 100"))
    }

    @Test
    fun theUserCanReturnToTheQuestionsAfterSaving() {
        // Without this the only way back to editing would be to close and reopen the screen.
        assertTrue(screen().contains("Change my answers"))
        assertTrue(screen().contains("justSaved = false"))
    }

    @Test
    fun aDisabledButtonAlwaysExplainsWhy() {
        // The helper line is shown unconditionally, not only in the disabled case.
        val src = screen()
        assertTrue(src.contains("DailyCheckInForm.helperText("))
        assertTrue("the helper text must be rendered", src.contains("saveHelper"))
    }

    @Test
    fun theButtonLabelReflectsSaveVersusUpdate() {
        assertTrue(screen().contains("DailyCheckInForm.buttonLabel(existing)"))
    }
}
