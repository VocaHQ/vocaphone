package com.vocahq.vocaphone.ui

/** Page position never grants a requirement; live system state gates every action. */
internal enum class SetupPage(val title: String, val detail: String, private val step: SetupStep?) {
    WELCOME("Dictate into any app", "A keyboard that types what you say.", null),
    KEYBOARD("Make room for your voice", "Start by enabling VocaPhone and choosing it as your keyboard.", SetupStep.KEYBOARD),
    MICROPHONE("Let your voice do the typing", "Allow microphone access so VocaPhone can hear your dictation.", SetupStep.MICROPHONE),
    NOTIFICATIONS("Know when you're recording", "Allow notifications to keep recording controls within reach.", SetupStep.NOTIFICATIONS),
    SOURCE("Choose where speech becomes text", "Download a model to dictate on this phone, or connect a gateway you run.", SetupStep.GATEWAY),
    READY("You're ready to dictate", "Your keyboard, permissions, and speech-to-text source are ready.", null);

    fun isSatisfied(status: SetupStatus, welcomeAcknowledged: Boolean = false): Boolean = when (this) {
        WELCOME -> welcomeAcknowledged
        READY -> status.isReadyToDictate
        else -> status.isSatisfied(requireNotNull(step))
    }

    /**
     * Step back one page. Once the how-it-works welcome is acknowledged, it is
     * not in the back path: returning users must not land on WELCOME again.
     */
    fun previous(welcomeAcknowledged: Boolean = false): SetupPage {
        val prev = entries[(ordinal - 1).coerceAtLeast(0)]
        return if (prev == WELCOME && welcomeAcknowledged) this else prev
    }

    fun next(): SetupPage = entries[(ordinal + 1).coerceAtMost(entries.lastIndex)]

    companion object {
        fun resume(status: SetupStatus, welcomeAcknowledged: Boolean = true): SetupPage =
            entries.firstOrNull { !it.isSatisfied(status, welcomeAcknowledged) } ?: READY

        /**
         * Page after the motion intro on a cold start: show the one-shot welcome
         * until it is persisted, otherwise the first unmet requirement.
         */
        fun afterIntro(status: SetupStatus, welcomeSeen: Boolean): SetupPage =
            if (welcomeSeen) resume(status, welcomeAcknowledged = true) else WELCOME
    }
}
