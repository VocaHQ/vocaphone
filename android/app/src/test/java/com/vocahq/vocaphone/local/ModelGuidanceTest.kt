package com.vocahq.vocaphone.local

import com.vocahq.vocaphone.core.TranscriptionLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
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
    fun qualityPrefersTheMostCapableModelThePhoneCanRun() {
        val lighter = ModelGuidance.recommend(
            profile(8),
            ModelGuidanceIntent("en", ModelGuidancePriority.LIGHTER),
        )
        val quality = ModelGuidance.recommend(
            profile(8),
            ModelGuidanceIntent("en", ModelGuidancePriority.QUALITY),
        )

        // The whole point of the option: it has to be able to differ from the
        // smallest download, or it is a control that does nothing.
        assertNotEquals(lighter.model?.id, quality.model?.id)
        assertTrue((quality.model?.sizeBytes ?: 0) > (lighter.model?.sizeBytes ?: 0))
    }

    @Test
    fun qualityDescribesTheActualDownloadTradeoffAgainstBalanced() {
        val balanced = ModelGuidance.recommend(
            profile(8),
            ModelGuidanceIntent("en", ModelGuidancePriority.BALANCED),
        )
        val quality = ModelGuidance.recommend(
            profile(8),
            ModelGuidanceIntent("en", ModelGuidancePriority.QUALITY),
        )

        assertTrue((quality.model?.sizeBytes ?: Long.MAX_VALUE) < (balanced.model?.sizeBytes ?: 0))
        assertTrue(ModelGuidancePriority.QUALITY.detail.contains("Download size can differ"))
        assertTrue(quality.reason.contains("Smaller download than the balanced match"))
    }

    /**
     * Bigger is not better when the smaller model was trained on the one
     * language being asked for. This is the case that caught the iOS catalog
     * walking its breadth-ordered list straight past the specialist.
     */
    @Test
    fun qualityKeepsALanguageSpecialistOverAGeneralModel() {
        val quality = ModelGuidance.recommend(
            profile(8, language = "ru"),
            ModelGuidanceIntent("ru", ModelGuidancePriority.QUALITY),
        )

        assertEquals("giga-am-ctc-ru", quality.model?.id)
    }

    /**
     * The specialist rule has to hold for every language the catalog names one
     * for, not just the Russian case that exposed it.
     */
    @Test
    fun everyLanguageWithASpecialistKeepsItUnderQuality() {
        val languages = listOf("ru", "de", "es", "fr", "zh", "ja", "ko", "yue")
        languages.forEach { language ->
            val specialist = LocalModelCatalog.starterForLanguage(language)
            val quality = ModelGuidance.recommend(
                profile(8, language = language),
                ModelGuidanceIntent(language, ModelGuidancePriority.QUALITY),
            )
            assertEquals(
                "quality for $language should stay on its specialist",
                specialist?.id,
                quality.model?.id,
            )
        }
    }

    /** English is the exception: its starter is the tiny compactness pick. */
    @Test
    fun englishIsNotHeldToTheTinyStarterUnderQuality() {
        val quality = ModelGuidance.recommend(
            profile(8, language = "en"),
            ModelGuidanceIntent("en", ModelGuidancePriority.QUALITY),
        )

        assertTrue(quality.model?.id != "moonshine-tiny-en")
    }

    @Test
    fun qualityNeverReturnsAModelThePhoneCannotRun() {
        val profile = profile(3, sherpa = false)
        val quality = ModelGuidance.recommend(
            profile,
            ModelGuidanceIntent("en", ModelGuidancePriority.QUALITY),
        )

        val model = quality.model
        assertNotNull(model)
        assertTrue(profile.fits(model!!))
    }

    @Test
    fun qualitySaysSoPlainlyWhenItLandsOnTheBalancedMatch() {
        val quality = ModelGuidance.recommend(
            profile(3, sherpa = false, language = "de"),
            ModelGuidanceIntent("de", ModelGuidancePriority.QUALITY),
        )
        val balanced = ModelGuidance.recommend(
            profile(3, sherpa = false, language = "de"),
            ModelGuidanceIntent("de", ModelGuidancePriority.BALANCED),
        )

        if (quality.model?.id == balanced.model?.id) {
            assertTrue(quality.reason.contains("already the most capable"))
        } else {
            assertTrue(quality.reason.contains("most capable"))
        }
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
    fun anUnlistedPhoneLanguageIsNotMislabelledAsAutomatic() {
        val result = ModelGuidance.recommend(
            profile(8, language = "af"),
            ModelGuidanceIntent("auto"),
        )

        assertEquals("af", result.intent.language)
        assertTrue(result.languageName.isNotBlank())
        assertTrue(result.languageName != TranscriptionLanguage.AUTOMATIC.displayName)
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
