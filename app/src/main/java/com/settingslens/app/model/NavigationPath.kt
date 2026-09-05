package com.settingslens.app.model

import kotlinx.serialization.Serializable

/**
 * An ordered sequence of navigation steps to reach a target setting.
 * Returned by the backend after resolving a user's voice query.
 */
@Serializable
data class NavigationPath(
    /** If non-null, launch this intent action directly for the first hop. */
    val directIntentAction: String? = null,
    
    /** Ordered list of navigation steps. */
    val steps: List<NavigationStep>
)

/**
 * A single step in a navigation path.
 */
@Serializable
data class NavigationStep(
    /** Expected screen signature (hint for verification). */
    val screenSignatureHint: String? = null,
    
    /** Selector to find and click the target node on this screen. */
    val clickTarget: NodeSelector,
    
    /** Human-readable label for debugging. */
    val label: String? = null
)
