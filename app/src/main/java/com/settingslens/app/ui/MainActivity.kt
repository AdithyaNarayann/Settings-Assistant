package com.settingslens.app.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.settingslens.app.R
import com.settingslens.app.accessibility.CrawlEngine
import com.settingslens.app.accessibility.SettingsAccessibilityService
import com.settingslens.app.data.ApiClient
import com.settingslens.app.data.GraphStorage
import com.settingslens.app.model.SettingsGraph
import com.settingslens.app.model.SettingsNode
import com.settingslens.app.overlay.BubbleService
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.ConnectException

/**
 * Main activity for Settings Lens.
 *
 * Resilience & Diagnostics:
 * - Direct status indicators for accessibility service, overlay permission, and graph discovery.
 * - CoroutineExceptionHandler guards Main Looper against network or persistence failures.
 * - Human-first diagnostic logging traces crawl initiation, backend sync, and overlay lifecycle.
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "SettingsLens:UI"
        private const val PREFS_NAME = "settings_lens_prefs"
        private const val KEY_ONBOARDED = "has_onboarded"
    }

    private lateinit var statusText: TextView
    private lateinit var crawlButton: Button
    private lateinit var startBubbleButton: Button
    private lateinit var permissionsButton: Button
    private lateinit var enableA11yButton: Button
    private lateinit var progressBar: ProgressBar
    private lateinit var nodeCountText: TextView
    private lateinit var graphRecyclerView: RecyclerView
    private lateinit var graphStorage: GraphStorage

    private var crawlJob: Job? = null

    private val activityExceptionHandler = CoroutineExceptionHandler { _, throwable ->
        Log.e(TAG, "🚨 [Main Looper Guard] Caught unhandled coroutine error: ${throwable.localizedMessage}", throwable)
        progressBar.visibility = View.GONE
        crawlButton.isEnabled = true
        statusText.text = "Notice: Operation paused (${throwable.localizedMessage})"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        graphStorage = GraphStorage(this)

        // Bind views
        statusText = findViewById(R.id.statusText)
        crawlButton = findViewById(R.id.crawlButton)
        startBubbleButton = findViewById(R.id.startBubbleButton)
        permissionsButton = findViewById(R.id.permissionsButton)
        enableA11yButton = findViewById(R.id.enableA11yButton)
        progressBar = findViewById(R.id.progressBar)
        nodeCountText = findViewById(R.id.nodeCountText)
        graphRecyclerView = findViewById(R.id.graphRecyclerView)

        graphRecyclerView.layoutManager = LinearLayoutManager(this)

        // Set up buttons
        crawlButton.setOnClickListener { startCrawl() }
        enableA11yButton.setOnClickListener { openAccessibilitySettings() }
        startBubbleButton.setOnClickListener { toggleBubble() }
        permissionsButton.setOnClickListener {
            startActivity(Intent(this, OnboardingActivity::class.java))
        }

        checkFirstLaunch()
        updateUI()
    }

    override fun onResume() {
        super.onResume()
        updateUI()
    }

    private fun checkFirstLaunch() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val hasOnboarded = prefs.getBoolean(KEY_ONBOARDED, false)
        val hasAudio = ContextCompat.checkSelfPermission(
            this, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        val hasOverlay = Settings.canDrawOverlays(this)
        val hasA11y = SettingsAccessibilityService.isRunning

        if (!hasOnboarded && (!hasAudio || !hasOverlay || !hasA11y)) {
            Log.i(TAG, "👋 [First Launch] Missing required permissions; navigating to Onboarding wizard.")
            prefs.edit().putBoolean(KEY_ONBOARDED, true).apply()
            startActivity(Intent(this, OnboardingActivity::class.java))
        }
    }

    private fun updateUI() {
        val serviceRunning = SettingsAccessibilityService.isRunning
        val hasOverlay = Settings.canDrawOverlays(this)

        // Accessibility service status
        if (serviceRunning) {
            enableA11yButton.visibility = View.GONE
            crawlButton.isEnabled = true
            statusText.text = "✅ Accessibility Service is enabled and ready"
        } else {
            enableA11yButton.visibility = View.VISIBLE
            crawlButton.isEnabled = false
            statusText.text = "⚠️ Accessibility Service is required.\nTap the button below to enable it in Settings."
        }

        // Overlay bubble button
        startBubbleButton.isEnabled = hasOverlay && (graphStorage.hasGraph())
        if (!hasOverlay) {
            startBubbleButton.text = "Enable Overlay to Start Bubble"
        } else {
            startBubbleButton.text = "Start Floating Bubble"
        }

        // Load existing graph if available
        val graph = graphStorage.loadGraph()
        if (graph != null) {
            val details = if (graph.graphId != null) {
                "${graph.nodeCount} settings mapped across ${graph.screenSignatures.size} screens • Cloud ID: ${graph.graphId}"
            } else {
                "${graph.nodeCount} settings mapped across ${graph.screenSignatures.size} screens"
            }
            nodeCountText.text = details
            nodeCountText.visibility = View.VISIBLE
            crawlButton.text = getString(R.string.rebuild_button)
            displayGraph(graph)
        } else {
            nodeCountText.visibility = View.GONE
            crawlButton.text = getString(R.string.crawl_button)
        }
    }

    private fun toggleBubble() {
        if (!Settings.canDrawOverlays(this)) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                android.net.Uri.parse("package:$packageName")
            )
            startActivity(intent)
            Toast.makeText(this, "Grant overlay permission to use the floating bubble", Toast.LENGTH_LONG).show()
            return
        }

        BubbleService.start(this)
        Toast.makeText(this, "Floating bubble active! Tap it anytime to speak.", Toast.LENGTH_SHORT).show()
    }

    private fun startCrawl() {
        val service = SettingsAccessibilityService.instance
        if (service == null) {
            val msg = "Please enable the Settings Lens Accessibility Service first."
            Log.w(TAG, "⚠️ [Service Not Running] $msg")
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
            return
        }

        crawlButton.isEnabled = false
        progressBar.visibility = View.VISIBLE
        statusText.text = getString(R.string.crawl_in_progress)

        crawlJob = service.startCrawl(object : CrawlEngine.CrawlListener {
            override fun onProgress(nodesDiscovered: Int, currentScreen: String?) {
                runOnUiThread {
                    statusText.text = "Mapping screen: ${currentScreen ?: "Settings"}\n$nodesDiscovered settings discovered so far..."
                }
            }

            override fun onComplete(graph: SettingsGraph) {
                runOnUiThread {
                    progressBar.visibility = View.GONE
                    crawlButton.isEnabled = true
                    statusText.text = getString(R.string.crawl_complete, graph.nodeCount)
                    nodeCountText.text = "${graph.nodeCount} settings mapped • ${graph.screenSignatures.size} screens"
                    nodeCountText.visibility = View.VISIBLE
                    crawlButton.text = getString(R.string.rebuild_button)
                    displayGraph(graph)

                    Toast.makeText(
                        this@MainActivity,
                        "Graph built: ${graph.nodeCount} settings discovered!",
                        Toast.LENGTH_SHORT
                    ).show()

                    // Upload graph to backend in background
                    uploadGraphToBackend(graph)
                }
            }

            override fun onError(error: String) {
                runOnUiThread {
                    progressBar.visibility = View.GONE
                    crawlButton.isEnabled = true
                    statusText.text = getString(R.string.crawl_failed, error)
                    Toast.makeText(this@MainActivity, "Crawl notice: $error", Toast.LENGTH_LONG).show()
                }
            }
        })
    }

    private fun uploadGraphToBackend(graph: SettingsGraph) {
        lifecycleScope.launch(activityExceptionHandler) {
            try {
                statusText.text = "Syncing graph with backend assistant..."
                val response = withContext(Dispatchers.IO) {
                    ApiClient.api.uploadGraph(graph)
                }
                graph.graphId = response.graphId
                graphStorage.saveGraph(graph)
                Log.i(TAG, "☁️ [Graph Synced] Uploaded successfully. Assigned Graph ID: ${response.graphId}")
                statusText.text = "Settings graph active and ready! (Cloud ID: ${response.graphId})"
                nodeCountText.text = "${graph.nodeCount} settings mapped • Cloud ID: ${response.graphId}"

                // Auto-start bubble if overlay permission is granted
                if (Settings.canDrawOverlays(this@MainActivity)) {
                    BubbleService.start(this@MainActivity)
                }
            } catch (e: ConnectException) {
                Log.i(TAG, "ℹ️ [Backend Offline] Graph saved locally. Offline keyword matching remains active.")
                statusText.text = "Settings saved locally. (Backend server offline; local navigation ready)"
            } catch (e: Exception) {
                Log.w(TAG, "ℹ️ [Sync Notice] Graph saved locally: ${e.localizedMessage}")
                statusText.text = "Settings saved locally. (Sync notice: ${e.localizedMessage})"
            }
        }
    }

    private fun displayGraph(graph: SettingsGraph) {
        graphRecyclerView.adapter = GraphAdapter(graph.nodes)
    }

    private fun openAccessibilitySettings() {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(intent)
        Toast.makeText(
            this,
            "Find 'Settings Lens' in the accessibility list and turn it ON",
            Toast.LENGTH_LONG
        ).show()
    }

    override fun onDestroy() {
        crawlJob?.cancel()
        super.onDestroy()
    }

    // ─── Simple graph adapter for development verification ──────────────

    private class GraphAdapter(
        private val nodes: List<SettingsNode>
    ) : RecyclerView.Adapter<GraphAdapter.NodeViewHolder>() {

        class NodeViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val label: TextView = view.findViewById(R.id.nodeLabel)
            val details: TextView = view.findViewById(R.id.nodeDetails)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): NodeViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_graph_node, parent, false)
            return NodeViewHolder(view)
        }

        override fun onBindViewHolder(holder: NodeViewHolder, position: Int) {
            val node = nodes[position]

            val indent = "    ".repeat(node.depth)
            holder.label.text = "$indent${node.label}"

            val details = buildString {
                append("depth=${node.depth}")
                if (node.subtitle != null) append(" • ${node.subtitle}")
                if (node.directIntentAction != null) append(" • ⚡direct")
                append(" • ${node.selector}")
            }
            holder.details.text = details
        }

        override fun getItemCount() = nodes.size
    }
}
