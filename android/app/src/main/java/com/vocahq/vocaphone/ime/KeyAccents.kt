package com.vocahq.vocaphone.ime

import java.util.Locale

internal object KeyAccents {
    private val variants = mapOf(
        "a" to listOf("à", "á", "â", "ä", "æ", "ã", "å", "ā"),
        "c" to listOf("ç", "ć", "č"),
        "d" to listOf("ð"),
        "e" to listOf("è", "é", "ê", "ë", "ē", "ė", "ę"),
        "g" to listOf("ğ"),
        "i" to listOf("ì", "í", "î", "ï", "ī", "į"),
        "l" to listOf("ł"),
        "n" to listOf("ñ", "ń"),
        "o" to listOf("ò", "ó", "ô", "ö", "õ", "ø", "ō", "œ"),
        "s" to listOf("ß", "ś", "š"),
        "u" to listOf("ù", "ú", "û", "ü", "ū"),
        "y" to listOf("ý", "ÿ"),
        "z" to listOf("ž", "ź", "ż"),
        "'" to listOf("'", "‘", "’", "‚"),
        "\"" to listOf("\"", "“", "”", "„"),
        "-" to listOf("-", "–", "—", "•"),
        "." to listOf(".", "…"),
        "?" to listOf("?", "¿"),
        "!" to listOf("!", "¡"),
        "$" to listOf("$", "€", "£", "¥", "₹", "₩"),
    )

    fun forKey(key: KeyboardKey, shift: ShiftState): List<String> {
        if (key.type != KeyboardKeyType.CHARACTER) return emptyList()
        val base = key.output.lowercase(Locale.ROOT)
        val options = variants[base] ?: return emptyList()
        if (shift == ShiftState.OFF) return options
        return options.map { it.uppercase(Locale.ROOT) }
    }
}
