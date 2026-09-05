package com.settingslens.app.model

import kotlinx.serialization.Serializable

/**
 * A single node in the crawled Settings graph.
 * Represents one clickable/actionable item on a Settings screen.
 *
 * Key design: we store a [selector] (text/resource-id/content-desc) for
 * re-finding this node in a live accessibility tree, NOT raw node IDs
 * which are ephemeral and invalid after the window is torn down.
 */
@Serializable
data class SettingsNode(
    /** Unique ID within the graph (UUID or incrementing int). */
    val id: String,
    
    /** Human-readable label (the text the user sees on screen). */
    val label: String,
    
    /** Optional subtitle/summary text shown below the label. */
    val subtitle: String? = null,
    
    /** Screen signature of the screen this node appears on. */
    val screenSignature: String,
    
    /** ID of the parent node (null for root-level items). */
    val parentId: String? = null,
    
    /** Selector for re-finding this node in a live window tree. */
    val selector: NodeSelector,
    
    /** Depth in the navigation hierarchy (0 = top-level settings screen). */
    val depth: Int,
    
    /** 
     * If this node maps to a known Android Settings intent action
     * (e.g. "android.settings.WIFI_SETTINGS"), store it here so
     * navigation can jump directly without walking from root.
     */
    val directIntentAction: String? = null,
    
    /** The view class name (e.g. "android.widget.LinearLayout"). */
    val className: String? = null,
    
    /** Whether this node is a clickable item that leads to a sub-screen. */
    val isClickable: Boolean = true,
    
    /** IDs of child nodes (populated after crawl completes). */
    val childIds: MutableList<String> = mutableListOf()
)
