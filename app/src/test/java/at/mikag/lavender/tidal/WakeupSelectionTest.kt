package at.mikag.lavender.tidal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class WakeupSelectionTest {
    private val source = (1..200).map { "t$it" }

    @Test
    fun inOrderTakesTheFirstFifty() {
        assertEquals(source.take(50), WakeupSelection.pick(source, shuffle = false))
    }

    @Test
    fun shuffledPicksFiftyDistinctSongsFromTheSource() {
        val picked = WakeupSelection.pick(source, shuffle = true, random = Random(42))
        assertEquals(50, picked.size)
        assertEquals(50, picked.toSet().size)
        assertTrue(source.containsAll(picked))
        assertNotEquals(source.take(50), picked)
    }

    @Test
    fun shuffledDiffersBetweenAlarms() {
        val first = WakeupSelection.pick(source, shuffle = true, random = Random(1))
        val second = WakeupSelection.pick(source, shuffle = true, random = Random(2))
        assertNotEquals(first, second)
    }

    @Test
    fun smallSourceIsUsedCompletely() {
        val small = listOf("a", "b", "c")
        assertEquals(small, WakeupSelection.pick(small, shuffle = false))
        assertEquals(small.toSet(), WakeupSelection.pick(small, shuffle = true, random = Random(3)).toSet())
    }

    @Test
    fun duplicatesAreDropped() {
        assertEquals(listOf("a", "b"), WakeupSelection.pick(listOf("a", "b", "a", "b"), shuffle = false))
    }

    @Test
    fun emptySourceGivesNothing() {
        assertTrue(WakeupSelection.pick(emptyList(), shuffle = true).isEmpty())
    }
}
