package com.pruthviraj.garagelink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FuzzyMatcherTest {

    private val delta = 1e-9

    // ---- similarity ---------------------------------------------------------------------

    @Test
    fun identicalStringsScoreOne() {
        assertEquals(1.0, FuzzyMatcher.similarity("brake pad", "brake pad"), delta)
    }

    @Test
    fun comparisonIgnoresCaseAndSurroundingSpaces() {
        assertEquals(1.0, FuzzyMatcher.similarity("  Brake PAD ", "brake pad"), delta)
    }

    @Test
    fun emptyOrBlankInputScoresZero() {
        assertEquals(0.0, FuzzyMatcher.similarity("", "brake pad"), delta)
        assertEquals(0.0, FuzzyMatcher.similarity("brake pad", ""), delta)
        assertEquals(0.0, FuzzyMatcher.similarity("   ", "brake pad"), delta)
        assertEquals(0.0, FuzzyMatcher.similarity("", ""), delta)
    }

    @Test
    fun completelyDifferentStringsScoreZero() {
        assertEquals(0.0, FuzzyMatcher.similarity("abc", "xyz"), delta)
    }

    @Test
    fun oneMissingLetterCostsOneNinthOfTheScore() {
        // "brak pad" -> "brake pad" is one insertion; the longer string has 9 characters.
        assertEquals(1.0 - 1.0 / 9.0, FuzzyMatcher.similarity("brak pad", "brake pad"), delta)
    }

    @Test
    fun similarityIsSymmetric() {
        val pairs = listOf("spark plug" to "spark plugs", "chain kit" to "clutch kit", "oil" to "air filter")
        for ((a, b) in pairs) {
            assertEquals(FuzzyMatcher.similarity(a, b), FuzzyMatcher.similarity(b, a), delta)
        }
    }

    @Test
    fun scoreAlwaysStaysBetweenZeroAndOne() {
        val samples = listOf("a", "brake", "BRAKE PAD FRONT", "x y z", "1234", "chain kit h p pro")
        for (a in samples) for (b in samples) {
            val score = FuzzyMatcher.similarity(a, b)
            assertTrue("score $score for '$a' vs '$b'", score in 0.0..1.0)
        }
    }

    // ---- findBestMatch ------------------------------------------------------------------

    private val inventory = listOf("Clutch cable", "Brake pad", "Brake disc", "Spark plug", "Air filter")

    @Test
    fun findBestMatchReturnsTheClosestCandidate() {
        val (name, score) = FuzzyMatcher.findBestMatch("BRAKE PAD", inventory)
        assertEquals("Brake pad", name)
        assertEquals(1.0, score, delta)
    }

    @Test
    fun findBestMatchToleratesTypicalOcrMistakes() {
        // "B" read as "8", and a dropped letter in "plug"
        assertEquals("Brake pad", FuzzyMatcher.findBestMatch("8rake pad", inventory).first)
        assertEquals("Spark plug", FuzzyMatcher.findBestMatch("Spark plg", inventory).first)
    }

    @Test
    fun findBestMatchWithNoCandidatesReturnsNull() {
        val (name, score) = FuzzyMatcher.findBestMatch("brake pad", emptyList())
        assertNull(name)
        assertEquals(0.0, score, delta)
    }

    @Test
    fun findBestMatchReturnsNullWhenNothingIsSimilar() {
        val (name, score) = FuzzyMatcher.findBestMatch("xyz", listOf("abc", "def"))
        assertNull(name)
        assertEquals(0.0, score, delta)
    }

    @Test
    fun findBestMatchWithBlankQueryReturnsNull() {
        val (name, score) = FuzzyMatcher.findBestMatch("   ", inventory)
        assertNull(name)
        assertEquals(0.0, score, delta)
    }

    @Test
    fun whenTwoCandidatesTieTheFirstOneWins() {
        // "ab" vs "ac" and "ab" vs "ad" both score 0.5
        val (name, _) = FuzzyMatcher.findBestMatch("ab", listOf("ac", "ad"))
        assertEquals("ac", name)
    }
}
