package com.vocahq.vocaphone.local

import com.vocahq.vocaphone.core.TranscriptionLanguage
import java.util.Locale

/** The small set of practical choices that can change a first-run model. */
enum class ModelGuidancePriority(
    val title: String,
    val detail: String,
) {
    BALANCED(
        title = "Balanced",
        detail = "The best all-round match for this phone and your language.",
    ),
    LIGHTER(
        title = "Smallest download",
        detail = "Least data and storage. Best on mobile data.",
    ),
    QUALITY(
        title = "Best accuracy",
        detail = "The most capable model this phone can run. Larger download.",
    ),
}

data class ModelGuidanceIntent(
    val language: String,
    val priority: ModelGuidancePriority = ModelGuidancePriority.BALANCED,
)

enum class ModelGuidanceConfidence {
    GOOD_DEFAULT,
    NO_MATCH,
}

data class ModelGuidanceResult(
    val model: LocalModelDescriptor?,
    val intent: ModelGuidanceIntent,
    val confidence: ModelGuidanceConfidence,
    val reason: String,
) {
    val languageName: String
        get() = guidanceLanguageName(intent.language)

    val isAvailable: Boolean get() = model != null

    val downloadDetail: String?
        get() = model?.let { "Works with $languageName · ${it.sizeLabel} download" }
}

/**
 * A pure, platform-local decision layer for onboarding.
 *
 * The catalog remains the source of truth for runtime and language support. The
 * guidance layer only changes ordering; it cannot return a model that fails the
 * existing device/runtime checks.
 */
object ModelGuidance {
    fun recommend(
        profile: DeviceProfile,
        intent: ModelGuidanceIntent,
    ): ModelGuidanceResult {
        val language = intent.language.lowercase(Locale.ROOT).let {
            if (it.isBlank() || it == TranscriptionLanguage.AUTOMATIC.wireValue) {
                profile.language.lowercase(Locale.ROOT)
            } else {
                it
            }
        }
        val candidates = LocalModelCatalog.all
            .filter {
                LocalModelCatalog.isUsableOnDevice(
                    it,
                    profile.totalRamGB,
                    profile.sherpaAvailable,
                ) && profile.fits(it) && it.coversLanguage(language)
            }

        if (candidates.isEmpty()) {
            return ModelGuidanceResult(
                model = null,
                intent = intent.copy(language = language),
                confidence = ModelGuidanceConfidence.NO_MATCH,
                reason = "No on-device model in this build supports ${guidanceLanguageName(language)} on this phone.",
            )
        }

        val normalized = intent.copy(language = language)
        val rankingProfile = profile.copy(language = language)
        val balanced = LocalModelCatalog.recommended(rankingProfile).takeIf { it in candidates }
            ?: candidates.sortedWith(
                compareByDescending<LocalModelDescriptor> { scoreModel(it, rankingProfile) }
                    .thenBy { it.sizeBytes }
                    .thenBy { it.minimumRamGB }
                    .thenBy { it.id },
            ).first()
        val selected = when (intent.priority) {
            ModelGuidancePriority.BALANCED -> balanced
            ModelGuidancePriority.LIGHTER ->
                candidates.minWith(compareBy<LocalModelDescriptor> { it.sizeBytes }
                    .thenBy { it.minimumRamGB }
                    .thenBy { it.id })
            ModelGuidancePriority.QUALITY ->
                languageSpecialist(candidates, language)
                    ?: candidates.maxWith(
                        compareBy<LocalModelDescriptor> { qualityScore(it, rankingProfile) }
                            .thenBy { it.sizeBytes }
                            .thenByDescending { it.id },
                    )
        }

        val languageName = guidanceLanguageName(language)
        val reason = when (intent.priority) {
            ModelGuidancePriority.BALANCED ->
                "A balanced match that fits this phone and covers $languageName."
            ModelGuidancePriority.LIGHTER ->
                "The smallest compatible download that covers $languageName."
            ModelGuidancePriority.QUALITY ->
                if (selected.id == balanced.id) {
                    "The balanced match is already the most capable model this phone can run for $languageName."
                } else {
                    "The most capable model this phone can run for $languageName. " +
                        "Bigger download than the balanced match."
                }
        }
        return ModelGuidanceResult(
            model = selected,
            intent = normalized,
            confidence = ModelGuidanceConfidence.GOOD_DEFAULT,
            reason = reason,
        )
    }
}

/**
 * The model trained on this one language, when the catalog has one.
 *
 * [scoreModel] ranks by family and by how close a Whisper build sits to the
 * class this tier wants, and on a phone with no declared performance class that
 * put a quantized Whisper base above GigaAM for Russian — a 74M generic encoder
 * chosen over one trained on Russian alone, offered to the user as the accurate
 * answer. The scores cannot see that difference, so the catalog's own statement
 * about the language is consulted first.
 *
 * English is excluded deliberately: its starter is the *tiny* checkpoint, a
 * compactness choice, and the general ranking already has a rich set of
 * English-only models to choose the accurate one from.
 */
private fun languageSpecialist(
    candidates: List<LocalModelDescriptor>,
    language: String,
): LocalModelDescriptor? {
    if (language.equals("en", ignoreCase = true)) return null
    val starter = LocalModelCatalog.starterForLanguage(language) ?: return null
    return candidates.firstOrNull { it.id == starter.id }
}

/**
 * How good a model is likely to be when download size is not the constraint.
 *
 * [scoreModel] deliberately penalizes anything over 500 MB, because a first-run
 * default has to finish on a phone radio. Someone who explicitly asked for
 * accuracy has answered that question themselves, so the penalty is added back
 * here and nowhere else. Everything else about the score — the family ranking,
 * the tier-aware Whisper class, the language match — still applies, and
 * [DeviceProfile.fits] has already excluded anything this phone cannot run.
 */
private fun qualityScore(model: LocalModelDescriptor, profile: DeviceProfile): Int {
    val base = scoreModel(model, profile)
    if (base == Int.MIN_VALUE) return base
    return base + if (model.sizeBytes > 500_000_000L) 50 else 0
}

private fun guidanceLanguageName(code: String): String {
    val known = TranscriptionLanguage.entries.firstOrNull { it.wireValue == code }
    if (known != null) return known.displayName
    return Locale.forLanguageTag(code).getDisplayLanguage(Locale.getDefault())
        .ifBlank { code.uppercase(Locale.ROOT) }
}
