package com.settingslens.app.ui

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.accessibility.AccessibilityManager
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.settingslens.app.R
import com.settingslens.app.accessibility.SettingsAccessibilityService

/**
 * Onboarding activity — walks the user through the three required permissions:
 *
 * 1. RECORD_AUDIO — standard runtime permission
 * 2. SYSTEM_ALERT_WINDOW (overlay) — requires jumping to system settings
 * 3. Accessibility Service — requires jumping to system settings + manual toggle
 *
 * Each step shows its status (granted/not granted) and a button to request it.
 * Once all three are granted, the user can proceed to the main activity.
 */
class OnboardingActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "Onboarding"
    }

    private lateinit var step1Status: ImageView
    private lateinit var step1Button: Button
    private lateinit var step2Status: ImageView
    private lateinit var step2Button: Button
    private lateinit var step3Status: ImageView
    private lateinit var step3Button: Button
    private lateinit var step3Note: TextView
    private lateinit var continueButton: Button

    private val audioPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        updateUI()
        if (!granted) {
            Toast.makeText(this, "Microphone permission is needed for voice commands", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_onboarding)

        step1Status = findViewById(R.id.step1Status)
        step1Button = findViewById(R.id.step1Button)
        step2Status = findViewById(R.id.step2Status)
        step2Button = findViewById(R.id.step2Button)
        step3Status = findViewById(R.id.step3Status)
        step3Button = findViewById(R.id.step3Button)
        step3Note = findViewById(R.id.step3Note)
        continueButton = findViewById(R.id.continueButton)

        step1Button.setOnClickListener { requestAudioPermission() }
        step2Button.setOnClickListener { requestOverlayPermission() }
        step3Button.setOnClickListener { requestAccessibilityPermission() }
        continueButton.setOnClickListener {
            startActivity(Intent(this, MainActivity::class.java))
            finish()
        }

        updateUI()
    }

    override fun onResume() {
        super.onResume()
        updateUI()
    }

    private fun updateUI() {
        val audioGranted = hasAudioPermission()
        val overlayGranted = hasOverlayPermission()
        val a11yGranted = isAccessibilityServiceEnabled()

        updateStep(step1Status, step1Button, audioGranted)
        updateStep(step2Status, step2Button, overlayGranted)
        updateStep(step3Status, step3Button, a11yGranted)

        // Show Android 13+ restricted settings note
        if (Build.VERSION.SDK_INT >= 33) {
            step3Note.visibility = View.VISIBLE
        } else {
            step3Note.visibility = View.GONE
        }

        // Enable continue button only when all permissions are granted
        continueButton.isEnabled = audioGranted && overlayGranted && a11yGranted
        continueButton.alpha = if (continueButton.isEnabled) 1.0f else 0.4f
    }

    private fun updateStep(statusIcon: ImageView, button: Button, granted: Boolean) {
        if (granted) {
            statusIcon.setImageResource(android.R.drawable.ic_menu_gallery) // Checkmark placeholder
            statusIcon.setColorFilter(ContextCompat.getColor(this, android.R.color.holo_green_dark))
            button.isEnabled = false
            button.alpha = 0.5f
        } else {
            statusIcon.setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
            statusIcon.setColorFilter(ContextCompat.getColor(this, android.R.color.holo_red_light))
            button.isEnabled = true
            button.alpha = 1.0f
        }
    }

    // ─── Permission checks ──────────────────────────────────────────────

    private fun hasAudioPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun hasOverlayPermission(): Boolean {
        return Settings.canDrawOverlays(this)
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val am = getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        val enabledServices = am.getEnabledAccessibilityServiceList(
            AccessibilityServiceInfo.FEEDBACK_GENERIC
        )
        return enabledServices.any {
            it.resolveInfo.serviceInfo.packageName == packageName
        }
    }

    // ─── Permission requests ────────────────────────────────────────────

    private fun requestAudioPermission() {
        audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    private fun requestOverlayPermission() {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName")
        )
        startActivity(intent)
        Toast.makeText(this, "Enable 'Allow display over other apps'", Toast.LENGTH_LONG).show()
    }

    private fun requestAccessibilityPermission() {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        startActivity(intent)
        Toast.makeText(
            this,
            "Find 'Settings Lens' in the list and enable it",
            Toast.LENGTH_LONG
        ).show()
    }
}
