package com.vocahq.vocaphone.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelGuidanceTest {

    private fun profile(
        ram: Long,
        sherpa: Boolean = true,
        language: String = "en",
    ) = DeviceProfile(
        totalRamGB = ram,
        abi = "arm64-v8a",
        sherpaAvailable = sherpa,
        language = language,
    )

    @Test
    fun balancedKeepsTheExistingCatalogDefault() {
        val result = ModelGuidance.recommend(
            profile(8),
            ModelGuidanceIntent("en", ModelGuidancePriority.BALANCED),
        )

        assertEquals(ModelGuidanceConfidence.GOOD_DEFAULT, result.confidence)
        assertEquals("parakeet-tdt-0.6b-v2-en", result.model?.id)
        assertTrue(result.reason.contains("English"))
    }

    @Test
    fun lighterPrefersTheSmallestCompatibleDownload() {
        val result = ModelGuidance.recommend(
            profile(8, sherpa = false, language = "de"),
            ModelGuidanceIntent("de", ModelGuidancePriority.LIGHTER),
        )

        assertEquals("tiny-q5_1", result.model?.id)
        assertEquals("German", result.languageName)
        assertTrue(result.downloadDetail?.contains("32 MB") == true)
    }

    @Test
    fun qualityPreferenceStaysHonestUntilAccuracyEvidenceExists() {
        val balanced = ModelGuidance.recommend(
            profile(8),
            ModelGuidanceIntent("en", ModelGuidancePriority.BALANCED),
        )
        val quality = ModelGuidance.recommend(
            profile(8),
            ModelGuidanceIntent("en", ModelGuidancePriority.QUALITY),
        )

        assertEquals(balanced.model?.id, quality.model?.id)
        assertTrue(quality.reason.contains("not available yet"))
    }

    @Test
    fun automaticLanguageUsesTheDeviceProfile() {
        val result = ModelGuidance.recommend(
            profile(8, language = "ru"),
            ModelGuidanceIntent("auto"),
        )

        assertEquals("ru", result.intent.language)
        assertEquals("giga-am-ctc-ru", result.model?.id)
    }

    @Test
    fun explicitLanguageWinsOverTheDeviceLocale() {
        val result = ModelGuidance.recommend(
            profile(8, language = "en"),
            ModelGuidanceIntent("ru"),
        )

        assertEquals("ru", result.intent.language)
        assertTrue(result.model?.coversLanguage("ru") == true)
    }

    @Test
    fun unsupportedLanguageReportsNoMatchWhenTheBuildHasNoSuitableRuntime() {
        val result = ModelGuidance.recommend(
            profile(3, sherpa = false, language = "yue"),
            ModelGuidanceIntent("yue"),
        )

        assertEquals(ModelGuidanceConfidence.NO_MATCH, result.confidence)
        assertEquals(null, result.model)
        assertTrue(result.reason.contains("Cantonese"))
        assertNotNull(result.languageName)
    }
}
