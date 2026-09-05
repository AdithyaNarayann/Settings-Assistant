package com.settingslens.app.accessibility

import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.settingslens.app.model.NodeSelector

/**
 * Extracts meaningful node information from an AccessibilityNodeInfo tree.
 *
 * Design: AccessibilityNodeInfo is ephemeral — valid only while the window
 * is displayed. We extract stable selectors (resource-id, text, content-desc)
 * that can be used to re-find the node in a future accessibility session.
 *
 * We filter the tree to only user-meaningful items: clickable settings rows,
 * switches, sliders — not internal ViewGroups or decorative elements.
 */
object NodeExtractor {

    private const val TAG = "NodeExtractor"

    /**
     * Data extracted from a single accessibility node.
     * This is a transient extraction — NOT stored long-term.
     * It gets converted to a SettingsNode for graph storage.
     */
    data class ExtractedNode(
        val label: String,
        val subtitle: String?,
        val selector: NodeSelector,
        val className: String?,
        val isClickable: Boolean,
        val bounds: android.graphics.Rect,
        /** The live node reference — only valid during this extraction session. */
        val liveNode: AccessibilityNodeInfo
    )

    /**
     * Extract all user-meaningful items from the current screen.
     *
     * @param root The root AccessibilityNodeInfo of the current window
     * @return List of extracted nodes representing settings items
     */
    fun extractScreenItems(root: AccessibilityNodeInfo?): List<ExtractedNode> {
        if (root == null) return emptyList()

        val items = mutableListOf<ExtractedNode>()
        val visited = mutableSetOf<Int>() // Track visited node hashes to avoid duplicates

        extractRecursive(root, items, visited, depth = 0)

        Log.d(TAG, "Extracted ${items.size} items from screen")
        return items
    }

    /**
     * Get the screen title from the window/root node.
     * Tries: window title, toolbar/action bar text, first heading node.
     */
    fun extractScreenTitle(root: AccessibilityNodeInfo?): String? {
        if (root == null) return null

        // Try to find a node with role heading or a toolbar title
        val title = findFirstByPredicate(root) { node ->
            // Check for heading role (API 28+)
            node.isHeading ||
            // Check for toolbar/action bar title patterns
            node.viewIdResourceName?.contains("action_bar_title") == true ||
            node.viewIdResourceName?.contains("collapsing_toolbar") == true ||
            node.viewIdResourceName?.contains("header_title") == true
        }

        val titleText = title?.text?.toString()
        if (titleText != null) return titleText

        // Fallback: window title from pane title
        val paneTitle = root.paneTitle?.toString()
        if (paneTitle != null) return paneTitle

        return null
    }

    private fun extractRecursive(
        node: AccessibilityNodeInfo,
        items: MutableList<ExtractedNode>,
        visited: MutableSet<Int>,
        depth: Int
    ) {
        // Safety: don't recurse too deep (some OEM UIs have very deep view hierarchies)
        if (depth > 30) return

        val nodeHash = System.identityHashCode(node)
        if (nodeHash in visited) return
        visited.add(nodeHash)

        // Check if this node is a meaningful settings item
        if (isSettingsItem(node)) {
            val label = getNodeLabel(node)
            if (label != null && label.isNotBlank()) {
                val subtitle = getSubtitle(node)
                val selector = buildSelector(node)

                if (selector.isValid()) {
                    val bounds = android.graphics.Rect()
                    node.getBoundsInScreen(bounds)

                    items.add(
                        ExtractedNode(
                            label = label,
                            subtitle = subtitle,
                            selector = selector,
                            className = node.className?.toString(),
                            isClickable = isClickableItem(node),
                            bounds = bounds,
                            liveNode = node
                        )
                    )
                }
            }
        }

        // Recurse into children
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            extractRecursive(child, items, visited, depth + 1)
        }
    }

    /**
     * Determines if a node represents a user-meaningful settings item
     * (as opposed to a layout container or decorative element).
     */
    private fun isSettingsItem(node: AccessibilityNodeInfo): Boolean {
        val className = node.className?.toString() ?: return false

        // Skip pure layout containers with no text
        if (node.text.isNullOrBlank() && node.contentDescription.isNullOrBlank()) {
            return false
        }

        // Include: TextViews that are part of clickable parents, switches, checkboxes, etc.
        val isInteractiveWidget = className.contains("Switch") ||
                className.contains("CheckBox") ||
                className.contains("RadioButton") ||
                className.contains("SeekBar") ||
                className.contains("Spinner")

        val isTextItem = (className.contains("TextView") || className.contains("Button")) &&
                !node.text.isNullOrBlank()

        // A clickable container with text is a settings row
        val isClickableWithText = isClickableItem(node) && !node.text.isNullOrBlank()

        return isInteractiveWidget || isTextItem || isClickableWithText
    }

    /**
     * Check if a node (or its nearest ancestor) is clickable.
     * Settings items are often TextViews inside a clickable LinearLayout.
     */
    private fun isClickableItem(node: AccessibilityNodeInfo): Boolean {
        if (node.isClickable) return true

        // Walk up to find a clickable parent (max 3 levels — performance guard)
        var parent = node.parent
        var levels = 0
        while (parent != null && levels < 3) {
            if (parent.isClickable) return true
            parent = parent.parent
            levels++
        }
        return false
    }

    /**
     * Get the primary label text for a node.
     */
    private fun getNodeLabel(node: AccessibilityNodeInfo): String? {
        // Prefer text, fall back to content description
        return node.text?.toString()?.trim()
            ?: node.contentDescription?.toString()?.trim()
    }

    /**
     * Try to get subtitle/summary text — often the next sibling or child TextView
     * with a resource ID containing "summary".
     */
    private fun getSubtitle(node: AccessibilityNodeInfo): String? {
        val parent = node.parent ?: return null

        for (i in 0 until parent.childCount) {
            val sibling = parent.getChild(i) ?: continue
            if (sibling == node) continue

            val resId = sibling.viewIdResourceName
            if (resId != null && (resId.contains("summary") || resId.contains("subtitle"))) {
                return sibling.text?.toString()?.trim()
            }
        }
        return null
    }

    /**
     * Build a NodeSelector from the most stable identifiers available.
     * Priority: resource-id > text > content description.
     */
    private fun buildSelector(node: AccessibilityNodeInfo): NodeSelector {
        return NodeSelector(
            resourceId = node.viewIdResourceName,
            text = node.text?.toString()?.trim(),
            contentDescription = node.contentDescription?.toString()?.trim()
        )
    }

    /**
     * Find the first node matching a predicate (BFS).
     */
    private fun findFirstByPredicate(
        root: AccessibilityNodeInfo,
        predicate: (AccessibilityNodeInfo) -> Boolean
    ): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)

        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            if (predicate(current)) return current

            for (i in 0 until current.childCount) {
                current.getChild(i)?.let { queue.add(it) }
            }
        }
        return null
    }

    /**
     * Find a node matching a NodeSelector in the current window tree.
     * Used during navigation to re-find a previously-discovered node.
     *
     * Priority: resource-id (most stable) → exact text → content description.
     */
    fun findNodeBySelector(root: AccessibilityNodeInfo?, selector: NodeSelector): AccessibilityNodeInfo? {
        if (root == null) return null

        // Try resource-id first (most stable across OS updates)
        if (selector.resourceId != null) {
            val matches = root.findAccessibilityNodeInfosByViewId(selector.resourceId)
            if (matches.isNotEmpty()) {
                // If we also have text, use it to disambiguate multiple matches
                if (selector.text != null) {
                    val exact = matches.find { it.text?.toString()?.trim() == selector.text }
                    if (exact != null) return exact
                }
                return matches.first()
            }
        }

        // Try exact text match
        if (selector.text != null) {
            val matches = root.findAccessibilityNodeInfosByText(selector.text)
            // findByText does substring matching; filter to exact match
            val exact = matches.find { it.text?.toString()?.trim() == selector.text }
            if (exact != null) return exact
            // Fall back to first substring match if no exact match
            if (matches.isNotEmpty()) return matches.first()
        }

        // Try content description
        if (selector.contentDescription != null) {
            return findFirstByPredicate(root) { node ->
                node.contentDescription?.toString()?.trim() == selector.contentDescription
            }
        }

        return null
    }

    /**
     * Find a clickable node for the given selector.
     * If the matched node itself isn't clickable, walks up to find
     * the nearest clickable ancestor (settings items are often TextViews
     * inside clickable LinearLayouts).
     */
    fun findClickableNode(root: AccessibilityNodeInfo?, selector: NodeSelector): AccessibilityNodeInfo? {
        val node = findNodeBySelector(root, selector) ?: return null

        if (node.isClickable) return node

        // Walk up to find clickable parent
        var parent = node.parent
        var levels = 0
        while (parent != null && levels < 5) {
            if (parent.isClickable) return parent
            parent = parent.parent
            levels++
        }

        // Last resort: try clicking the node itself even if not marked clickable
        return node
    }

    /**
     * Scroll the first scrollable container forward.
     */
    fun scrollForward(root: AccessibilityNodeInfo?): Boolean {
        if (root == null) return false
        val scrollable = findFirstByPredicate(root) { it.isScrollable }
        return scrollable?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) ?: false
    }

    /**
     * Scroll the first scrollable container backward.
     */
    fun scrollBackward(root: AccessibilityNodeInfo?): Boolean {
        if (root == null) return false
        val scrollable = findFirstByPredicate(root) { it.isScrollable }
        return scrollable?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) ?: false
    }
}

