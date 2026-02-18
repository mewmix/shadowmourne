package com.mewmix.glaive.bridge

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Environment
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.mewmix.glaive.core.NativeCore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class BridgeActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val intent = intent
        if (intent == null || intent.action != BridgeConstants.ACTION_EXECUTE_TOOL) {
            finish()
            return
        }

        val toolName = intent.getStringExtra(BridgeConstants.EXTRA_TOOL_NAME)
        val paramsJson = intent.getStringExtra(BridgeConstants.EXTRA_TOOL_PARAMS)

        if (toolName == null) {
            finishWithResult(error = "Tool name missing")
            return
        }

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val params = if (paramsJson != null) JSONObject(paramsJson) else JSONObject()
                val result = executeTool(toolName, params)
                withContext(Dispatchers.Main) {
                    finishWithResult(result = result)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    finishWithResult(error = e.message ?: "Unknown error")
                }
            }
        }
    }

    private suspend fun executeTool(toolName: String, params: JSONObject): String {
        return when (toolName) {
            "list_files" -> {
                val path = normalizeToolPath(params.optString("path"))
                if (path.isEmpty()) throw IllegalArgumentException("Path is required")
                requireStorageAccess(path, "list files")
                val file = File(path)
                if (!file.exists()) throw IllegalArgumentException("Path not found: $path")
                if (!file.isDirectory) throw IllegalArgumentException("Path is not a directory: $path")
                val files = file.listFiles()
                    ?: throw SecurityException("Permission Denial: Cannot list directory $path")
                val jsonArray = JSONArray()
                files.forEach { f ->
                    val obj = JSONObject()
                    obj.put("name", f.name)
                    obj.put("path", f.absolutePath)
                    obj.put("isDirectory", f.isDirectory)
                    obj.put("length", f.length())
                    obj.put("lastModified", f.lastModified())
                    jsonArray.put(obj)
                }
                jsonArray.toString()
            }
            "read_file" -> {
                val path = normalizeToolPath(params.optString("path"))
                if (path.isEmpty()) throw IllegalArgumentException("Path is required")
                requireStorageAccess(path, "read file")
                val file = File(path)
                if (!file.exists()) throw IllegalArgumentException("File not found: $path")
                if (!file.isFile) throw IllegalArgumentException("Path is not a file: $path")
                if (!file.canRead()) throw SecurityException("Permission Denial: Cannot read file $path")
                if (file.length() > 1024 * 1024) throw IllegalArgumentException("File too large (max 1MB)")
                file.readText()
            }
            "write_file" -> {
                val path = normalizeToolPath(params.optString("path"))
                val content = params.optString("content")
                if (path.isEmpty()) throw IllegalArgumentException("Path is required")
                requireStorageAccess(path, "write file")
                val file = File(path)
                file.parentFile?.let { parent ->
                    if (!parent.exists() && !parent.mkdirs()) {
                        throw IllegalStateException("Failed to create parent directory: ${parent.absolutePath}")
                    }
                }
                file.writeText(content)
                "Success"
            }
            "create_dir" -> {
                val path = normalizeToolPath(params.optString("path"))
                if (path.isEmpty()) throw IllegalArgumentException("Path is required")
                requireStorageAccess(path, "create directory")
                if (File(path).mkdirs()) "Success" else "Failed to create directory (might already exist)"
            }
            "delete_file" -> {
                val path = normalizeToolPath(params.optString("path"))
                if (path.isEmpty()) throw IllegalArgumentException("Path is required")
                requireStorageAccess(path, "delete file")
                if (File(path).deleteRecursively()) "Success" else "Failed to delete"
            }
            "search_files" -> {
                val rootPath = normalizeToolPath(params.optString("root_path"))
                val query = params.optString("query")
                if (rootPath.isEmpty()) throw IllegalArgumentException("Root path is required")
                if (query.isEmpty()) throw IllegalArgumentException("Query is required")
                requireStorageAccess(rootPath, "search files")

                // Using NativeCore.search
                val results = NativeCore.search(rootPath, query)
                val jsonArray = JSONArray()
                results.forEach { item ->
                    val obj = JSONObject()
                    obj.put("name", item.name)
                    obj.put("path", item.path)
                    obj.put("type", item.type)
                    obj.put("size", item.size)
                    obj.put("mtime", item.mtime)
                    jsonArray.put(obj)
                }
                jsonArray.toString()
            }
            else -> throw IllegalArgumentException("Unknown tool: $toolName")
        }
    }

    private fun normalizeToolPath(rawPath: String): String {
        val trimmed = rawPath.trim().trim('"')
        if (trimmed.isEmpty()) return ""

        return when (trimmed.lowercase()) {
            "downloads", "/downloads", "/sdcard/downloads", "/storage/emulated/0/downloads" ->
                "/sdcard/Download"
            "documents", "/documents", "/sdcard/documents", "/storage/emulated/0/documents" ->
                "/sdcard/Documents"
            "pictures", "/pictures", "/sdcard/pictures", "/storage/emulated/0/pictures" ->
                "/sdcard/Pictures"
            "music", "/music", "/sdcard/music", "/storage/emulated/0/music" ->
                "/sdcard/Music"
            else -> trimmed
        }
    }

    private fun requireStorageAccess(path: String, purpose: String) {
        val normalized = path.lowercase()
        val isSharedStorage = normalized.startsWith("/sdcard") ||
            normalized.startsWith("/storage/emulated/0")

        if (isSharedStorage && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                throw SecurityException(
                    "Permission Denial: Glaive needs 'All files access' to $purpose at $path. " +
                        "Open Glaive and grant it in Settings > Apps > Glaive > All files access."
                )
            }
        }
    }

    private fun finishWithResult(result: String? = null, error: String? = null) {
        val resultIntent = Intent()
        if (error != null) {
            resultIntent.putExtra(BridgeConstants.EXTRA_TOOL_ERROR, error)
        } else {
            resultIntent.putExtra(BridgeConstants.EXTRA_TOOL_RESULT, result)
        }
        setResult(Activity.RESULT_OK, resultIntent)
        finish()
    }
}
