package com.vocahq.vocaphone.ime

import com.vocahq.vocaphone.core.DictationPhase

/** What the keyboard mic does on tap vs long-press. */
internal enum class MicDictationAction {
    START,
    FINISH,
    NONE,
    CANCEL,
    OPEN_APP,
}

internal object MicDictationControl {
    fun tap(phase: DictationPhase): MicDictationAction = when {
        phase == DictationPhase.LISTENING -> MicDictationAction.FINISH
        // Only long-press is destructive while busy: a tap here used to
        // cancel, indistinguishable from long-press. Doing nothing keeps a
        // stray/repeated tap on the now-consolidated Stop button safe.
        phase.isBusy -> MicDictationAction.NONE
        phase == DictationPhase.PERMISSION_REPAIR -> MicDictationAction.OPEN_APP
        else -> MicDictationAction.START
    }

    fun longPress(phase: DictationPhase): MicDictationAction? =
        if (phase.isBusy) MicDictationAction.CANCEL else null

    /** Menu, clipboard, and models stay reachable unless dictation is actually running. */
    fun allowsMenu(phase: DictationPhase): Boolean = !phase.isBusy
}
