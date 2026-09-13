package com.vocahq.vocaphone.ime

import android.os.Build
import android.view.Gravity
import android.view.WindowManager
import android.view.inputmethod.InputMethodInfo
import android.view.inputmethod.InputMethodManager
import com.vocahq.vocaphone.core.DictationPhase

/**
 * Decisions for the auxiliary voice subtype other keyboards hand off to.
 *
 * HeliBoard / Gboard-style mic keys call
 * [android.view.inputmethod.InputMethodManager.getShortcutInputMethodsAndSubtypes],
 * which only lists IMEs that declare a voice-mode auxiliary subtype.
 */
internal object VoiceShortcutIme {
    const val MODE_VOICE = "voice"
    const val MAX_WINDOW_WAITS = 10
    const val WINDOW_WAIT_DELAY_MS = 50L
    // KeyboardHeight.DEFAULT.dictationBarDp (52) + VoiceShortcutListeningBar padding (4+6).
    const val FALLBACK_BAR_DP = 62
    const val SHORTCUT_WINDOW_GRAVITY = Gravity.BOTTOM
    const val SHORTCUT_WINDOW_HEIGHT = WindowManager.LayoutParams.WRAP_CONTENT

    fun isVoiceShortcutSubtype(mode: String?, auxiliary: Boolean): Boolean =
        auxiliary && !mode.isNullOrEmpty() && mode.equals(MODE_VOICE, ignoreCase = true)

    /** Hash codes of every subtype, so the voice shortcut is explicitly enabled. */
    fun subtypeHashes(imi: InputMethodInfo): IntArray =
        IntArray(imi.subtypeCount) { imi.getSubtypeAt(it).hashCode() }

    fun publishEnabledSubtypes(imm: InputMethodManager?, imiId: String, imi: InputMethodInfo?) {
        if (imm == null || imi == null) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return
        imm.setExplicitlyEnabledInputMethodSubtypes(imiId, subtypeHashes(imi))
    }

    fun shouldAutoStart(
        isVoiceShortcut: Boolean,
        dictationAllowed: Boolean,
        isBusy: Boolean,
        alreadyRequested: Boolean,
        inputViewShown: Boolean = true,
    ): Boolean = isVoiceShortcut && dictationAllowed && !isBusy && !alreadyRequested && inputViewShown

    /**
     * HeliBoard hands off before our IME window counts as visible, and
     * startForeground(MICROPHONE) on targetSdk 36 throws until it does.
     */
    fun shouldWaitForInputView(
        isVoiceShortcut: Boolean,
        alreadyRequested: Boolean,
        inputViewShown: Boolean,
        waitAttempts: Int,
    ): Boolean = isVoiceShortcut && !alreadyRequested && !inputViewShown &&
        waitAttempts < MAX_WINDOW_WAITS

    /**
     * Whether this activation should hand back to the typing keyboard.
     *
     * [ownedSession] is the voice-shortcut flag, not "VocaPhone is the normal
     * keyboard". IDLE is a return only after the session left idle — otherwise
     * auto-start would bounce back before listening began.
     *
     * READY_TO_INSERT is an IME insert that already failed. Stay on the
     * shortcut chrome so retry and the transcript are still on screen.
     */
    fun shouldReturnToPreviousIme(
        isVoiceShortcut: Boolean,
        ownedSession: Boolean,
        sessionLeftIdle: Boolean,
        phase: DictationPhase,
    ): Boolean {
        if (!isVoiceShortcut || !ownedSession) return false
        return when (phase) {
            DictationPhase.INSERTED,
            DictationPhase.FAILED,
            -> true
            DictationPhase.IDLE -> sessionLeftIdle
            else -> false
        }
    }

    /**
     * How to leave the voice subtype when dictation cannot run.
     *
     * [RejectedHandback.IMMEDIATE] is for sensitive editors (passwords): leave
     * without lingering chrome. [RejectedHandback.GUIDED] covers the system
     * IME-picker path and other non-dictation fields: show a one-line hint,
     * then hand back after [REJECTED_HANDBACK_DELAY_MS] so the subtype does
     * not flicker away in the same frame (#281). HeliBoard/Gboard handoff
     * still starts dictation when [dictationAllowed] is true.
     */
    enum class RejectedHandback {
        NONE,
        IMMEDIATE,
        GUIDED,
    }

    const val REJECTED_HANDBACK_DELAY_MS = 1_800L

    const val REJECTED_HANDBACK_GUIDANCE =
        "Open a text field, then use the host keyboard mic"

    fun rejectedHandback(
        isVoiceShortcut: Boolean,
        dictationAllowed: Boolean,
        sensitive: Boolean,
    ): RejectedHandback {
        if (!isVoiceShortcut || dictationAllowed) return RejectedHandback.NONE
        return if (sensitive) RejectedHandback.IMMEDIATE else RejectedHandback.GUIDED
    }


    /**
     * Side effects for [rejectedHandback] without a Handler or IMS.
     *
     * The service applies these: cancel/schedule the delayed bounce, toggle
     * the one-line hint, leave immediately, or request auto-start. Returning
     * null means a no-op (guided path after we already handed back).
     */
    data class RejectedHandbackEffects(
        val cancelDelayedHandback: Boolean,
        val scheduleDelayedHandback: Boolean,
        val showGuidance: Boolean,
        val returnImmediately: Boolean,
        val requestAutoStart: Boolean,
    )

    fun rejectedHandbackEffects(
        decision: RejectedHandback,
        alreadyReturned: Boolean,
    ): RejectedHandbackEffects? = when (decision) {
        RejectedHandback.IMMEDIATE -> RejectedHandbackEffects(
            cancelDelayedHandback = true,
            scheduleDelayedHandback = false,
            showGuidance = false,
            returnImmediately = true,
            requestAutoStart = false,
        )
        RejectedHandback.GUIDED -> {
            if (alreadyReturned) {
                null
            } else {
                RejectedHandbackEffects(
                    cancelDelayedHandback = true,
                    scheduleDelayedHandback = true,
                    showGuidance = true,
                    returnImmediately = false,
                    requestAutoStart = false,
                )
            }
        }
        RejectedHandback.NONE -> RejectedHandbackEffects(
            cancelDelayedHandback = true,
            scheduleDelayedHandback = false,
            showGuidance = false,
            returnImmediately = false,
            requestAutoStart = true,
        )
    }

    /**
     * Pure fold of [RejectedHandbackEffects] onto the delayed-bounce flags the
     * service keeps (pending Handler callback + guidance chrome). Lets unit
     * tests prove GUIDED → NONE clears a pending bounce without Robolectric.
     */
    data class RejectedHandbackFlags(
        val delayedPending: Boolean = false,
        val guidanceVisible: Boolean = false,
    )

    fun applyRejectedHandbackEffects(
        flags: RejectedHandbackFlags,
        effects: RejectedHandbackEffects,
    ): RejectedHandbackFlags {
        var delayedPending = flags.delayedPending
        if (effects.cancelDelayedHandback) delayedPending = false
        if (effects.scheduleDelayedHandback) delayedPending = true
        return RejectedHandbackFlags(
            delayedPending = delayedPending,
            guidanceVisible = effects.showGuidance,
        )
    }

    fun shouldReturnWhenDictationRejected(
        isVoiceShortcut: Boolean,
        dictationAllowed: Boolean,
    ): Boolean = isVoiceShortcut && !dictationAllowed

    fun shouldReturnWhenViewFinishes(
        isVoiceShortcut: Boolean,
        alreadyReturned: Boolean,
    ): Boolean = isVoiceShortcut && !alreadyReturned

    /**
     * A HeliBoard handoff can hide our input view for a frame while the
     * microphone service is still coming up. Returning then would make
     * HeliBoard the current IME again and leave insert with no connection.
     */
    fun shouldKeepShortcutSession(
        isVoiceShortcut: Boolean,
        startRequested: Boolean,
        sessionLeftIdle: Boolean,
    ): Boolean = isVoiceShortcut && startRequested && !sessionLeftIdle

    /**
     * HeliBoard handoff does not get while-in-use for a microphone FGS
     * (targetSdk 36). The IME window is visible, so capture can run without
     * it; we cancel when the view finishes.
     */
    fun usesMicrophoneForegroundService(isVoiceShortcut: Boolean): Boolean = !isVoiceShortcut

    /**
     * Real bar height for shortcut insets.
     *
     * A measured height strictly in (0, window) is a distinct short bar in
     * leftover fill. After WRAP_CONTENT the window has already shrunk to the
     * bar, so measured == window is success. Leftover fill is only when the
     * window is clearly taller than a bar (more than 2× fallback).
     */
    fun effectiveBarHeightPx(
        windowHeightPx: Int,
        measuredInputHeightPx: Int,
        fallbackBarHeightPx: Int,
    ): Int {
        val fallback = fallbackBarHeightPx.coerceAtLeast(1)
        if (windowHeightPx <= 0) return fallback
        if (measuredInputHeightPx in 1 until windowHeightPx) return measuredInputHeightPx
        val leftoverThreshold = fallback * 2
        if (windowHeightPx <= leftoverThreshold) return windowHeightPx.coerceAtLeast(1)
        return fallback.coerceAtMost(windowHeightPx)
    }

    /**
     * contentTopInsets so the editor pans only for the bar, not the leftover
     * tall window HeliBoard left behind.
     */
    fun contentTopInsetsPx(
        isVoiceShortcut: Boolean,
        windowHeightPx: Int,
        measuredInputHeightPx: Int,
        fallbackBarHeightPx: Int,
        defaultContentTopInsetsPx: Int,
    ): Int {
        if (!isVoiceShortcut || windowHeightPx <= 0) return defaultContentTopInsetsPx
        val bar = effectiveBarHeightPx(windowHeightPx, measuredInputHeightPx, fallbackBarHeightPx)
        return (windowHeightPx - bar).coerceAtLeast(0)
    }

    /** Remeasure on enter, leave, or stay (rapid cancel/restart). */
    fun shouldRemeasureInputWindow(isVoiceShortcut: Boolean, wasVoiceShortcut: Boolean): Boolean =
        isVoiceShortcut || wasVoiceShortcut

    /** WRAP_CONTENT + BOTTOM is applied for the whole time the shortcut is showing. */
    fun shouldApplyShortcutWindow(isVoiceShortcut: Boolean): Boolean = isVoiceShortcut
}
