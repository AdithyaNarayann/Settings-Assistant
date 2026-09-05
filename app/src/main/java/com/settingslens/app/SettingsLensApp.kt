package com.settingslens.app

import android.app.Application
import android.util.Log

class SettingsLensApp : Application() {
    
    companion object {
        const val TAG = "SettingsLens"
    }
    
    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "Settings Lens application initialized")
    }
}
