package com.vocahq.vocaphone.local

import com.vocahq.vocaphone.core.TranscriptionLanguage
import java.util.Locale

/** The small set of practical choices that can change a first-run model. */
enum class ModelGuidancePriority(
    val title: String,
    val detail: String,
) {
    BALANCED(
        title = "Let VocaPhone decide",
        detail = "A balanced match for this phone and your language.",
    ),
    LIGHTER(
        title = "Keep it light",
        detail = "Prefer the smallest compatible download.",
    ),
    QUALITY(
        title = "Prioritize quality",
        detail = "Use measured accuracy when available; otherwise keep a balanced match.",
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
            ModelGuidancePriority.QUALITY -> balanced
        }

        val reason = when (intent.priority) {
            ModelGuidancePriority.BALANCED ->
                "A balanced match that fits this phone and covers ${guidanceLanguageName(language)}."
            ModelGuidancePriority.LIGHTER ->
                "The smallest compatible download that covers ${guidanceLanguageName(language)}."
            ModelGuidancePriority.QUALITY ->
                "Language-specific accuracy comparisons are not available yet, so we kept the balanced match."
        }
        return ModelGuidanceResult(
            model = selected,
            intent = normalized,
            confidence = ModelGuidanceConfidence.GOOD_DEFAULT,
            reason = reason,
        )
    }
}

private fun guidanceLanguageName(code: String): String {
    val known = TranscriptionLanguage.entries.firstOrNull { it.wireValue == code }
    if (known != null) return known.displayName
    return Locale.forLanguageTag(code).getDisplayLanguage(Locale.getDefault())
        .ifBlank { code.uppercase(Locale.ROOT) }
}
