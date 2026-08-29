package com.vocahq.vocaphone.core

import org.junit.Assert.assertEquals
import org.junit.Test

class SnippetExpanderTest {

    @Test
    fun `no snippets leaves text unchanged`() {
        assertEquals(
            "call me later",
            SnippetExpander.expand("call me later", emptyList()),
        )
    }

    @Test
    fun `blank triggers are ignored`() {
        val snippets = listOf(Snippet("1", "   ", "nope"))
        assertEquals("hello world", SnippetExpander.expand("hello world", snippets))
    }

    @Test
    fun `matching is case insensitive`() {
        val snippets = listOf(Snippet("1", "brb", "be right back"))
        assertEquals(
            "be right back, getting coffee",
            SnippetExpander.expand("BRB, getting coffee", snippets),
        )
    }

    @Test
    fun `word triggers only match on word boundaries`() {
        val snippets = listOf(Snippet("1", "addr", "123 Main St"))
        assertEquals(
            "send to 123 Main St please",
            SnippetExpander.expand("send to addr please", snippets),
        )
        // "address" contains "addr" but is not a boundary match.
        assertEquals(
            "my address is unlisted",
            SnippetExpander.expand("my address is unlisted", snippets),
        )
    }

    @Test
    fun `punctuation only triggers match at string edges`() {
        val snippets = listOf(Snippet("1", "->", "results in"))
        assertEquals(
            "results in success",
            SnippetExpander.expand("-> success", snippets),
        )
        assertEquals(
            "a results in b",
            SnippetExpander.expand("a -> b", snippets),
        )
    }

    @Test
    fun `longer trigger wins over a shorter substring trigger`() {
        val snippets = listOf(
            Snippet("1", "email", "user@example.com"),
            Snippet("2", "my email", "kanishk@example.com"),
        )
        assertEquals(
            "send to kanishk@example.com now",
            SnippetExpander.expand("send to my email now", snippets),
        )
    }

    @Test
    fun `an expansion containing another trigger is not re-expanded`() {
        val snippets = listOf(
            Snippet("1", "sig", "Thanks, my email is contact"),
            Snippet("2", "contact", "hello@example.com"),
        )
        assertEquals(
            "Thanks, my email is contact",
            SnippetExpander.expand("sig", snippets),
        )
    }

    @Test
    fun `multiple matches are replaced without shifting earlier ranges`() {
        val snippets = listOf(
            Snippet("1", "brb", "be right back"),
            Snippet("2", "omw", "on my way"),
        )
        assertEquals(
            "on my way, be right back soon",
            SnippetExpander.expand("omw, brb soon", snippets),
        )
    }
}
