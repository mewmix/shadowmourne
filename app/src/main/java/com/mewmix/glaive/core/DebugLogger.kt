package com.mewmix.glaive.core

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue

object DebugLogger {
    private const val TAG = "GlaiveDebug"
    private var logFile: File? = null
    private val memoryLog = ConcurrentLinkedQueue<String>()
    private const val MAX_MEMORY_LOGS = 1000
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    fun init(context: Context) {
        try {
            val dir = context.getExternalFilesDir(null) ?: context.filesDir
            logFile = File(dir, "glaive_debug.log")
            log("DebugLogger initialized. Log file: ${logFile?.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize file logger", e)
        }
    }

    fun log(message: String) {
        val timestamp = dateFormat.format(Date())
        val formattedMessage = "$timestamp: $message"

        // Logcat
        Log.d(TAG, message)

        // Memory
        memoryLog.add(formattedMessage)
        while (memoryLog.size > MAX_MEMORY_LOGS) {
            memoryLog.poll()
        }

        // File
        logFile?.let { file ->
            try {
                // Synchronized to prevent race conditions on file write
                synchronized(this) {
                    FileWriter(file, true).use { fw ->
                        fw.appendLine(formattedMessage)
                    }
                }
            } catch (e: Exception) {
                // Ignore file write errors
            }
        }
    }

    fun log(message: String, t: Throwable) {
        log("$message\n${Log.getStackTraceString(t)}")
    }

    fun <T> log(message: String, block: () -> T): T {
        log("$message: Starting")
        try {
            val result = block()
            log("$message: Finished")
            return result
        } catch (t: Throwable) {
            log("$message: Failed", t)
            throw t
        }
    }

    suspend fun <T> logSuspend(message: String, block: suspend () -> T): T {
        log("$message: Starting")
        try {
            val result = block()
            log("$message: Finished")
            return result
        } catch (t: Throwable) {
            log("$message: Failed", t)
            throw t
        }
    }

    fun getLogs(): String {
        return memoryLog.joinToString("\n")
    }

    fun getLogFile(): File? = logFile

    fun clearLogs() {
        memoryLog.clear()
        logFile?.delete()
        log("Logs cleared")
    }
}
