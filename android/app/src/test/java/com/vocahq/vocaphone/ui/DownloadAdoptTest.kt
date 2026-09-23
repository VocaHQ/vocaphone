package com.vocahq.vocaphone.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Whether a finishing download-and-use may persist, and whether an explicit
 * pick of an installed model should drop a different pending download.
 */
class DownloadAdoptTest {

    private val parakeet = "parakeet-tdt-0.6b-v2-en"
    private val tiny = "tiny-q5_1"

    @Test
    fun stillPendingAndNothingElseConfiguredIsAdopted() {
        assertEquals(
            DownloadAdoptAction.ADOPT,
            downloadAdoptAction(pendingUse = parakeet, configuredId = "", modelId = parakeet),
        )
    }

    @Test
    fun stillPendingAndThisIdAlreadyConfiguredIsAdopted() {
        assertEquals(
            DownloadAdoptAction.ADOPT,
            downloadAdoptAction(pendingUse = parakeet, configuredId = parakeet, modelId = parakeet),
        )
    }

    /**
     * Replacement download: an older installed model stays configured until
     * adoption. That must not look like a newer pick.
     */
    @Test
    fun stillPendingWhileAnOlderModelRemainsConfiguredIsAdopted() {
        assertEquals(
            DownloadAdoptAction.ADOPT,
            downloadAdoptAction(pendingUse = parakeet, configuredId = tiny, modelId = parakeet),
        )
    }

    @Test
    fun aNewerPendingDownloadIsIgnored() {
        assertEquals(
            DownloadAdoptAction.IGNORE,
            downloadAdoptAction(pendingUse = tiny, configuredId = "", modelId = parakeet),
        )
        assertEquals(
            DownloadAdoptAction.IGNORE,
            downloadAdoptAction(pendingUse = null, configuredId = "", modelId = parakeet),
        )
    }

    @Test
    fun pickingAnInstalledModelClearsADifferentPendingUse() {
        assertEquals(parakeet, pendingUseToClearOnSelect(pendingUse = parakeet, selectedId = tiny))
        assertNull(pendingUseToClearOnSelect(pendingUse = tiny, selectedId = tiny))
        assertNull(pendingUseToClearOnSelect(pendingUse = null, selectedId = tiny))
    }
}
