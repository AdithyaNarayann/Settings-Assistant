package com.settingslens.app.model

import kotlinx.serialization.Serializable

/**
 * Selector for re-finding a UI node in a live accessibility tree.
 * At least one field should be non-null. During navigation, we try
 * resource-id first (most stable across OS updates), then exact text,
 * then content description.
 */
@Serializable
data class NodeSelector(
    val resourceId: String? = null,
    val text: String? = null,
    val contentDescription: String? = null
) {
    /**
     * Returns true if this selector has at least one usable field.
     */
    fun isValid(): Boolean = resourceId != null || text != null || contentDescription != null

    override fun toString(): String {
        return when {
            resourceId != null -> "res:$resourceId"
            text != null -> "text:$text"
            contentDescription != null -> "desc:$contentDescription"
            else -> "empty-selector"
        }
    }
}
