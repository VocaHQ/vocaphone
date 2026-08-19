package com.vocahq.vocaphone.core

import com.vocahq.vocaphone.audio.DictationToneSynth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DictationToneTest {

    @Test
    fun `catalog ids and display names are the family list`() {
        assertEquals(
            listOf(
                "lift" to "Lift",
                "flick" to "Flick",
                "ember" to "Ember",
                "step" to "Step",
                "voca" to "Voca",
                "soft" to "Soft",
                "chirp" to "Chirp",
                "scale" to "Scale",
                "drop" to "Drop",
                "glass" to "Glass",
                "off" to "Off",
            ),
            DictationTone.entries.map { it.id to it.displayName },
        )
    }

    @Test
    fun `phone default is Voca`() {
        assertEquals(DictationTone.VOCA, DictationTone.DEFAULT)
        assertEquals("voca", DictationTone.DEFAULT.id)
    }

    @Test
    fun `an unset preference lands on Voca, not Off`() {
        assertEquals(DictationTone.VOCA, DictationTone.fromStored(null))
        assertEquals(DictationTone.VOCA, DictationTone.fromStored(""))
        assertEquals(DictationTone.VOCA, DictationTone.fromStored("   "))
    }

    @Test
    fun `an unknown stored id lands on Voca`() {
        assertEquals(DictationTone.VOCA, DictationTone.fromStored("no-such-tone"))
    }

    @Test
    fun `Off stays Off once it was saved`() {
        assertEquals(DictationTone.OFF, DictationTone.fromStored("off"))
        assertEquals(DictationTone.OFF, DictationTone.fromStored("Off"))
        assertTrue(DictationTone.OFF.playsCues.not())
    }

    @Test
    fun `a saved tone other than the default is left alone`() {
        assertEquals(DictationTone.LIFT, DictationTone.fromStored("lift"))
        assertEquals(DictationTone.GLASS, DictationTone.fromStored("glass"))
    }

    @Test
    fun `Off plays nothing`() {
        assertEquals(0, DictationToneSynth.start(DictationTone.OFF).size)
        assertEquals(0, DictationToneSynth.stop(DictationTone.OFF).size)
    }

    @Test
    fun `every audible tone has a start and stop cue`() {
        DictationTone.entries.filter { it.playsCues }.forEach { tone ->
            assertTrue("${tone.id} start is empty", DictationToneSynth.start(tone).isNotEmpty())
            assertTrue("${tone.id} stop is empty", DictationToneSynth.stop(tone).isNotEmpty())
        }
    }
}
