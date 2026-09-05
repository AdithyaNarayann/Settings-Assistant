package com.settingslens.app.model

import kotlinx.serialization.Serializable

/**
 * The complete crawled settings graph for a device.
 * Uploaded to the backend once, referenced by [graphId] for all subsequent queries.
 */
@Serializable
data class SettingsGraph(
    /** Server-assigned ID after upload (null before first upload). */
    var graphId: String? = null,
    
    /** Device manufacturer. */
    val deviceManufacturer: String,
    
    /** Device model. */
    val deviceModel: String,
    
    /** Android SDK version. */
    val androidVersion: Int,
    
    /** All discovered nodes. */
    val nodes: List<SettingsNode>,
    
    /** ISO-8601 timestamp of when the crawl completed. */
    val createdAt: String,
    
    /** Set of all unique screen signatures discovered. */
    val screenSignatures: Set<String> = emptySet()
) {
    /** Quick lookup: node by ID. Built lazily, not serialized. */
    @kotlinx.serialization.Transient
    val nodeMap: Map<String, SettingsNode> by lazy { nodes.associateBy { it.id } }
    
    /** Count of total nodes. */
    val nodeCount: Int get() = nodes.size
}
