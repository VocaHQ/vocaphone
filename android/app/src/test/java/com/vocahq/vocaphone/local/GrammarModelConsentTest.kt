package com.vocahq.vocaphone.local

import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Greptile on #437: "Check while typing" works without the grammar model and
 * its download offer can be declined, so turning it on must not make the next
 * app start fetch 28 MB anyway.
 */
class GrammarModelConsentTest {
    @get:Rule val temp = TemporaryFolder()

    @Test
    fun aStoredAnswerIsKeptWhateverIsOnThePhone() {
        assertTrue(GrammarModelConsent.requested(stored = true, fixGrammar = false, filesOnPhone = false))
        assertFalse(GrammarModelConsent.requested(stored = false, fixGrammar = true, filesOnPhone = true))
    }

    @Test
    fun anEarlierBuildsDownloadOrFixGrammarCountsAsAgreement() {
        assertTrue(GrammarModelConsent.requested(stored = null, fixGrammar = false, filesOnPhone = true))
        assertTrue(GrammarModelConsent.requested(stored = null, fixGrammar = true, filesOnPhone = false))
        assertFalse(GrammarModelConsent.requested(stored = null, fixGrammar = false, filesOnPhone = false))
    }

    @Test
    fun onlyAnAgreementOrFixGrammarFetchesAMissingModel() {
        assertFalse(GrammarModelConsent.wanted(available = true, requested = false, fixGrammar = false))
        assertTrue(GrammarModelConsent.wanted(available = true, requested = true, fixGrammar = false))
        // Fix grammar does nothing without the model, so it is an agreement on its own.
        assertTrue(GrammarModelConsent.wanted(available = true, requested = false, fixGrammar = true))
        // The F-Droid build has no ONNX Runtime and never downloads it.
        assertFalse(GrammarModelConsent.wanted(available = false, requested = true, fixGrammar = true))
    }

    private val bodies = mapOf("model.onnx" to "pretend weights", "labels.json" to "[\"KEEP\"]")

    private fun sha256(text: String) = MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray())
        .joinToString("") { "%02x".format(it) }

    private val model = GrammarModelDescriptor(
        id = "grammar-test",
        displayName = "Grammar model",
        repository = "example/grammar",
        revision = "0".repeat(40),
        files = bodies.map { (path, body) -> PinnedFile(path, body.length.toLong(), sha256(body)) },
        sizeBytes = bodies.values.sumOf { it.length.toLong() },
    )

    private fun store(server: MockWebServer) = GrammarModelStore(
        root = temp.root,
        model = model,
        client = OkHttpClient.Builder().readTimeout(5, TimeUnit.SECONDS).build(),
        downloadUrl = { server.url("/${it.path}").toString() },
    )

    /** What [com.vocahq.vocaphone.VocaPhoneApplication] does at start, with [stored] as the setting. */
    private suspend fun start(store: GrammarModelStore, stored: Boolean?, fixGrammar: Boolean): Pair<Boolean, Boolean> {
        val requested = GrammarModelConsent.requested(stored, fixGrammar, store.hasFiles(listOf(RETIRED)))
        val downloaded = store.restore(retired = listOf(RETIRED)) {
            GrammarModelConsent.wanted(available = true, requested = requested, fixGrammar = fixGrammar)
        }
        return requested to downloaded
    }

    @Test
    fun checkWhileTypingAloneNeverDownloadsAtStart() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            // A fresh install that turned typing on and said "Not now" to the
            // model: nothing on the phone, the flag never written.
            val (requested, downloaded) = start(store(server), stored = null, fixGrammar = false)

            assertFalse(requested)
            assertFalse(downloaded)
            assertEquals(0, server.requestCount)
            // Stored as false, so the next start does not reconsider it either.
            assertFalse(start(store(server), stored = requested, fixGrammar = false).second)
            assertEquals(0, server.requestCount)
        }
    }

    @Test
    fun anUpgradeFromABuildWithoutTheFlagKeepsRetryingUntilTheModelLands() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            // Downloaded under the old pin, for typing alone: Fix grammar off.
            File(temp.root, RETIRED).apply { mkdirs() }.resolve("model.onnx").writeText("old weights")
            server.enqueue(MockResponse.Builder().code(404).build())

            val (requested, downloaded) = start(store(server), stored = null, fixGrammar = false)
            assertTrue(requested)
            assertFalse(downloaded)
            assertFalse(File(temp.root, RETIRED).exists())

            // The old folder is gone now; the stored flag is what carries the
            // agreement to the next start.
            bodies.values.forEach { server.enqueue(MockResponse.Builder().code(200).body(it).build()) }
            assertTrue(start(store(server), stored = requested, fixGrammar = false).second)
        }
    }

    @Test
    fun aPartWayDownloadCountsAsFilesOnThePhone() = runBlocking {
        MockWebServer().use { server ->
            val store = store(server)
            assertFalse(store.hasFiles(listOf(RETIRED)))

            File(temp.root, ".${model.id}.staging").mkdirs()
            assertTrue(store.hasFiles(listOf(RETIRED)))
        }
    }

    private companion object {
        const val RETIRED = "grammar-old"
    }
}
