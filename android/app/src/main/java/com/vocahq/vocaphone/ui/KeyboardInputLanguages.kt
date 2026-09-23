package com.vocahq.vocaphone.ui

import android.content.Context
import android.view.inputmethod.InputMethodManager

/**
 * Enabled system keyboards, in the order the user enabled them.
 *
 * App-only. [InputMethodManager.getEnabledInputMethodSubtypeList] is a
 * settings snapshot, not something the keyboard should query on every frame.
 * [com.vocahq.vocaphone.local.LocalModelCatalog] is compiled into the IME,
 * which must not ship this call.
 *
 * The answer genuinely changes only when the user edits their keyboards,
 * which they can only do in Settings, so the snapshot is retaken when a
 * screen opens and when the app comes back.
 */
object KeyboardInputLanguages {
    @Volatile
    private var held: List<String>? = null

    fun enabledPrimaryLanguages(context: Context): List<String> {
        val imm = context.applicationContext
            .getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            ?: return emptyList()
        val methods = imm.enabledInputMethodList
        val tags = LinkedHashSet<String>()
        for (imi in methods) {
            val subtypes = imm.getEnabledInputMethodSubtypeList(imi, true)
            for (subtype in subtypes) {
                if (subtype.isAuxiliary) continue
                if (subtype.mode == "voice") continue
                @Suppress("DEPRECATION")
                val tag = subtype.languageTag.ifBlank { subtype.locale }
                if (tag.isNotBlank()) tags.add(tag)
            }
        }
        return tags.toList()
    }

    fun snapshot(context: Context): List<String> {
        held?.let { return it }
        val fresh = enabledPrimaryLanguages(context)
        held = fresh
        return fresh
    }

    fun refresh(context: Context) {
        held = enabledPrimaryLanguages(context)
    }
}
