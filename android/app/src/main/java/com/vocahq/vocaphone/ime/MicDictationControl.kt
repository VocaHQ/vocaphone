package com.vocahq.vocaphone.ime

import com.vocahq.vocaphone.core.DictationPhase

/** What the keyboard mic does on tap vs long-press. */
internal enum class MicDictationAction {
    START,
    FINISH,
    NONE,
    ACCEPT_PARTIAL,
    CANCEL,
    OPEN_APP,
}

internal object MicDictationControl {
    fun tap(phase: DictationPhase): MicDictationAction = when {
        phase == DictationPhase.LISTENING -> MicDictationAction.FINISH
        // INSERTING is already committing text via a largely synchronous
        // editor call; there's no safe partial to insert on top of an
        // insertion already in flight, so it stays a no-op.
        phase == DictationPhase.INSERTING -> MicDictationAction.NONE
        // Every other busy phase can offer up whatever partial transcript
        // is already on hand instead of doing nothing.
        phase.isBusy -> MicDictationAction.ACCEPT_PARTIAL
        phase == DictationPhase.PERMISSION_REPAIR -> MicDictationAction.OPEN_APP
        else -> MicDictationAction.START
    }

    fun longPress(phase: DictationPhase): MicDictationAction? =
        if (phase.isBusy) MicDictationAction.CANCEL else null

    /** Menu, clipboard, and models stay reachable unless dictation is actually running. */
    fun allowsMenu(phase: DictationPhase): Boolean = !phase.isBusy
}
