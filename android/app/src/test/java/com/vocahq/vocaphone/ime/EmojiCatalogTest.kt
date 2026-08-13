package com.vocahq.vocaphone.ime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EmojiCatalogTest {

    private val catalog = EmojiCatalog.parse(
        sequenceOf(
            "😀\tsmileys\tgrinning face smile happy",
            "😂\tsmileys\tface tears joy laugh",
            "👍\tpeople\tthumbs up yes",
            "🚀\ttravel\trocket space launch",
        ),
    )

    @Test
    fun `search matches keywords and keeps category filters`() {
        assertEquals(listOf("😂"), EmojiCatalog.search(catalog, "laugh").map { it.glyph })
        assertEquals(listOf("🚀"), EmojiCatalog.search(catalog, "rock").map { it.glyph })
        assertEquals(
            listOf("😀", "😂"),
            EmojiCatalog.inCategory(catalog, EmojiCategory.SMILEYS).map { it.glyph },
        )
        assertTrue(EmojiCatalog.search(catalog, "nope").isEmpty())
    }
}
