package com.vocahq.vocaphone.local

/**
 * Whether the person agreed to the 28 MB grammar model, kept apart from the
 * switches that use it.
 *
 * "Check while typing" works without the model (the rules ship in the app),
 * and the offer to download the model can be declined, so that switch is no
 * longer an agreement to download anything. Fix grammar still is: it cannot
 * be turned on until the model is verified. The agreement itself is the
 * stored `grammar_model_requested` flag, written whenever someone starts a
 * download from any entry point and cleared when they cancel one.
 */
internal object GrammarModelConsent {
    /**
     * The flag as app start leaves it. A [stored] value, true or false, is
     * kept. Someone whose build never wrote one agreed if Fix grammar is on or
     * any grammar model (this build's, a retired one, or a part-way download)
     * is on the phone, because only an agreed download puts files there. This
     * runs before the retired folders are swept and is then stored, so an
     * upgrade download that fails offline is still retried on later starts.
     */
    fun requested(stored: Boolean?, fixGrammar: Boolean, filesOnPhone: Boolean): Boolean =
        stored ?: (fixGrammar || filesOnPhone)

    /**
     * Whether app start may fetch the model when its files are missing. Fix
     * grammar counts on its own because it does nothing without the model;
     * "Check while typing" does not count at all.
     */
    fun wanted(available: Boolean, requested: Boolean, fixGrammar: Boolean): Boolean =
        available && (requested || fixGrammar)
}
