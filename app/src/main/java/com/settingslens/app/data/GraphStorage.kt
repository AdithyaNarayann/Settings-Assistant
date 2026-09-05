package com.settingslens.app.data

import android.content.Context
import android.util.Log
import com.settingslens.app.model.SettingsGraph
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Persists the crawled settings graph as a JSON file in app-private storage.
 * Simple file-based storage — no Room/SQLite needed for a graph of ~800 nodes.
 */
class GraphStorage(private val context: Context) {

    companion object {
        private const val TAG = "GraphStorage"
        private const val GRAPH_FILENAME = "settings_graph.json"
    }

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /**
     * Save the graph to internal storage.
     * @return true if saved successfully
     */
    fun saveGraph(graph: SettingsGraph): Boolean {
        return try {
            val file = File(context.filesDir, GRAPH_FILENAME)
            val jsonString = json.encodeToString(graph)
            file.writeText(jsonString)
            Log.i(TAG, "Graph saved: ${graph.nodeCount} nodes, ${jsonString.length} bytes")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save graph", e)
            false
        }
    }

    /**
     * Load the graph from internal storage.
     * @return the graph, or null if not found or corrupted
     */
    fun loadGraph(): SettingsGraph? {
        return try {
            val file = File(context.filesDir, GRAPH_FILENAME)
            if (!file.exists()) {
                Log.i(TAG, "No saved graph found")
                return null
            }
            val jsonString = file.readText()
            val graph = json.decodeFromString<SettingsGraph>(jsonString)
            Log.i(TAG, "Graph loaded: ${graph.nodeCount} nodes")
            graph
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load graph", e)
            null
        }
    }

    /**
     * Check if a graph exists in storage.
     */
    fun hasGraph(): Boolean {
        return File(context.filesDir, GRAPH_FILENAME).exists()
    }

    /**
     * Delete the stored graph.
     */
    fun deleteGraph(): Boolean {
        return File(context.filesDir, GRAPH_FILENAME).delete()
    }

    /**
     * Get the raw JSON string of the stored graph (for uploading to backend).
     */
    fun getGraphJson(): String? {
        return try {
            val file = File(context.filesDir, GRAPH_FILENAME)
            if (file.exists()) file.readText() else null
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read graph JSON", e)
            null
        }
    }
}
