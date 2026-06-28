package com.example.data

import java.util.regex.Pattern

object SeriesParser {
    // Regex for matching common chapter structures: Ch.X, Chapter X, Vol.X, vX, etc.
    private val chapterRegex = Regex("""(?i)(?:chapter|ch\.?|v\.?|vol\.?|volume)\s*(\d+(?:\.\d+)?)""")
    private val trailingDigitsRegex = Regex("""(\d+(?:\.\d+)?)\s*$""")

    data class ParsedInfo(val seriesName: String, val chapterNumber: Float)

    fun parse(title: String): ParsedInfo {
        val trimmed = title.trim()
        
        // 1. Try to find a chapter pattern (e.g. "Ch. 5" or "Chapter 12")
        val match = chapterRegex.find(trimmed)
        if (match != null) {
            val numStr = match.groupValues[1]
            val chNum = numStr.toFloatOrNull() ?: 1.0f
            
            // Series name is everything before the match, stripped of delimiters
            var sName = trimmed.substring(0, match.range.first).trim()
            if (sName.endsWith("-") || sName.endsWith("_") || sName.endsWith(":")) {
                sName = sName.dropLast(1).trim()
            }
            if (sName.isEmpty()) {
                sName = "Uncategorized"
            }
            return ParsedInfo(sName, chNum)
        }
        
        // 2. Try to find trailing numbers (e.g. "Solo Leveling 05")
        val trailingMatch = trailingDigitsRegex.find(trimmed)
        if (trailingMatch != null) {
            val numStr = trailingMatch.groupValues[1]
            val chNum = numStr.toFloatOrNull() ?: 1.0f
            val sName = trimmed.substring(0, trailingMatch.range.first).trim()
            if (sName.isNotEmpty()) {
                return ParsedInfo(sName, chNum)
            }
        }

        // Default if no pattern matches
        return ParsedInfo(trimmed, 1.0f)
    }

    // Natural sorting comparison
    val naturalOrderComparator = Comparator<Manhwa> { m1, m2 ->
        val info1 = parse(m1.title)
        val info2 = parse(m2.title)
        
        if (info1.seriesName.lowercase() != info2.seriesName.lowercase()) {
            info1.seriesName.compareTo(info2.seriesName, ignoreCase = true)
        } else {
            info1.chapterNumber.compareTo(info2.chapterNumber)
        }
    }
}
