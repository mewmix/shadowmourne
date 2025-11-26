package com.mewmix.glaive.core

import android.util.Log

object DebugLogger {
    private const val TAG = "GlaiveDebug"

    fun log(message: String) {
        Log.d(TAG, message)
    }

    fun <T> log(message: String, block: () -> T): T {
        log("$message: Starting")
        val result = block()
        log("$message: Finished")
        return result
    }

    suspend fun <T> logSuspend(message: String, block: suspend () -> T): T {
        log("$message: Starting")
        val result = block()
        log("$message: Finished")
        return result
    }
}
