package com.vocahq.vocaphone.local

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SherpaIncrementalSessionTest {

    @Test
    fun `complete stable windows produce a usable latency result`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val session = SherpaIncrementalSession(
                scope = scope,
                prepare = {},
                decode = { SherpaTranscript("words") },
            )
            repeat(120) { assertTrue(session.offer(ShortArray(1_600) { 8_000 })) }

            assertTrue(session.finish().isSafe)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `an empty audible window forces the complete wav fallback`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val session = SherpaIncrementalSession(
                scope = scope,
                prepare = {},
                decode = { SherpaTranscript.EMPTY },
            )
            repeat(120) { assertTrue(session.offer(ShortArray(1_600) { 8_000 })) }

            assertFalse(session.finish().isSafe)
        } finally {
            scope.cancel()
        }
    }
}
