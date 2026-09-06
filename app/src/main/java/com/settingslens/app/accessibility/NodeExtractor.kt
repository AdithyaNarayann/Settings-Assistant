package com.settingslens.app.accessibility

import android.graphics.Rect
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.settingslens.app.model.NodeSelector

/**
 * Extracts structured node information from an AccessibilityNodeInfo tree.
 *
 * Key Architecture:
 * 1. Preference Row Grouping: Treats each settings item container as a single logical entity,
 *    unifying title, subtitle, and control widgets without producing duplicate phantom nodes.
 * 2. Leaf vs Navigation Discrimination: Inspects for embedded toggles (Switch, CheckBox, SeekBar)
 *    to mark isNavigationCandidate = false, preventing inadvertent setting changes or false recursions.
 * 3. Text-First Selector Resolution: Prioritizes exact label match over non-unique framework IDs,
 *    preventing click misdirection.
 */
object NodeExtractor {

    private const val TAG = "SettingsLens:Extractor"

    data class ExtractedNode(
        val label: String,
        val subtitle: String?,
        val selector: NodeSelector,
        val className: String?,
        val isClickable: Boolean,
        val isNavigationCandidate: Boolean,
        val bounds: Rect,
        val liveNode: AccessibilityNodeInfo
    )

    /**
     * Extract all settings rows from the current screen without duplicates.
     */
    fun extractScreenItems(root: AccessibilityNodeInfo?): List<ExtractedNode> {
        if (root == null) return emptyList()

        val items = mutableListOf<ExtractedNode>()
        val visitedHashes = mutableSetOf<Int>()

        // Check if there is a primary scrollable list (RecyclerView / ListView)
        val listContainer = findListContainer(root)

        if (listContainer != null && listContainer.childCount > 0) {
            // Process children of the list container directly
            for (i in 0 until listContainer.childCount) {
                val child = listContainer.getChild(i) ?: continue
                processItemContainer(child, items, visitedHashes)
            }
        } else {
            // Fallback for screens without RecyclerView (ScrollView / LinearLayout hierarchy)
            extractRecursive(root, items, visitedHashes, depth = 0)
        }

        // Deduplicate any items that might have identical labels and identical bounds
        val distinctItems = mutableListOf<ExtractedNode>()
        val seenSignatures = mutableSetOf<String>()
        for (item in items) {
            val key = "${item.label}|${item.bounds.top}|${item.bounds.bottom}"
            if (seenSignatures.add(key)) {
                distinctItems.add(item)
            }
        }

        Log.d(TAG, "📋 [Extracted Settings] Found ${distinctItems.size} items on current screen.")
        return distinctItems
    }

    /**
     * Extract screen title from toolbar, action bar, heading, or top-level pane title.
     */
    fun extractScreenTitle(root: AccessibilityNodeInfo?): String? {
        if (root == null) return null

        // 1. Check for heading role (API 28+)
        val headingNode = findFirstByPredicate(root) { it.isHeading && !it.text.isNullOrBlank() }
        if (headingNode?.text?.isNotBlank() == true) {
            return headingNode.text.toString().trim()
        }

        // 2. Check toolbar / action bar title patterns (Pixel, Vivo, Samsung, MIUI)
        val toolbarTitle = findFirstByPredicate(root) { node ->
            val resId = node.viewIdResourceName?.lowercase() ?: ""
            val cls = node.className?.toString() ?: ""
            val isTitleRes = resId.contains("action_bar_title") ||
                    resId.contains("toolbar_title") ||
                    resId.contains("collapsing_toolbar") ||
                    resId.contains("header_title") ||
                    resId.contains("entity_header_title") ||
                    resId.contains("suc_layout_title") ||
                    resId.contains("vivo_action_bar_title") ||
                    (resId.endsWith(":id/title") && node.parent?.className?.contains("Toolbar") == true)

            (isTitleRes || cls.contains("Toolbar")) && !node.text.isNullOrBlank()
        }

        if (toolbarTitle?.text?.isNotBlank() == true) {
            return toolbarTitle.text.toString().trim()
        }

        // 3. Fallback: window pane title
        val paneTitle = root.paneTitle?.toString()?.trim()
        if (!paneTitle.isNullOrBlank()) {
            return paneTitle
        }

        // 4. Fallback: prominent TextView at top of viewport (< 250px from top)
        val topText = findFirstByPredicate(root) { node ->
            if (node.className?.contains("TextView") == true && !node.text.isNullOrBlank()) {
                val bounds = Rect()
                node.getBoundsInScreen(bounds)
                bounds.top in 50..250 && bounds.height() > 30
            } else {
                false
            }
        }

        return topText?.text?.toString()?.trim()
    }

    private fun findListContainer(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        return findFirstByPredicate(root) { node ->
            val cls = node.className?.toString() ?: ""
            cls.contains("RecyclerView") || cls.contains("ListView")
        }
    }

    private fun processItemContainer(
        container: AccessibilityNodeInfo,
        items: MutableList<ExtractedNode>,
        visitedHashes: MutableSet<Int>
    ) {
        val hash = System.identityHashCode(container)
        if (hash in visitedHashes) return
        visitedHashes.add(hash)

        val textNodes = mutableListOf<AccessibilityNodeInfo>()
        var hasToggle = false
        var hasSlider = false

        inspectDescendants(container, textNodes) { widgetNode ->
            val cls = widgetNode.className?.toString() ?: ""
            if (cls.contains("Switch") || cls.contains("CheckBox") || cls.contains("RadioButton")) {
                hasToggle = true
            }
            if (cls.contains("SeekBar")) {
                hasSlider = true
            }
        }

        if (textNodes.isEmpty()) {
            val contentDesc = container.contentDescription?.toString()?.trim()
            if (!contentDesc.isNullOrBlank() && container.isClickable) {
                val bounds = Rect()
                container.getBoundsInScreen(bounds)
                items.add(
                    ExtractedNode(
                        label = contentDesc,
                        subtitle = null,
                        selector = NodeSelector(contentDescription = contentDesc),
                        className = container.className?.toString(),
                        isClickable = true,
                        isNavigationCandidate = !hasToggle && !hasSlider,
                        bounds = bounds,
                        liveNode = container
                    )
                )
            }
            return
        }

        // Determine title vs summary
        var titleNode = textNodes.find {
            val id = it.viewIdResourceName?.lowercase() ?: ""
            id.contains("title") && !id.contains("subtitle")
        }
        if (titleNode == null) {
            titleNode = textNodes.first()
        }

        val titleText = titleNode.text?.toString()?.trim()
        if (titleText.isNullOrBlank()) return

        val summaryNode = textNodes.find {
            val id = it.viewIdResourceName?.lowercase() ?: ""
            (id.contains("summary") || id.contains("subtitle")) && it != titleNode
        } ?: textNodes.firstOrNull { it != titleNode && !it.text.isNullOrBlank() }

        val summaryText = summaryNode?.text?.toString()?.trim()

        val bounds = Rect()
        container.getBoundsInScreen(bounds)

        val lowerTitle = titleText.lowercase()
        val isAccountOrAuth = lowerTitle.contains("@") ||
                lowerTitle.contains("account") ||
                lowerTitle.contains("password") ||
                lowerTitle.contains("sign in") ||
                lowerTitle.contains("fingerprint") ||
                lowerTitle.contains("face recognition") ||
                lowerTitle.contains("face unlock") ||
                lowerTitle.contains("suggestion") ||
                lowerTitle.contains("screen lock")

        val hasRadioOrCheck = findAllByPredicate(container) {
            val c = it.className?.toString() ?: ""
            c.contains("RadioButton") || c.contains("CheckBox") || c.contains("CheckedTextView")
        }.isNotEmpty()

        val isClickable = container.isClickable || titleNode.isClickable || isClickableItem(container)
        val isNavigation = isClickable && !hasToggle && !hasSlider && !hasRadioOrCheck && !isAccountOrAuth

        items.add(
            ExtractedNode(
                label = titleText,
                subtitle = summaryText,
                selector = NodeSelector(
                    resourceId = titleNode.viewIdResourceName,
                    text = titleText
                ),
                className = container.className?.toString() ?: titleNode.className?.toString(),
                isClickable = isClickable,
                isNavigationCandidate = isNavigation,
                bounds = bounds,
                liveNode = if (container.isClickable) container else titleNode
            )
        )
    }

    private fun extractRecursive(
        node: AccessibilityNodeInfo,
        items: MutableList<ExtractedNode>,
        visitedHashes: MutableSet<Int>,
        depth: Int
    ) {
        if (depth > 25) return
        val hash = System.identityHashCode(node)
        if (hash in visitedHashes) return
        visitedHashes.add(hash)

        // If this node is a clickable container with children, process as unified row
        if (node.isClickable && node.childCount > 0) {
            processItemContainer(node, items, visitedHashes)
            return // Don't recurse into row children to avoid duplicate sub-nodes
        }

        // Standalone TextView or Button
        val cls = node.className?.toString() ?: ""
        if ((cls.contains("TextView") || cls.contains("Button")) && !node.text.isNullOrBlank()) {
            val text = node.text.toString().trim()
            val lowerText = text.lowercase()
            val isAccountOrAuth = lowerText.contains("@") ||
                    lowerText.contains("account") ||
                    lowerText.contains("password") ||
                    lowerText.contains("sign in") ||
                    lowerText.contains("fingerprint") ||
                    lowerText.contains("face recognition") ||
                    lowerText.contains("face unlock") ||
                    lowerText.contains("suggestion") ||
                    lowerText.contains("screen lock")

            val bounds = Rect()
            node.getBoundsInScreen(bounds)
            items.add(
                ExtractedNode(
                    label = text,
                    subtitle = null,
                    selector = NodeSelector(resourceId = node.viewIdResourceName, text = text),
                    className = cls,
                    isClickable = node.isClickable || isClickableItem(node),
                    isNavigationCandidate = (node.isClickable || isClickableItem(node)) &&
                            !cls.contains("Button") &&
                            !cls.contains("Radio") &&
                            !cls.contains("Check") &&
                            !isAccountOrAuth,
                    bounds = bounds,
                    liveNode = node
                )
            )
            return
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            extractRecursive(child, items, visitedHashes, depth + 1)
        }
    }

    private fun inspectDescendants(
        root: AccessibilityNodeInfo,
        textNodes: MutableList<AccessibilityNodeInfo>,
        onWidgetFound: (AccessibilityNodeInfo) -> Unit
    ) {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)

        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()

            val cls = current.className?.toString() ?: ""
            if (cls.contains("TextView") && !current.text.isNullOrBlank()) {
                textNodes.add(current)
            } else if (cls.contains("Switch") || cls.contains("CheckBox") ||
                cls.contains("RadioButton") || cls.contains("SeekBar")
            ) {
                onWidgetFound(current)
            }

            for (i in 0 until current.childCount) {
                current.getChild(i)?.let { queue.add(it) }
            }
        }
    }

    /**
     * Check if a node (or its ancestor within 3 hops) is marked clickable.
     */
    private fun isClickableItem(node: AccessibilityNodeInfo): Boolean {
        if (node.isClickable) return true
        var parent = node.parent
        var hops = 0
        while (parent != null && hops < 3) {
            if (parent.isClickable) return true
            parent = parent.parent
            hops++
        }
        return false
    }

    /**
     * Find a node matching a NodeSelector in the current window tree.
     * Searches exact text first for uniqueness, then resource ID with text verification.
     */
    fun findNodeBySelector(root: AccessibilityNodeInfo?, selector: NodeSelector): AccessibilityNodeInfo? {
        if (root == null) return null

        // 1. Text match first — settings labels are unique on screen
        if (!selector.text.isNullOrBlank()) {
            val textMatches = root.findAccessibilityNodeInfosByText(selector.text.trim())
            val exact = textMatches.find { it.text?.toString()?.trim().equals(selector.text.trim(), ignoreCase = true) }
            if (exact != null) return exact

            val partial = textMatches.find { it.text?.toString()?.contains(selector.text.trim(), ignoreCase = true) == true }
            if (partial != null) return partial
        }

        // 2. Resource ID match (verify text if selector provides text)
        if (!selector.resourceId.isNullOrBlank()) {
            val resMatches = root.findAccessibilityNodeInfosByViewId(selector.resourceId)
            if (resMatches.isNotEmpty()) {
                if (!selector.text.isNullOrBlank()) {
                    val matchingText = resMatches.find { node ->
                        node.text?.toString()?.trim().equals(selector.text.trim(), ignoreCase = true)
                    }
                    if (matchingText != null) return matchingText
                } else if (resMatches.size == 1) {
                    return resMatches.first()
                }
            }
        }

        // 3. Content description
        if (!selector.contentDescription.isNullOrBlank()) {
            return findFirstByPredicate(root) { node ->
                node.contentDescription?.toString()?.trim().equals(selector.contentDescription.trim(), ignoreCase = true)
            }
        }

        return null
    }

    /**
     * Find a clickable node for the given selector.
     * If the matched node isn't clickable, walks up ancestors to locate the clickable row container.
     */
    fun findClickableNode(root: AccessibilityNodeInfo?, selector: NodeSelector): AccessibilityNodeInfo? {
        val node = findNodeBySelector(root, selector) ?: return null

        if (node.isClickable) return node

        var parent = node.parent
        var levels = 0
        while (parent != null && levels < 5) {
            if (parent.isClickable) return parent
            parent = parent.parent
            levels++
        }

        return node
    }

    /**
     * Scroll scrollable containers forward (innermost first).
     */
    fun scrollForward(root: AccessibilityNodeInfo?): Boolean {
        if (root == null) return false
        val scrollables = findAllByPredicate(root) { it.isScrollable }
        for (scrollable in scrollables.reversed()) {
            if (scrollable.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) {
                return true
            }
        }
        return false
    }

    /**
     * Scroll scrollable containers backward (innermost first).
     */
    fun scrollBackward(root: AccessibilityNodeInfo?): Boolean {
        if (root == null) return false
        val scrollables = findAllByPredicate(root) { it.isScrollable }
        for (scrollable in scrollables.reversed()) {
            if (scrollable.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)) {
                return true
            }
        }
        return false
    }

    /**
     * Breadth-first search for first node matching predicate.
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
     * Breadth-first search for all nodes matching predicate.
     */
    fun findAllByPredicate(
        root: AccessibilityNodeInfo,
        predicate: (AccessibilityNodeInfo) -> Boolean
    ): List<AccessibilityNodeInfo> {
        val matches = mutableListOf<AccessibilityNodeInfo>()
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)

        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            if (predicate(current)) matches.add(current)

            for (i in 0 until current.childCount) {
                current.getChild(i)?.let { queue.add(it) }
            }
        }
        return matches
    }
}

