package com.settingslens.app.data

import com.settingslens.app.model.NavigationPath
import com.settingslens.app.model.NavigationStep
import com.settingslens.app.model.NodeSelector
import com.settingslens.app.model.ResolveResponse
import com.settingslens.app.model.SettingsGraph
import com.settingslens.app.model.SettingsNode

/**
 * Local offline intent resolver.
 *
 * Resolves user voice transcripts against the local settings graph using
 * token scoring and Manglish synonym expansion when the backend server is unreachable.
 */
object LocalResolver {

    private val MANGLISH_SYNONYMS = mapOf(
        "valuthakkanam" to listOf("font", "size", "display", "large", "text"),
        "valuthaakkanam" to listOf("font", "size", "display", "large", "text"),
        "valuthu" to listOf("font", "size", "display", "large", "text"),
        "kurakkanam" to listOf("volume", "sound", "audio", "ringtone"),
        "kurakkaanam" to listOf("volume", "sound", "audio", "ringtone"),
        "shabdam" to listOf("sound", "volume", "audio", "ringtone"),
        "letters" to listOf("font", "text", "display", "size"),
        "aksharam" to listOf("font", "text", "display", "size"),
        "velicham" to listOf("brightness", "display"),
        "light" to listOf("brightness", "display"),
        "net" to listOf("network", "wifi", "internet", "data"),
        "bhasha" to listOf("language", "input", "keyboard"),
        "battery" to listOf("battery", "power"),
        "sound" to listOf("sound", "volume", "audio"),
        "maattanam" to listOf("change", "edit"),
        "off" to listOf("disable", "turn off"),
        "on" to listOf("enable", "turn on")
    )

    fun resolve(graph: SettingsGraph, transcript: String): ResolveResponse {
        val candidates = findCandidates(graph, transcript)
        if (candidates.isEmpty()) {
            return ResolveResponse(
                type = "clarification",
                question = "I couldn't find a setting matching \"$transcript\". Could you describe it differently?",
                confidence = 0.0
            )
        }

        val topNode = candidates.first()
        val path = buildPath(graph, topNode)

        return ResolveResponse(
            type = "resolved",
            path = path,
            confidence = 0.85
        )
    }

    private fun findCandidates(
        graph: SettingsGraph,
        transcript: String,
        maxCandidates: Int = 20
    ): List<SettingsNode> {
        val transcriptLower = transcript.lowercase().trim()
        val rawTokens = transcriptLower.split(Regex("[^a-zA-Z0-9]+")).filter { it.isNotBlank() }
        val tokens = rawTokens.toMutableSet()

        for (token in rawTokens) {
            MANGLISH_SYNONYMS[token]?.let { tokens.addAll(it) }
        }

        val scored = mutableListOf<Pair<SettingsNode, Int>>()

        for (node in graph.nodes) {
            var score = 0
            val labelLower = node.label.lowercase()
            val subtitleLower = (node.subtitle ?: "").lowercase()
            val titleLower = (node.screenTitle ?: "").lowercase()
            val breadcrumbText = node.pathBreadcrumbs.joinToString(" ").lowercase()

            // Exact match
            if (labelLower == transcriptLower) {
                score += 20
            } else if (labelLower.contains(transcriptLower)) {
                score += 12
            } else if (transcriptLower.contains(labelLower) && labelLower.length >= 4) {
                score += 10
            }

            // Token matching
            for (token in tokens) {
                if (token.length >= 3) {
                    if (labelLower.contains(token)) score += 5
                    if (subtitleLower.contains(token)) score += 3
                    if (titleLower.contains(token)) score += 3
                    if (breadcrumbText.contains(token)) score += 2
                }
            }

            if (score > 0) {
                scored.add(node to score)
            }
        }

        scored.sortByDescending { it.second }
        return scored.take(maxCandidates).map { it.first }
    }

    fun buildPath(graph: SettingsGraph, targetNode: SettingsNode): NavigationPath {
        val pathNodes = mutableListOf<SettingsNode>()
        val visitedIds = mutableSetOf<String>()
        var current: SettingsNode? = targetNode

        while (current != null) {
            if (!visitedIds.add(current.id)) break
            pathNodes.add(current)
            current = if (current.parentId != null) {
                graph.nodes.find { it.id == current?.parentId }
            } else {
                null
            }
        }

        pathNodes.reverse()

        // Check for direct intent action shortcut
        var directIntent: String? = null
        var startIdx = 0
        for ((index, node) in pathNodes.withIndex()) {
            if (node.directIntentAction != null) {
                directIntent = node.directIntentAction
                startIdx = index + 1
                break
            }
        }

        val steps = pathNodes.subList(startIdx, pathNodes.size).map { node ->
            NavigationStep(
                screenSignatureHint = node.screenSignature,
                clickTarget = NodeSelector(
                    resourceId = node.selector.resourceId,
                    text = node.selector.text,
                    contentDescription = node.selector.contentDescription
                ),
                label = node.label
            )
        }

        return NavigationPath(
            directIntentAction = directIntent,
            steps = steps
        )
    }
}
