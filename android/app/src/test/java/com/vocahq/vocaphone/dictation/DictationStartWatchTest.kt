package com.vocahq.vocaphone.dictation

import com.vocahq.vocaphone.core.DictationPhase
import com.vocahq.vocaphone.core.DictationState
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DictationStartWatchTest {

    @Test
    fun `holding the microphone settles the start`() {
        assertTrue(DictationStartWatch.hasSettled(DictationPhase.LISTENING))
        assertTrue(DictationStartWatch.hasSettled(DictationPhase.FINALIZING))
    }

    /**
     * The service holds a microphone foreground service from before capture
     * begins, so an outcome that never reaches the microphone has to end the
     * wait. Leaving it to the timeout showed "VocaPhone is recording" and the
     * system microphone indicator for ten seconds over a dictation that never
     * started — which is what tapping the mic before setup was finished did.
     */
    @Test
    fun `an outcome that never records settles the start too`() {
        assertTrue(DictationStartWatch.hasSettled(DictationPhase.PERMISSION_REPAIR))
        assertTrue(DictationStartWatch.hasSettled(DictationPhase.FAILED))
    }

    /**
     * Idle is the phase the state is already in when the service subscribes, so
     * treating it as an outcome would abandon every dictation before it began.
     */
    @Test
    fun `idle is not an outcome`() {
        assertFalse(DictationStartWatch.hasSettled(DictationPhase.IDLE))
    }

    /**
     * These arrive after the microphone has been released. Reaching one without
     * passing through LISTENING would mean capture never happened.
     */
    @Test
    fun `phases past the microphone do not stand in for it`() {
        for (phase in listOf(
            DictationPhase.UPLOADING,
            DictationPhase.TRANSCRIBING,
            DictationPhase.READY_TO_INSERT,
            DictationPhase.INSERTING,
            DictationPhase.INSERTED,
        )) {
            assertFalse("$phase", DictationStartWatch.hasSettled(phase))
        }
    }

    @Test
    fun `terminal phases release the foreground service after inference`() {
        assertTrue(DictationPhase.READY_TO_INSERT.isTerminal)
        assertTrue(DictationPhase.INSERTED.isTerminal)
        assertTrue(DictationPhase.FAILED.isTerminal)
        assertFalse(DictationPhase.TRANSCRIBING.isTerminal)
        assertFalse(DictationPhase.INSERTING.isTerminal)
    }

    @Test
    fun `the idle snapshot the service subscribed with is not progress`() {
        val initial = DictationState()
        assertFalse(DictationStartWatch.hasProgressed(initial, initial))
        assertTrue(DictationStartWatch.hasProgressed(DictationState(), initial))
    }

    /**
     * Controller updates run off Main; the service collects on it. StateFlow
     * then skips LISTENING/FINALIZING on a short tap and only delivers the
     * reset Idle. hasSettled(Idle) is false, which is how the notification
     * stayed on Listening after the dictation had already finished.
     */
    @Test
    fun `a conflated short tap is progress even though it is idle again`() = runTest {
        val initial = DictationState()
        val flow = MutableStateFlow(initial)
        flow.value = DictationState(
            sessionId = UUID.randomUUID(),
            phase = DictationPhase.LISTENING,
        )
        flow.value = DictationState()
        val seen = flow.first { DictationStartWatch.hasProgressed(it, initial) }
        assertEquals(DictationPhase.IDLE, seen.phase)
        assertTrue(seen.phase.isTerminal)
        assertTrue(DictationStartWatch.hasProgressed(seen, initial))
    }

    @Test
    fun `hasSettled misses a conflated short tap`() = runTest {
        val flow = MutableStateFlow(DictationState())
        flow.value = DictationState(
            sessionId = UUID.randomUUID(),
            phase = DictationPhase.LISTENING,
        )
        flow.value = DictationState()
        val settled = withTimeoutOrNull(50) {
            flow.first { DictationStartWatch.hasSettled(it.phase) }
        }
        assertNull(settled)
    }

    /**
     * The same skip can land on Transcribing. That is not terminal: the
     * foreground service has to stay up through on-device inference.
     */
    @Test
    fun `a conflated jump to transcribing is progress and stays busy`() = runTest {
        val initial = DictationState()
        val flow = MutableStateFlow(initial)
        flow.value = DictationState(
            sessionId = UUID.randomUUID(),
            phase = DictationPhase.LISTENING,
        )
        flow.value = DictationState(
            sessionId = UUID.randomUUID(),
            phase = DictationPhase.TRANSCRIBING,
        )
        val seen = flow.first { DictationStartWatch.hasProgressed(it, initial) }
        assertEquals(DictationPhase.TRANSCRIBING, seen.phase)
        assertFalse(seen.phase.isTerminal)
    }
}
