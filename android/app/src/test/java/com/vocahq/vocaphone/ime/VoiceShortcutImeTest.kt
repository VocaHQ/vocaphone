package com.vocahq.vocaphone.ime

import android.view.WindowManager
import com.vocahq.vocaphone.core.DictationPhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceShortcutImeTest {

    @Test
    fun `only an auxiliary voice subtype is the system shortcut`() {
        assertTrue(VoiceShortcutIme.isVoiceShortcutSubtype("voice", auxiliary = true))
        assertTrue(VoiceShortcutIme.isVoiceShortcutSubtype("VOICE", auxiliary = true))
        assertFalse(VoiceShortcutIme.isVoiceShortcutSubtype("voice", auxiliary = false))
        assertFalse(VoiceShortcutIme.isVoiceShortcutSubtype("keyboard", auxiliary = true))
        assertFalse(VoiceShortcutIme.isVoiceShortcutSubtype("keyboard", auxiliary = false))
        assertFalse(VoiceShortcutIme.isVoiceShortcutSubtype(null, auxiliary = true))
        assertFalse(VoiceShortcutIme.isVoiceShortcutSubtype("", auxiliary = true))
    }

    @Test
    fun `auto-start requires the shortcut, a dictation field, and a free pipeline`() {
        assertTrue(
            VoiceShortcutIme.shouldAutoStart(
                isVoiceShortcut = true,
                dictationAllowed = true,
                isBusy = false,
                alreadyRequested = false,
            ),
        )
        assertFalse(
            VoiceShortcutIme.shouldAutoStart(
                isVoiceShortcut = false,
                dictationAllowed = true,
                isBusy = false,
                alreadyRequested = false,
            ),
        )
        assertFalse(
            VoiceShortcutIme.shouldAutoStart(
                isVoiceShortcut = true,
                dictationAllowed = false,
                isBusy = false,
                alreadyRequested = false,
            ),
        )
        assertFalse(
            VoiceShortcutIme.shouldAutoStart(
                isVoiceShortcut = true,
                dictationAllowed = true,
                isBusy = true,
                alreadyRequested = false,
            ),
        )
        assertFalse(
            VoiceShortcutIme.shouldAutoStart(
                isVoiceShortcut = true,
                dictationAllowed = true,
                isBusy = false,
                alreadyRequested = true,
            ),
        )
        assertFalse(
            VoiceShortcutIme.shouldAutoStart(
                isVoiceShortcut = true,
                dictationAllowed = true,
                isBusy = false,
                alreadyRequested = false,
                inputViewShown = false,
            ),
        )
    }

    @Test
    fun `auto-start waits while the IME window is not yet visible`() {
        assertTrue(
            VoiceShortcutIme.shouldWaitForInputView(
                isVoiceShortcut = true,
                alreadyRequested = false,
                inputViewShown = false,
                waitAttempts = 0,
            ),
        )
        assertFalse(
            VoiceShortcutIme.shouldWaitForInputView(
                isVoiceShortcut = true,
                alreadyRequested = false,
                inputViewShown = true,
                waitAttempts = 0,
            ),
        )
        assertFalse(
            VoiceShortcutIme.shouldWaitForInputView(
                isVoiceShortcut = true,
                alreadyRequested = true,
                inputViewShown = false,
                waitAttempts = 0,
            ),
        )
        assertFalse(
            VoiceShortcutIme.shouldWaitForInputView(
                isVoiceShortcut = false,
                alreadyRequested = false,
                inputViewShown = false,
                waitAttempts = 0,
            ),
        )
        assertFalse(
            VoiceShortcutIme.shouldWaitForInputView(
                isVoiceShortcut = true,
                alreadyRequested = false,
                inputViewShown = false,
                waitAttempts = VoiceShortcutIme.MAX_WINDOW_WAITS,
            ),
        )
    }

    @Test
    fun `the normal keyboard never hands back to a previous IME`() {
        val phases = listOf(
            DictationPhase.IDLE,
            DictationPhase.LISTENING,
            DictationPhase.INSERTED,
            DictationPhase.FAILED,
            DictationPhase.READY_TO_INSERT,
        )
        for (phase in phases) {
            assertFalse(
                "non-shortcut $phase should stay on VocaPhone",
                VoiceShortcutIme.shouldReturnToPreviousIme(
                    isVoiceShortcut = false,
                    ownedSession = true,
                    sessionLeftIdle = true,
                    phase = phase,
                ),
            )
        }
    }

    @Test
    fun `shortcut returns after insert, cancel idle, or failed empty`() {
        assertTrue(
            VoiceShortcutIme.shouldReturnToPreviousIme(
                isVoiceShortcut = true,
                ownedSession = true,
                sessionLeftIdle = true,
                phase = DictationPhase.INSERTED,
            ),
        )
        assertTrue(
            VoiceShortcutIme.shouldReturnToPreviousIme(
                isVoiceShortcut = true,
                ownedSession = true,
                sessionLeftIdle = true,
                phase = DictationPhase.FAILED,
            ),
        )
        assertTrue(
            VoiceShortcutIme.shouldReturnToPreviousIme(
                isVoiceShortcut = true,
                ownedSession = true,
                sessionLeftIdle = true,
                phase = DictationPhase.IDLE,
            ),
        )
        assertTrue(
            VoiceShortcutIme.shouldReturnToPreviousIme(
                isVoiceShortcut = true,
                ownedSession = true,
                sessionLeftIdle = false,
                phase = DictationPhase.INSERTED,
            ),
        )
        assertFalse(
            VoiceShortcutIme.shouldReturnToPreviousIme(
                isVoiceShortcut = true,
                ownedSession = true,
                sessionLeftIdle = true,
                phase = DictationPhase.READY_TO_INSERT,
            ),
        )
    }

    @Test
    fun `shortcut does not bounce back before listening starts`() {
        assertFalse(
            VoiceShortcutIme.shouldReturnToPreviousIme(
                isVoiceShortcut = true,
                ownedSession = true,
                sessionLeftIdle = false,
                phase = DictationPhase.IDLE,
            ),
        )
        assertFalse(
            VoiceShortcutIme.shouldReturnToPreviousIme(
                isVoiceShortcut = true,
                ownedSession = true,
                sessionLeftIdle = false,
                phase = DictationPhase.LISTENING,
            ),
        )
        assertFalse(
            VoiceShortcutIme.shouldReturnToPreviousIme(
                isVoiceShortcut = true,
                ownedSession = true,
                sessionLeftIdle = true,
                phase = DictationPhase.LISTENING,
            ),
        )
        assertFalse(
            VoiceShortcutIme.shouldReturnToPreviousIme(
                isVoiceShortcut = true,
                ownedSession = true,
                sessionLeftIdle = true,
                phase = DictationPhase.TRANSCRIBING,
            ),
        )
        assertFalse(
            VoiceShortcutIme.shouldReturnToPreviousIme(
                isVoiceShortcut = true,
                ownedSession = true,
                sessionLeftIdle = true,
                phase = DictationPhase.PERMISSION_REPAIR,
            ),
        )
    }

    @Test
    fun `a session this shortcut did not start does not steal the keyboard`() {
        assertFalse(
            VoiceShortcutIme.shouldReturnToPreviousIme(
                isVoiceShortcut = true,
                ownedSession = false,
                sessionLeftIdle = true,
                phase = DictationPhase.INSERTED,
            ),
        )
        assertFalse(
            VoiceShortcutIme.shouldReturnToPreviousIme(
                isVoiceShortcut = true,
                ownedSession = false,
                sessionLeftIdle = true,
                phase = DictationPhase.IDLE,
            ),
        )
    }

    @Test
    fun `password and other rejected fields still hand back`() {
        assertTrue(
            VoiceShortcutIme.shouldReturnWhenDictationRejected(
                isVoiceShortcut = true,
                dictationAllowed = false,
            ),
        )
        assertFalse(
            VoiceShortcutIme.shouldReturnWhenDictationRejected(
                isVoiceShortcut = true,
                dictationAllowed = true,
            ),
        )
        assertFalse(
            VoiceShortcutIme.shouldReturnWhenDictationRejected(
                isVoiceShortcut = false,
                dictationAllowed = false,
            ),
        )
    }

    @Test
    fun `sensitive rejected fields hand back immediately`() {
        assertEquals(
            VoiceShortcutIme.RejectedHandback.IMMEDIATE,
            VoiceShortcutIme.rejectedHandback(
                isVoiceShortcut = true,
                dictationAllowed = false,
                sensitive = true,
            ),
        )
        assertEquals(
            VoiceShortcutIme.RejectedHandback.NONE,
            VoiceShortcutIme.rejectedHandback(
                isVoiceShortcut = true,
                dictationAllowed = true,
                sensitive = true,
            ),
        )
    }

    @Test
    fun `late capable EditorInfo after GUIDED cancels bounce and starts`() {
        // Picker / unknown field first → GUIDED delay is armed.
        val guidedDecision = VoiceShortcutIme.rejectedHandback(
            isVoiceShortcut = true,
            dictationAllowed = false,
            sensitive = false,
        )
        assertEquals(VoiceShortcutIme.RejectedHandback.GUIDED, guidedDecision)
        val guided = VoiceShortcutIme.rejectedHandbackEffects(
            decision = guidedDecision,
            alreadyReturned = false,
        )!!
        assertTrue(guided.scheduleDelayedHandback)
        assertTrue(guided.showGuidance)
        assertFalse(guided.requestAutoStart)
        assertFalse(guided.returnImmediately)

        var flags = VoiceShortcutIme.applyRejectedHandbackEffects(
            VoiceShortcutIme.RejectedHandbackFlags(),
            guided,
        )
        assertTrue(flags.delayedPending)
        assertTrue(flags.guidanceVisible)

        // A late StartInput can still deliver a dictation-capable EditorInfo.
        // Live re-check must flip to NONE so the service clears the delayed
        // bounce + guidance and starts — matching the delayed NONE arm.
        val noneDecision = VoiceShortcutIme.rejectedHandback(
            isVoiceShortcut = true,
            dictationAllowed = true,
            sensitive = false,
        )
        assertEquals(VoiceShortcutIme.RejectedHandback.NONE, noneDecision)
        val cleared = VoiceShortcutIme.rejectedHandbackEffects(
            decision = noneDecision,
            alreadyReturned = false,
        )!!
        assertTrue(cleared.cancelDelayedHandback)
        assertFalse(cleared.scheduleDelayedHandback)
        assertFalse(cleared.showGuidance)
        assertTrue(cleared.requestAutoStart)
        assertFalse(cleared.returnImmediately)

        flags = VoiceShortcutIme.applyRejectedHandbackEffects(flags, cleared)
        assertFalse("pending Handler bounce must clear", flags.delayedPending)
        assertFalse("guidance chrome must clear", flags.guidanceVisible)
        assertTrue(
            VoiceShortcutIme.shouldAutoStart(
                isVoiceShortcut = true,
                dictationAllowed = true,
                isBusy = false,
                alreadyRequested = false,
                inputViewShown = true,
            ),
        )
    }

    @Test
    fun `guided handback is a no-op after return and immediate clears pending`() {
        assertEquals(
            null,
            VoiceShortcutIme.rejectedHandbackEffects(
                decision = VoiceShortcutIme.RejectedHandback.GUIDED,
                alreadyReturned = true,
            ),
        )
        val immediate = VoiceShortcutIme.rejectedHandbackEffects(
            decision = VoiceShortcutIme.RejectedHandback.IMMEDIATE,
            alreadyReturned = false,
        )!!
        val flags = VoiceShortcutIme.applyRejectedHandbackEffects(
            VoiceShortcutIme.RejectedHandbackFlags(
                delayedPending = true,
                guidanceVisible = true,
            ),
            immediate,
        )
        assertFalse(flags.delayedPending)
        assertFalse(flags.guidanceVisible)
        assertTrue(immediate.returnImmediately)
        assertFalse(immediate.requestAutoStart)
    }

    @Test
    fun `picker and unknown editors use guided delayed handback`() {
        assertEquals(
            VoiceShortcutIme.RejectedHandback.GUIDED,
            VoiceShortcutIme.rejectedHandback(
                isVoiceShortcut = true,
                dictationAllowed = false,
                sensitive = false,
            ),
        )
        assertEquals(
            VoiceShortcutIme.RejectedHandback.NONE,
            VoiceShortcutIme.rejectedHandback(
                isVoiceShortcut = false,
                dictationAllowed = false,
                sensitive = false,
            ),
        )
        assertTrue(VoiceShortcutIme.REJECTED_HANDBACK_DELAY_MS > 0L)
        assertEquals(
            "Open a text field, then use the host keyboard mic",
            VoiceShortcutIme.REJECTED_HANDBACK_GUIDANCE,
        )
    }

    @Test
    fun `hiding the shortcut view returns even if dictation never started`() {
        assertTrue(
            VoiceShortcutIme.shouldReturnWhenViewFinishes(
                isVoiceShortcut = true,
                alreadyReturned = false,
            ),
        )
        assertFalse(
            VoiceShortcutIme.shouldReturnWhenViewFinishes(
                isVoiceShortcut = true,
                alreadyReturned = true,
            ),
        )
        assertFalse(
            VoiceShortcutIme.shouldReturnWhenViewFinishes(
                isVoiceShortcut = false,
                alreadyReturned = false,
            ),
        )
    }

    @Test
    fun `a hide during auto-start does not bounce back to the typing keyboard`() {
        assertTrue(
            VoiceShortcutIme.shouldKeepShortcutSession(
                isVoiceShortcut = true,
                startRequested = true,
                sessionLeftIdle = false,
            ),
        )
        assertFalse(
            VoiceShortcutIme.shouldKeepShortcutSession(
                isVoiceShortcut = true,
                startRequested = true,
                sessionLeftIdle = true,
            ),
        )
        assertFalse(
            VoiceShortcutIme.shouldKeepShortcutSession(
                isVoiceShortcut = true,
                startRequested = false,
                sessionLeftIdle = false,
            ),
        )
        assertFalse(
            VoiceShortcutIme.shouldKeepShortcutSession(
                isVoiceShortcut = false,
                startRequested = true,
                sessionLeftIdle = false,
            ),
        )
    }

    @Test
    fun `the voice shortcut does not take a microphone foreground service`() {
        assertFalse(VoiceShortcutIme.usesMicrophoneForegroundService(isVoiceShortcut = true))
        assertTrue(VoiceShortcutIme.usesMicrophoneForegroundService(isVoiceShortcut = false))
    }

    @Test
    fun `voice mode string matches method xml`() {
        assertEquals("voice", VoiceShortcutIme.MODE_VOICE)
    }

    @Test
    fun `publishing subtypes is a no-op without a manager or info`() {
        VoiceShortcutIme.publishEnabledSubtypes(null, "com.vocahq.vocaphone/.ime.VocaPhoneInputMethodService", null)
    }

    @Test
    fun `non-shortcut insets pass through the default`() {
        assertEquals(
            400,
            VoiceShortcutIme.contentTopInsetsPx(
                isVoiceShortcut = false,
                windowHeightPx = 1000,
                measuredInputHeightPx = 120,
                fallbackBarHeightPx = 80,
                defaultContentTopInsetsPx = 400,
            ),
        )
    }

    @Test
    fun `shortcut insets use the measured bar when it is shorter than the leftover window`() {
        assertEquals(
            880,
            VoiceShortcutIme.contentTopInsetsPx(
                isVoiceShortcut = true,
                windowHeightPx = 1000,
                measuredInputHeightPx = 120,
                fallbackBarHeightPx = 80,
                defaultContentTopInsetsPx = 0,
            ),
        )
    }

    @Test
    fun `shortcut insets use fallback when measured fills the leftover window`() {
        assertEquals(
            720,
            VoiceShortcutIme.contentTopInsetsPx(
                isVoiceShortcut = true,
                windowHeightPx = 800,
                measuredInputHeightPx = 800,
                fallbackBarHeightPx = 80,
                defaultContentTopInsetsPx = 0,
            ),
        )
    }

    @Test
    fun `shortcut insets use fallback when measured height is zero`() {
        assertEquals(
            720,
            VoiceShortcutIme.contentTopInsetsPx(
                isVoiceShortcut = true,
                windowHeightPx = 800,
                measuredInputHeightPx = 0,
                fallbackBarHeightPx = 80,
                defaultContentTopInsetsPx = 0,
            ),
        )
    }

    @Test
    fun `shortcut insets pass through default when window height is zero`() {
        assertEquals(
            400,
            VoiceShortcutIme.contentTopInsetsPx(
                isVoiceShortcut = true,
                windowHeightPx = 0,
                measuredInputHeightPx = 120,
                fallbackBarHeightPx = 80,
                defaultContentTopInsetsPx = 400,
            ),
        )
    }

    @Test
    fun `measured height larger than the window is treated as leftover fill`() {
        assertEquals(
            720,
            VoiceShortcutIme.contentTopInsetsPx(
                isVoiceShortcut = true,
                windowHeightPx = 800,
                measuredInputHeightPx = 900,
                fallbackBarHeightPx = 80,
                defaultContentTopInsetsPx = 0,
            ),
        )
    }

    @Test
    fun `shortcut insets are zero when wrap has already shrunk the window to the bar`() {
        assertEquals(
            0,
            VoiceShortcutIme.contentTopInsetsPx(
                isVoiceShortcut = true,
                windowHeightPx = 100,
                measuredInputHeightPx = 100,
                fallbackBarHeightPx = 80,
                defaultContentTopInsetsPx = 0,
            ),
        )
    }

    @Test
    fun `shortcut insets are zero when wrap succeeded before measure`() {
        assertEquals(
            0,
            VoiceShortcutIme.contentTopInsetsPx(
                isVoiceShortcut = true,
                windowHeightPx = 100,
                measuredInputHeightPx = 0,
                fallbackBarHeightPx = 80,
                defaultContentTopInsetsPx = 0,
            ),
        )
    }

    @Test
    fun `effective bar is the window when wrap succeeded`() {
        assertEquals(100, VoiceShortcutIme.effectiveBarHeightPx(100, 100, 80))
    }

    @Test
    fun `input window is remeasured when entering leaving or staying on the shortcut`() {
        assertFalse(VoiceShortcutIme.shouldRemeasureInputWindow(false, false))
        assertTrue(VoiceShortcutIme.shouldRemeasureInputWindow(true, false))
        assertTrue(VoiceShortcutIme.shouldRemeasureInputWindow(false, true))
        assertTrue(VoiceShortcutIme.shouldRemeasureInputWindow(true, true))
    }

    @Test
    fun `fallback bar height ceilings at tall dictation bar plus listening bar padding`() {
        assertEquals(10, VoiceShortcutIme.LISTENING_BAR_VERTICAL_PADDING_DP)
        assertEquals(68, VoiceShortcutIme.FALLBACK_BAR_DP)
        assertEquals(62, VoiceShortcutIme.fallbackBarDp(52))
        assertEquals(68, VoiceShortcutIme.fallbackBarDp(58))
    }

    @Test
    fun `restored soft-input height uses saved attrs or match parent`() {
        assertEquals(
            WindowManager.LayoutParams.MATCH_PARENT,
            VoiceShortcutIme.restoredSoftInputHeightPx(null),
        )
        assertEquals(1200, VoiceShortcutIme.restoredSoftInputHeightPx(1200))
        assertEquals(
            WindowManager.LayoutParams.WRAP_CONTENT,
            VoiceShortcutIme.restoredSoftInputHeightPx(WindowManager.LayoutParams.WRAP_CONTENT),
        )
    }
}
