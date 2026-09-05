package com.settingslens.app.data

import com.settingslens.app.model.NavigationPath
import com.settingslens.app.model.ResolveResponse
import com.settingslens.app.model.SettingsGraph
import kotlinx.serialization.Serializable
import retrofit2.http.Body
import retrofit2.http.POST

/**
 * Retrofit interface for the Settings Lens backend API.
 */
interface ApiService {

    /**
     * Upload a crawled settings graph to the backend.
     * Called once after a crawl, and again if the user rebuilds the graph.
     */
    @POST("/graph")
    suspend fun uploadGraph(@Body graph: SettingsGraph): GraphUploadResponse

    /**
     * Resolve a voice transcript against a stored graph.
     * Sends only graph_id + transcript (NOT the full graph).
     */
    @POST("/resolve")
    suspend fun resolve(@Body request: ResolveRequest): ResolveResponse
}

/**
 * Request body for the /resolve endpoint.
 */
@Serializable
data class ResolveRequest(
    val graphId: String,
    val transcript: String,
    val conversationState: String? = null
)

/**
 * Response from the /graph upload endpoint.
 */
@Serializable
data class GraphUploadResponse(
    val graphId: String,
    val nodeCount: Int,
    val message: String
)
