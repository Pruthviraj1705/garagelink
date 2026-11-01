package com.pruthviraj.garagelink

import kotlin.math.max

object FuzzyMatcher {

    private fun levenshtein(a: String, b: String): Int {
        val dp = Array(a.length + 1) { IntArray(b.length + 1) }
        for (i in 0..a.length) dp[i][0] = i
        for (j in 0..b.length) dp[0][j] = j
        for (i in 1..a.length) {
            for (j in 1..b.length) {
                dp[i][j] = if (a[i - 1] == b[j - 1]) dp[i - 1][j - 1]
                else 1 + minOf(dp[i - 1][j], dp[i][j - 1], dp[i - 1][j - 1])
            }
        }
        return dp[a.length][b.length]
    }

    // 1.0 = identical, 0.0 = nothing in common
    fun similarity(a: String, b: String): Double {
        val s1 = a.trim().lowercase()
        val s2 = b.trim().lowercase()
        if (s1.isEmpty() || s2.isEmpty()) return 0.0
        val distance = levenshtein(s1, s2)
        return 1.0 - (distance.toDouble() / max(s1.length, s2.length))
    }

    fun findBestMatch(ocrText: String, candidates: List<String>): Pair<String?, Double> {
        var bestMatch: String? = null
        var bestScore = 0.0
        for (candidate in candidates) {
            val score = similarity(ocrText, candidate)
            if (score > bestScore) { bestScore = score; bestMatch = candidate }
        }
        return Pair(bestMatch, bestScore)
    }
}