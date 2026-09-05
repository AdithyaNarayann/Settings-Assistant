package com.settingslens.app.accessibility

import java.security.MessageDigest

/**
 * Computes a stable signature for a Settings screen.
 *
 * The signature is a hash of the screen title + sorted set of visible item labels.
 * This is used during crawl to detect:
 * - "I've already visited this screen" (avoid redundant work)
 * - Back-references / cycles in the Settings navigation graph
 * - Infinite loops (some OEM settings have circular navigation)
 *
 * The signature intentionally does NOT include dynamic content like
 * toggle states, battery percentages, or timestamps, which would make
 * the same logical screen produce different signatures on each visit.
 */
object ScreenSignature {

    /**
     * Compute a screen signature from title and visible item labels.
     *
     * @param screenTitle The screen title (from window title or first heading)
     * @param itemLabels All visible item labels on the screen
     * @return A hex string hash serving as the screen signature
     */
    fun compute(screenTitle: String?, itemLabels: List<String>): String {
        val normalizedTitle = screenTitle?.trim()?.lowercase() ?: "untitled"
        
        // Sort labels for stability (order might vary between visits)
        // Take only distinct labels to avoid counting duplicates
        val sortedLabels = itemLabels
            .map { it.trim().lowercase() }
            .filter { it.isNotBlank() }
            .distinct()
            .sorted()
        
        val content = buildString {
            append("title:")
            append(normalizedTitle)
            append("|items:")
            append(sortedLabels.joinToString(","))
        }
        
        return sha256Short(content)
    }

    /**
     * Compute a short SHA-256 hash (first 16 hex chars = 64 bits).
     * This is plenty for dedup of ~800 screens — collision probability
     * is negligible at this scale.
     */
    private fun sha256Short(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(input.toByteArray(Charsets.UTF_8))
        return hash.take(8).joinToString("") { "%02x".format(it) }
    }
}
