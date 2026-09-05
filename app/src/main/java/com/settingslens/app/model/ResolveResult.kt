package com.settingslens.app.model

import kotlinx.serialization.Serializable

/**
 * Result from the backend's /resolve endpoint.
 * Either a resolved navigation path or a clarifying question.
 */
@Serializable
data class ResolveResponse(
    /** "resolved" or "clarification" */
    val type: String,
    
    /** Present when type == "resolved" */
    val path: NavigationPath? = null,
    
    /** Confidence score 0.0-1.0 (present when type == "resolved") */
    val confidence: Double? = null,
    
    /** Clarifying question text (present when type == "clarification") */
    val question: String? = null,
    
    /** Conversation state to send back with the next request */
    val conversationState: String? = null
) {
    val isResolved: Boolean get() = type == "resolved" && path != null
    val isClarification: Boolean get() = type == "clarification" && question != null
}
