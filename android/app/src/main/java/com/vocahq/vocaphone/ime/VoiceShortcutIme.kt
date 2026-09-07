package com.vocahq.vocaphone.ime

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

    fun isVoiceShortcutSubtype(mode: String?, auxiliary: Boolean): Boolean =
        auxiliary && !mode.isNullOrEmpty() && mode.equals(MODE_VOICE, ignoreCase = true)

    fun shouldAutoStart(
        isVoiceShortcut: Boolean,
        dictationAllowed: Boolean,
        isBusy: Boolean,
        alreadyRequested: Boolean,
    ): Boolean = isVoiceShortcut && dictationAllowed && !isBusy && !alreadyRequested

    /**
     * Whether this activation should hand back to the typing keyboard.
     *
     * [ownedSession] is the voice-shortcut flag, not "VocaPhone is the normal
     * keyboard". IDLE is a return only after the session left idle — otherwise
     * auto-start would bounce back before listening began.
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
            DictationPhase.READY_TO_INSERT,
            -> true
            DictationPhase.IDLE -> sessionLeftIdle
            else -> false
        }
    }

    fun shouldReturnWhenDictationRejected(
        isVoiceShortcut: Boolean,
        dictationAllowed: Boolean,
    ): Boolean = isVoiceShortcut && !dictationAllowed

    fun shouldReturnWhenViewFinishes(
        isVoiceShortcut: Boolean,
        alreadyReturned: Boolean,
    ): Boolean = isVoiceShortcut && !alreadyReturned
}
