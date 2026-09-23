package com.vocahq.vocaphone.core

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What the keyboard says while dictation cannot start.
 *
 * Setup now finishes while the model is still downloading, so the keyboard
 * can be opened before the model has landed. That is a wait, not a fault, and
 * the hint has to say so instead of sending the person back to the app.
 */
class DictationRepairHintTest {

    private fun repair(vararg missing: MissingPermission, progress: Int? = null) = DictationState(
        phase = DictationPhase.PERMISSION_REPAIR,
        missingPermissions = missing.toSet(),
        modelDownloadProgress = progress,
    )

    @Test
    fun aDownloadingModelNamesTheWaitAndItsProgress() {
        assertEquals("Model downloading · 40%", repair(MissingPermission.MODEL_DOWNLOADING, progress = 40).repairHint)
    }

    @Test
    fun aDownloadingModelWithoutAKnownPercentStillSaysItIsDownloading() {
        assertEquals("Model downloading", repair(MissingPermission.MODEL_DOWNLOADING).repairHint)
    }

    @Test
    fun aMissingModelSendsThePersonToChooseOne() {
        assertEquals("Open VocaPhone to choose a model", repair(MissingPermission.MODEL_MISSING).repairHint)
    }

    @Test
    fun everyOtherRepairKeepsTheGenericHint() {
        assertEquals("Open VocaPhone to finish setup", repair(MissingPermission.MICROPHONE).repairHint)
        assertEquals("Open VocaPhone to finish setup", repair(MissingPermission.GATEWAY_NOT_CONFIGURED).repairHint)
        assertEquals("Open VocaPhone to finish setup", repair().repairHint)
    }

    @Test
    fun theNewRepairsHaveTitlesForTheRepairScreen() {
        MissingPermission.entries.forEach { assert(it.title.isNotBlank()) { it.name } }
    }

    @Test
    fun aModelBeingPreparedSaysSoRatherThanAHundredPercent() {
        assertEquals("Preparing model…", repair(MissingPermission.MODEL_PREPARING).repairHint)
    }
}
