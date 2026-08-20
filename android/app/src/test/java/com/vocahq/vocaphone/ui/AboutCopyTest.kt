package com.vocahq.vocaphone.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AboutCopyTest {

    @Test
    fun `source links point at the org, the repo, and a new issue`() {
        assertEquals("https://github.com/VocaHQ", ORG_URL)
        assertEquals("https://github.com/VocaHQ/vocaphone", PROJECT_URL)
        assertEquals("https://vocaphone.vocahq.com", WEBSITE_URL)
        assertEquals("https://github.com/VocaHQ/vocaphone/issues/new/choose", NEW_ISSUE_URL)
    }

    @Test
    fun `family and contact links match the public sites`() {
        assertEquals("https://vocahq.com", FAMILY_SITE_URL)
        assertEquals("https://vocalinux.com", VOCALINUX_URL)
        assertEquals("https://vocamac.com", VOCAMAC_URL)
        assertEquals("https://vocagateway.vocahq.com", VOCAGATEWAY_SITE_URL)
        assertEquals("https://discord.gg/UMJduhcqn", DISCORD_URL)
        assertEquals("https://x.com/vocahq", X_URL)
        assertEquals("hello@vocahq.com", CONTACT_EMAIL)
        assertEquals("mailto:hello@vocahq.com", CONTACT_MAILTO)
        assertEquals(
            listOf(
                "vocahq.com",
                "vocalinux.com",
                "vocamac.com",
                "vocaphone.vocahq.com",
                "vocagateway.vocahq.com",
            ),
            ABOUT_FAMILY_LINKS.map { it.label },
        )
        assertEquals(
            listOf(FAMILY_SITE_URL, VOCALINUX_URL, VOCAMAC_URL, WEBSITE_URL, VOCAGATEWAY_SITE_URL),
            ABOUT_FAMILY_LINKS.map { it.url },
        )
        assertEquals(
            listOf("Discord", "X @vocahq", CONTACT_EMAIL),
            ABOUT_CONTACT_LINKS.map { it.label },
        )
        assertEquals(
            listOf(DISCORD_URL, X_URL, CONTACT_MAILTO),
            ABOUT_CONTACT_LINKS.map { it.url },
        )
        (ABOUT_FAMILY_LINKS + ABOUT_CONTACT_LINKS).forEach { assertNull(it.icon) }
    }

    @Test
    fun `about copy names the family and stays honest about status`() {
        assertEquals("VocaPhone", ABOUT_WORDMARK)
        assertEquals("Report a bug or idea", ABOUT_REPORT_BUG)
        assertTrue(ABOUT_TAGLINE.contains("Android"))
        assertTrue(ABOUT_TAGLINE.contains("iPhone"))
        assertTrue(ABOUT_STATUS.contains("Android public beta"))
        assertTrue(ABOUT_STATUS.contains("source build"))
        assertTrue(ABOUT_STATUS.contains("App Store"))
        assertFalse(ABOUT_STATUS.contains("Play Store", ignoreCase = true))
        assertFalse(ABOUT_STATUS.contains("production", ignoreCase = true))
        assertTrue(ABOUT_ON_DEVICE.contains("on this phone first"))
        assertTrue(ABOUT_ON_DEVICE.contains("optional"))
        assertTrue(ABOUT_ON_DEVICE.contains("self-hosted"))
        assertTrue(ABOUT_ON_DEVICE.contains("never calls it"))
        assertTrue(ABOUT_FAMILY_NOTE.contains("VocaHQ"))
        assertTrue(ABOUT_FAMILY_NOTE.contains("VocaLinux"))
        assertTrue(ABOUT_FAMILY_NOTE.contains("VocaMac"))
        assertTrue(ABOUT_FAMILY_NOTE.contains("VocaWin"))
        assertTrue(ABOUT_FEEDBACK_NOTE.contains("GitHub issue"))
    }

    @Test
    fun `about copy skips em dashes`() {
        for (text in listOf(
            ABOUT_WORDMARK,
            ABOUT_REPORT_BUG,
            ABOUT_TAGLINE,
            ABOUT_STATUS,
            ABOUT_ON_DEVICE,
            ABOUT_FAMILY_NOTE,
            ABOUT_FEEDBACK_NOTE,
            ABOUT_PRIVACY_NOTE,
            ABOUT_DIAGNOSTICS_NOTE,
            ABOUT_COPY_DIAGNOSTICS,
            ABOUT_CLEAR_EVENT_LOG,
        ) + ABOUT_FAMILY_LINKS.map { it.label } + ABOUT_CONTACT_LINKS.map { it.label }) {
            assertFalse(text.contains("—"))
            assertFalse(text.contains("–"))
        }
    }
}
