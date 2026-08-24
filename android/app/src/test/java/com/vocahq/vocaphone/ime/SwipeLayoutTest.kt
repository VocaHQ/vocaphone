package com.vocahq.vocaphone.ime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SwipeLayoutTest {

    @Test
    fun `qwerty neighbours match the previous layout`() {
        val g = SwipeLayout.nearby('g')
        assertTrue(g.contains('f'))
        assertTrue(g.contains('h'))
        assertTrue(g.contains('t'))
        assertFalse(g.contains('q'))
        assertFalse(g.contains('g'))
    }

    @Test
    fun `a grid point on a key centre is that key`() {
        val h = SwipeLayout.qwerty('h')!!
        val mapped = SwipeLayout.gridPoint('h', 0f, 0f)!!
        assertEquals(h.x, mapped.x, 0.001f)
        assertEquals(h.y, mapped.y, 0.001f)
    }

    @Test
    fun `interpolating a word visits its letters in order`() {
        val path = SwipeLayout.interpolate("hello")
        assertTrue(path.size >= 8)
        assertTrue(SwipeLayout.lettersLieOnPath("helo", SwipeLayout.samplePoints(path)))
        assertFalse(SwipeLayout.lettersLieOnPath("world", SwipeLayout.samplePoints(path)))
    }

    @Test
    fun `double letters are detected so a loop path can be scored`() {
        assertTrue(SwipeLayout.hasDoubleLetter("hello"))
        assertTrue(SwipeLayout.hasDoubleLetter("good"))
        assertFalse(SwipeLayout.hasDoubleLetter("world"))
    }

    @Test
    fun `a resampled path has a fixed number of points`() {
        val samples = SwipeLayout.sampleWord("hello")
        assertEquals(SwipeLayout.SAMPLE_POINTS * 2, samples.size)
    }
}
