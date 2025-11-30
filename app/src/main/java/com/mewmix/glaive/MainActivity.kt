package com.mewmix.glaive

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.mewmix.glaive.ui.GlaiveScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        com.mewmix.glaive.core.DebugLogger.init(this)
        
        // CHECK: Do we have total control?
        if (!hasAllFilesAccess()) {
            requestAllFilesAccess()
        }

        setContent {
            GlaiveScreen()
        }
    }

    override fun onResume() {
        super.onResume()
        // Optional: Refresh list if user just returned from granting permission
        if (hasAllFilesAccess()) {
            // Trigger a refresh event if your UI needs it
        }
    }

    private fun hasAllFilesAccess(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            // For older Android versions, standard permissions suffice
            true 
        }
    }

    private fun requestAllFilesAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                intent.addCategory("android.intent.category.DEFAULT")
                intent.data = Uri.parse("package:$packageName")
                startActivity(intent)
            } catch (e: Exception) {
                val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                startActivity(intent)
            }
        }
    }
}
