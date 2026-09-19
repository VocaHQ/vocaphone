package com.vocahq.vocaphone.ime

import com.vocahq.vocaphone.core.DictationPhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MicDictationControlTest {

    @Test
    fun `tap starts, finishes, accepts the partial, or cancels by phase`() {
        assertEquals(MicDictationAction.START, MicDictationControl.tap(DictationPhase.IDLE))
        assertEquals(MicDictationAction.FINISH, MicDictationControl.tap(DictationPhase.LISTENING))
        assertEquals(
            MicDictationAction.ACCEPT_PARTIAL,
            MicDictationControl.tap(DictationPhase.TRANSCRIBING),
        )
        assertEquals(
            MicDictationAction.ACCEPT_PARTIAL,
            MicDictationControl.tap(DictationPhase.FINALIZING),
        )
        // INSERTING is already committing text; accepting the partial there
        // would risk a double insertion, so it stays a no-op.
        assertEquals(MicDictationAction.NONE, MicDictationControl.tap(DictationPhase.INSERTING))
        assertEquals(
            MicDictationAction.OPEN_APP,
            MicDictationControl.tap(DictationPhase.PERMISSION_REPAIR),
        )
    }

    @Test
    fun `long press cancels only while dictation owns the mic or the pipeline`() {
        assertEquals(
            MicDictationAction.CANCEL,
            MicDictationControl.longPress(DictationPhase.LISTENING),
        )
        assertEquals(
            MicDictationAction.CANCEL,
            MicDictationControl.longPress(DictationPhase.TRANSCRIBING),
        )
        assertNull(MicDictationControl.longPress(DictationPhase.IDLE))
        assertNull(MicDictationControl.longPress(DictationPhase.FAILED))
    }

    @Test
    fun `a failed empty transcript does not lock the keyboard menu`() {
        assertTrue(MicDictationControl.allowsMenu(DictationPhase.IDLE))
        assertTrue(MicDictationControl.allowsMenu(DictationPhase.FAILED))
        assertTrue(MicDictationControl.allowsMenu(DictationPhase.INSERTED))
        assertFalse(MicDictationControl.allowsMenu(DictationPhase.LISTENING))
        assertFalse(MicDictationControl.allowsMenu(DictationPhase.TRANSCRIBING))
    }
}
