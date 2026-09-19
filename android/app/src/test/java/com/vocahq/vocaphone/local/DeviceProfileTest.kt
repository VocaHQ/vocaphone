package com.vocahq.vocaphone.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class DeviceProfileTest {

    private fun profile(
        ram: Long,
        cores: Int = 8,
        mpc: Int = 0,
        abi: String = "arm64-v8a",
        khz: Int = 0,
        sherpa: Boolean = true,
        language: String = "en",
    ) = DeviceProfile(ram, cores, mpc, abi, khz, sherpa, language)

    @Test
    fun `tiers follow ram, abi, clock and performance class`() {
        assertEquals(DeviceTier.CONSTRAINED, profile(3).tier)
        assertEquals(DeviceTier.CONSTRAINED, profile(8, abi = "armeabi-v7a").tier)
        assertEquals(DeviceTier.STANDARD, profile(4).tier)
        assertEquals(DeviceTier.FAST, profile(8).tier)
        assertEquals(DeviceTier.FAST, profile(6, mpc = 31).tier)
        assertEquals(DeviceTier.FAST, profile(6, cores = 8, khz = 2_800_000).tier)
        assertEquals(DeviceTier.FLAGSHIP, profile(16).tier)
        assertEquals(DeviceTier.FLAGSHIP, profile(12, mpc = 34).tier)
        assertEquals(DeviceTier.FLAGSHIP, profile(12, cores = 8, khz = 3_200_000).tier)
    }

    @Test
    fun `budget leaves headroom on fast and flagship phones`() {
        assertEquals(3, profile(3).modelRamBudgetGB)
        // 4 GB is the whole point: it is what the int8 0.6B Parakeet needs.
        assertEquals(4, profile(4).modelRamBudgetGB)
        assertEquals(7, profile(8).modelRamBudgetGB)
        assertEquals(14, profile(16).modelRamBudgetGB)
        assertEquals(18, profile(20).modelRamBudgetGB)
    }

    @Test
    fun `english leads with parakeet wherever the budget holds it`() {
        for (ram in listOf(4L, 8L, 16L, 20L)) {
            val pick = LocalModelCatalog.recommended(profile(ram, language = "en"))
            assertEquals("parakeet-tdt-0.6b-v2-en", pick.id)
            assertTrue(pick.minimumRamGB <= profile(ram).modelRamBudgetGB)
        }
    }

    /**
     * The list is what makes a 670 MB default acceptable: someone on mobile
     * data can see a 30 MB answer to the same question without going hunting
     * through the catalog.
     */
    @Test
    fun `english picks cover accuracy, breadth and a small download`() {
        val picks = LocalModelCatalog.recommendations(profile(8, language = "en"))
        assertEquals(
            listOf(ModelPickRole.ENGLISH, ModelPickRole.MULTILINGUAL, ModelPickRole.COMPACT),
            picks.map { it.role },
        )
        assertEquals("parakeet-tdt-0.6b-v2-en", picks[0].model.id)
        assertEquals("parakeet-tdt-0.6b-v3", picks[1].model.id)
        assertTrue(picks[2].model.sizeBytes < 100_000_000L)
    }

    @Test
    fun `a regional language leads with its own specialist and still offers the rest`() {
        val picks = LocalModelCatalog.recommendations(profile(8, language = "ru"))
        assertEquals("giga-am-ctc-ru", picks[0].model.id)
        assertEquals(ModelPickRole.REGIONAL, picks[0].role)
        // The multilingual and English answers stay on offer next to it.
        assertEquals("parakeet-tdt-0.6b-v3", picks[1].model.id)
        assertEquals("parakeet-tdt-0.6b-v2-en", picks[2].model.id)
        assertTrue(picks.size in 3..4)
        picks.forEach { assertTrue(profile(8).fits(it.model)) }
    }

    @Test
    fun `every pick covers the phone language and fits the budget`() {
        for (language in listOf("en", "de", "hi", "zh", "yue", "ja", "ru", "it")) {
            for (ram in listOf(3L, 4L, 8L, 16L)) {
                val device = profile(ram, language = language)
                val picks = LocalModelCatalog.recommendations(device)
                assertTrue("no picks for $language on $ram GB", picks.isNotEmpty())
                assertEquals(picks.map { it.model.id }.distinct().size, picks.size)
                picks.forEach { pick ->
                    assertTrue(
                        "${pick.model.id} does not fit $ram GB",
                        device.fits(pick.model),
                    )
                }
                assertTrue(picks.first().model.coversLanguage(language))
            }
        }
    }

    @Test
    fun `the default follows the phone language`() {
        // Languages with a compact specialist keep it as the lead pick.
        assertEquals(
            "canary-180m-flash",
            LocalModelCatalog.recommended(profile(8, language = "de")).id,
        )
        assertEquals(
            "canary-180m-flash",
            LocalModelCatalog.recommended(profile(8, language = "es")).id,
        )
        assertEquals(
            "canary-180m-flash",
            LocalModelCatalog.recommended(profile(8, language = "fr")).id,
        )
        assertEquals(
            "paraformer-zh-small",
            LocalModelCatalog.recommended(profile(8, language = "zh")).id,
        )
        // Paraformer is Mandarin and English only, so Cantonese gets SenseVoice.
        assertEquals(
            "sense-voice",
            LocalModelCatalog.recommended(profile(8, language = "yue")).id,
        )
        assertEquals(
            "sense-voice",
            LocalModelCatalog.recommended(profile(8, language = "ja")).id,
        )
        assertEquals(
            "sense-voice",
            LocalModelCatalog.recommended(profile(8, language = "ko")).id,
        )
        assertEquals(
            "giga-am-ctc-ru",
            LocalModelCatalog.recommended(profile(8, language = "ru")).id,
        )
        assertEquals(
            "dolphin-base-ctc",
            LocalModelCatalog.recommended(profile(8, language = "hi")).id,
        )
        // Italian has no specialist in the catalog, so the widest multilingual
        // model that covers it leads instead of a small Whisper.
        val italian = LocalModelCatalog.recommended(profile(8, language = "it"))
        assertEquals("parakeet-tdt-0.6b-v3", italian.id)
        assertTrue(italian.coversLanguage("it"))
    }

    @Test
    fun `four GB sherpa phone reaches parakeet rather than a tiny default`() {
        val pick = LocalModelCatalog.recommended(profile(4))
        assertEquals(SherpaFamily.NEMO_TRANSDUCER, pick.sherpaFamily)
        assertTrue(pick.minimumRamGB <= profile(4).modelRamBudgetGB)
    }

    @Test
    fun `without sherpa the class picks a matching whisper, not the largest file`() {
        // English leads, so these are the English builds of the same class.
        assertEquals(
            "tiny.en-q5_1",
            LocalModelCatalog.recommended(profile(2, sherpa = false)).id,
        )
        assertEquals(
            "base.en-q5_1",
            LocalModelCatalog.recommended(profile(4, sherpa = false)).id,
        )
        assertEquals(
            "base.en-q5_1",
            LocalModelCatalog.recommended(profile(8, mpc = 0, sherpa = false)).id,
        )
        // Large v3 / turbo stays in the catalog with a slow-on-phone mark.
        // First-run must not start a 1.6 GB download even on a flagship.
        val flagship = LocalModelCatalog.recommended(profile(12, mpc = 34, sherpa = false))
        assertEquals(LocalModelEngine.WHISPER, flagship.engine)
        assertTrue(flagship.sizeBytes < 600_000_000L)
        assertTrue(!flagship.id.contains("large"))
        assertEquals(
            "small.en-q5_1",
            LocalModelCatalog.recommended(profile(16, sherpa = false)).id,
        )
        assertEquals(
            "small-q5_1",
            LocalModelCatalog.recommended(profile(16, sherpa = false, language = "it")).id,
        )
    }

    @Test
    fun `poco class hardware stays on base whisper without a sherpa runtime`() {
        val pocoF1 = profile(
            ram = 6,
            cores = 8,
            mpc = 0,
            khz = 2_800_000,
            sherpa = false,
        )
        assertEquals(DeviceTier.FAST, pocoF1.tier)
        assertEquals("base-q5_1", LocalModelCatalog.recommendedWhisper(pocoF1).id)
        assertEquals("base.en-q5_1", LocalModelCatalog.recommended(pocoF1).id)
    }

    @Test
    fun `constrained sherpa stays on a small family that fits the budget`() {
        val pick = LocalModelCatalog.recommended(profile(3))
        assertEquals(SherpaFamily.MOONSHINE, pick.sherpaFamily)
        assertTrue(pick.minimumRamGB <= 3)
    }

    @Test
    fun `32-bit stays inside the constrained budget`() {
        val pick = LocalModelCatalog.recommended(profile(8, abi = "armeabi-v7a"))
        assertTrue(pick.minimumRamGB <= 3)
        assertTrue(pick.engine == LocalModelEngine.WHISPER || pick.minimumRamGB <= 3)
    }

    @Test
    fun `sysfs max frequency uses the fastest core`() {
        val root = kotlin.io.path.createTempDirectory("cpu").toFile()
        try {
            File(root, "cpu0/cpufreq").mkdirs()
            File(root, "cpu1/cpufreq").mkdirs()
            File(root, "cpu0/cpufreq/cpuinfo_max_freq").writeText("1804800\n")
            File(root, "cpu1/cpufreq/cpuinfo_max_freq").writeText("2803200\n")
            assertEquals(2_803_200, readMaxCpuKHz(root))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `summary names the class and the budget`() {
        val text = profile(8, cores = 8, khz = 2_800_000).summary()
        assertTrue(text.contains("Fast class"))
        assertTrue(text.contains("8 GB RAM"))
        assertTrue(text.contains("8 cores"))
        assertTrue(text.contains("2.8 GHz"))
        assertTrue(text.contains("7 GB model budget"))
    }

    @Test
    fun twoKeyboardLanguagesLeadWithTheModelThatCoversBoth() {
        val spoken = LocalModelCatalog.spokenLanguages(
            device = "en",
            keyboards = listOf("en-US", "ru-RU"),
        )
        assertEquals(listOf("en", "ru"), spoken)
        val picks = LocalModelCatalog.recommendations(profile(8), spoken)
        assertEquals(ModelPickRole.MULTILINGUAL, picks[0].role)
        assertTrue(picks[0].model.coversLanguage("en"))
        assertTrue(picks[0].model.coversLanguage("ru"))
        assertTrue(picks.any { it.model.id == "giga-am-ctc-ru" })
        assertTrue(picks.any { it.model.id == "parakeet-tdt-0.6b-v2-en" })
    }

    @Test
    fun aSingleKeyboardLanguageStillLeadsWithItsSpecialist() {
        val spoken = LocalModelCatalog.spokenLanguages(device = "en", keyboards = listOf("ru-RU"))
        assertEquals(listOf("ru"), spoken)
        val picks = LocalModelCatalog.recommendations(profile(8), spoken)
        assertEquals(ModelPickRole.REGIONAL, picks[0].role)
        assertEquals("giga-am-ctc-ru", picks[0].model.id)
    }

    @Test
    fun everyEnabledKeyboardLanguageIsInTheSpokenList() {
        val spoken = LocalModelCatalog.spokenLanguages(
            device = "en",
            keyboards = listOf("en-US", "zh-Hans", "ja", "ko", "ru-RU"),
        )
        assertEquals(listOf("en", "zh", "ja", "ko", "ru"), spoken)
        val picks = LocalModelCatalog.recommendations(profile(8), spoken)
        assertTrue(picks.any { it.model.id == "giga-am-ctc-ru" })
        assertTrue(picks.any { it.model.id == "paraformer-zh-small" })
        assertTrue(picks.any { it.model.id == "sense-voice" })
    }

    @Test
    fun noChineseModelWithoutAChineseKeyboard() {
        val spoken = LocalModelCatalog.spokenLanguages(
            device = "en",
            keyboards = listOf("en-US", "ru-RU"),
        )
        assertEquals(listOf("en", "ru"), spoken)
        val picks = LocalModelCatalog.recommendations(profile(8), spoken)
        assertTrue(picks.none { it.model.id == "paraformer-zh-small" })
        assertTrue(picks.none { it.model.id == "sense-voice" })
    }

    @Test
    fun deviceLanguageDoesNotInventAKeyboard() {
        val spoken = LocalModelCatalog.spokenLanguages(
            device = "zh",
            keyboards = listOf("en-US", "ru-RU"),
        )
        assertEquals(listOf("en", "ru"), spoken)
        assertFalse(spoken.contains("zh"))
        val picks = LocalModelCatalog.recommendations(profile(8), spoken)
        assertTrue(picks.any { it.model.id == "giga-am-ctc-ru" })
        assertTrue(picks.none { it.model.id == "paraformer-zh-small" })
        assertTrue(picks.none { it.model.id == "sense-voice" })
    }

    @Test
    fun extraDeviceLocalesAreNotSpokenWithoutAKeyboard() {
        val spoken = LocalModelCatalog.spokenLanguages(
            device = "en",
            keyboards = listOf("en-US"),
        )
        assertEquals(listOf("en"), spoken)
        assertFalse(spoken.contains("hi"))
    }

    @Test
    fun emptyKeyboardsFallBackToThePhoneLanguage() {
        val spoken = LocalModelCatalog.spokenLanguages(
            device = "ru",
            keyboards = listOf("emoji", "com.vocahq.vocaphone.keyboard"),
        )
        assertEquals(listOf("ru"), spoken)
    }

    @Test
    fun normalizedLanguageCodeSkipsEmojiDictationAndBundles() {
        assertEquals(null, LocalModelCatalog.normalizedLanguageCode("emoji"))
        assertEquals(null, LocalModelCatalog.normalizedLanguageCode("dictation"))
        assertEquals(null, LocalModelCatalog.normalizedLanguageCode("com.vocahq.vocaphone.keyboard"))
        assertEquals("ru", LocalModelCatalog.normalizedLanguageCode("ru_RU@sw=Russian"))
        assertEquals("zh", LocalModelCatalog.normalizedLanguageCode("zh-Hans"))
        assertEquals("yue", LocalModelCatalog.normalizedLanguageCode("yue-Hant"))
        assertEquals("en", LocalModelCatalog.normalizedLanguageCode("en-US"))
        assertEquals(null, LocalModelCatalog.normalizedLanguageCode("auto"))
        assertEquals("tl", LocalModelCatalog.normalizedLanguageCode("fil"))
        assertEquals("tl", LocalModelCatalog.normalizedLanguageCode("fil-PH"))
        val spoken = LocalModelCatalog.spokenLanguages(
            device = "en",
            keyboards = listOf("fil-PH"),
        )
        assertTrue(spoken.contains("tl"))
    }

    @Test
    fun multilingualPickCoversEveryLanguageOnThePhone() {
        val pick = LocalModelCatalog.bestMultilingual(profile(8), listOf("ru", "en"))
        assertEquals("parakeet-tdt-0.6b-v3", pick?.id)
        assertTrue(pick!!.coversLanguage("en"))
        assertTrue(pick.coversLanguage("ru"))
    }

    @Test
    fun anEnglishPhoneWithAnIndicKeyboardLeadsWithTheBestEnglishModel() {
        val spoken = LocalModelCatalog.spokenLanguages(
            device = "en",
            keyboards = listOf("hi-IN", "en-IN"),
        )
        assertEquals(listOf("en", "hi"), spoken)
        val picks = LocalModelCatalog.recommendations(profile(6), spoken)
        assertEquals("parakeet-tdt-0.6b-v2-en", picks[0].model.id)
        assertTrue(picks.any { it.model.coversLanguage("hi") })
    }

    @Test
    fun thePhoneLanguageLeadsTheKeyboardsItIsOn() {
        assertEquals(
            listOf("en", "hi"),
            LocalModelCatalog.spokenLanguages(device = "en", keyboards = listOf("hi-IN", "en-US")),
        )
        assertEquals(
            listOf("hi"),
            LocalModelCatalog.spokenLanguages(device = "en", keyboards = listOf("hi-IN")),
        )
    }

    @Test
    fun onboardingOffersOnlyTheMostAccurateModels() {
        val picks = LocalModelCatalog.onboardingRecommendations(profile(6), listOf("en"))
        assertEquals(
            listOf("parakeet-tdt-0.6b-v2-en", "parakeet-tdt-0.6b-v3", "canary-180m-flash"),
            picks.map { it.model.id },
        )
        assertTrue(picks.all { LocalModelCatalog.isHighAccuracy(it.model, "en") })
    }

    @Test
    fun onboardingKeepsTheBestModelForASecondKeyboard() {
        val picks = LocalModelCatalog.onboardingRecommendations(
            profile(6),
            listOf("en", "hi"),
        )
        assertEquals(3, picks.size)
        assertEquals("parakeet-tdt-0.6b-v2-en", picks[0].model.id)
        assertTrue(picks.any { it.model.id == "dolphin-small-ctc" })
        assertTrue(picks.none { it.model.id == "dolphin-base-ctc" })
    }

    @Test
    fun onboardingFallsBackWhenNothingAccurateFits() {
        val picks = LocalModelCatalog.onboardingRecommendations(profile(1), listOf("en"))
        assertTrue(picks.isNotEmpty())
    }

    @Test
    fun theSmallestChoiceIsOrderedBySizeForTheLanguage() {
        val picks = LocalModelCatalog.onboardingRecommendations(
            profile(6),
            listOf("en"),
            priority = ModelGuidancePriority.LIGHTER,
        )
        assertEquals(3, picks.size)
        assertEquals(picks.map { it.model.sizeBytes }.sorted(), picks.map { it.model.sizeBytes })
        assertTrue(picks.none { it.model.id == "paraformer-zh-small" })
        assertTrue(picks.none { it.model.id.startsWith("dolphin") })
    }

    @Test
    fun theManyLanguagesChoiceNeverOffersAnEnglishOnlyModel() {
        val picks = LocalModelCatalog.onboardingRecommendations(
            profile(6),
            listOf("en", "de"),
            priority = ModelGuidancePriority.MULTILINGUAL,
        )
        assertTrue(picks.isNotEmpty())
        assertTrue(picks.all { !it.model.englishOnly })
        assertTrue(picks[0].model.coversLanguage("en") && picks[0].model.coversLanguage("de"))
    }

    @Test
    fun spokenLanguagesUsesDeviceWhenItIsNotEnglish() {
        val spoken = LocalModelCatalog.spokenLanguages(
            device = "ru",
            keyboards = listOf("ru-RU", "en-US"),
        )
        assertEquals("ru", spoken.first())
        assertTrue(spoken.contains("en"))
    }

    @Test
    fun currentProfileLeadsWithSpokenLanguages() {
        val device = DeviceProfile.current(
            totalRamGB = 8,
            sherpaAvailable = true,
            keyboards = listOf("hi-IN", "en-US"),
            deviceLanguage = "en",
        )
        assertEquals("en", device.language)
        assertEquals(listOf("en", "hi"), device.languages)
        val russianOnly = DeviceProfile.current(
            totalRamGB = 8,
            sherpaAvailable = true,
            keyboards = listOf("ru-RU"),
            deviceLanguage = "en",
        )
        assertEquals("ru", russianOnly.language)
        assertEquals(listOf("ru"), russianOnly.languages)
    }
}
