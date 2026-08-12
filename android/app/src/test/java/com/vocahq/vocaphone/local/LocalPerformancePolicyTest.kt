package com.vocahq.vocaphone.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalPerformancePolicyTest {

    @Test
    fun `whisper worker count is capped for sustained phone inference`() {
        assertEquals(2, WhisperCpuConfig.whisperThreadCount(4))
        assertEquals(4, WhisperCpuConfig.whisperThreadCount(8))
        assertEquals(4, WhisperCpuConfig.whisperThreadCount(16))
    }

    @Test
    fun `older high ram phone receives a conservative recommendation`() {
        assertEquals("small-q5_1", LocalModelCatalog.recommended(8, 0).id)
        assertTrue(
            LocalModelCatalog.usableOnDevice(8).any { it.id == "large-v3-turbo-q5_0" },
        )
    }

    @Test
    fun `large models remain recommendations for declared performance class devices`() {
        assertEquals("large-v3-turbo-q5_0", LocalModelCatalog.recommended(8, 31).id)
        assertEquals("large-v3-turbo", LocalModelCatalog.recommended(12, 34).id)
    }
}
