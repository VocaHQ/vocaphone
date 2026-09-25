package com.vocahq.vocaphone.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SetupPageTest {
    private val ready = SetupStatus(
        microphone = true, notifications = true, keyboard = true, gatewayConfigured = true,
    )

    @Test
    fun `relaunch resumes the first real missing requirement in page order`() {
        assertEquals(SetupPage.KEYBOARD, SetupPage.resume(SetupStatus()))
        assertEquals(SetupPage.MICROPHONE, SetupPage.resume(SetupStatus(keyboard = true)))
        assertEquals(SetupPage.NOTIFICATIONS, SetupPage.resume(ready.copy(notifications = false)))
        assertEquals(SetupPage.SOURCE, SetupPage.resume(ready.copy(gatewayConfigured = false)))
        assertEquals(SetupPage.READY, SetupPage.resume(ready))
    }

    @Test
    fun `welcome is only satisfied by an explicit acknowledgement`() {
        assertFalse(SetupPage.WELCOME.isSatisfied(SetupStatus()))
        assertTrue(SetupPage.WELCOME.isSatisfied(SetupStatus(), welcomeAcknowledged = true))
        assertEquals(SetupPage.WELCOME, SetupPage.resume(SetupStatus(), welcomeAcknowledged = false))
    }

    @Test
    fun `after intro cold start uses welcome until it is persisted then resumes`() {
        val midSetup = SetupStatus(keyboard = true)
        assertEquals(SetupPage.WELCOME, SetupPage.afterIntro(SetupStatus(), welcomeSeen = false))
        assertEquals(SetupPage.KEYBOARD, SetupPage.afterIntro(SetupStatus(), welcomeSeen = true))
        assertEquals(SetupPage.MICROPHONE, SetupPage.afterIntro(midSetup, welcomeSeen = true))
        assertEquals(SetupPage.READY, SetupPage.afterIntro(ready, welcomeSeen = true))
        // Continue after welcome must resume, not hardcode KEYBOARD.
        assertEquals(
            SetupPage.MICROPHONE,
            SetupPage.resume(midSetup, welcomeAcknowledged = true),
        )
    }

    @Test
    fun `enabled but unselected keyboard cannot advance`() {
        val status = ready.copy(keyboard = false, ime = ImeSetupStatus(enabled = true))
        assertFalse(SetupPage.KEYBOARD.isSatisfied(status))
        assertEquals(SetupPage.KEYBOARD, SetupPage.resume(status))
    }

    @Test
    fun `revoking any requirement blocks completion and offers the right recovery page`() {
        val revoked = listOf(
            ready.copy(keyboard = false) to SetupPage.KEYBOARD,
            ready.copy(microphone = false) to SetupPage.MICROPHONE,
            ready.copy(notifications = false) to SetupPage.NOTIFICATIONS,
            ready.copy(gatewayConfigured = false) to SetupPage.SOURCE,
        )
        revoked.forEach { (status, recovery) ->
            assertFalse(SetupPage.READY.isSatisfied(status))
            assertEquals(recovery, SetupPage.resume(status))
        }
        assertTrue(SetupPage.READY.isSatisfied(ready))
    }

    @Test
    fun `back and forward preserve the ordered journey without granting readiness`() {
        assertEquals(SetupPage.WELCOME, SetupPage.KEYBOARD.previous())
        assertEquals(SetupPage.WELCOME, SetupPage.WELCOME.previous())
        assertEquals(SetupPage.KEYBOARD, SetupPage.WELCOME.next())
        assertEquals(SetupPage.READY, SetupPage.READY.next())
        SetupPage.entries.dropLast(1).forEach { page ->
            assertEquals(page, page.next().previous())
            if (page == SetupPage.WELCOME) {
                assertFalse(page.isSatisfied(SetupStatus()))
                assertTrue(page.isSatisfied(SetupStatus(), welcomeAcknowledged = true))
            } else {
                assertFalse(page.isSatisfied(SetupStatus()))
            }
        }
    }

    @Test
    fun `back from first requirement skips welcome once it is acknowledged`() {
        assertEquals(SetupPage.KEYBOARD, SetupPage.KEYBOARD.previous(welcomeAcknowledged = true))
        assertEquals(SetupPage.WELCOME, SetupPage.KEYBOARD.previous(welcomeAcknowledged = false))
        assertEquals(SetupPage.KEYBOARD, SetupPage.MICROPHONE.previous(welcomeAcknowledged = true))
    }
}
