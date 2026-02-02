package com.mewmix.glaive.core

import com.mewmix.glaive.data.GlaiveItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.ByteOrder

object NativeCore {
    init {
        System.loadLibrary("glaive_core")
    }

    private val sharedBuffer: ByteBuffer =
        ByteBuffer.allocateDirect(4 * 1024 * 1024).order(ByteOrder.LITTLE_ENDIAN)
    private val bufferLock = Any()

    private external fun nativeFillBuffer(path: String, buffer: ByteBuffer, capacity: Int, sortMode: Int, asc: Boolean, filterMask: Int): Int
    private external fun nativeSearch(root: String, query: String, buffer: ByteBuffer, capacity: Int, filterMask: Int): Int
    private external fun nativeCalculateDirectorySize(path: String): Long
    private external fun nativeRunBenchmark(path: String)
    private external fun nativeCancelSearch()
    private external fun nativeResetSearch()

    suspend fun calculateDirectorySize(path: String): Long = withContext(Dispatchers.IO) {
        nativeCalculateDirectorySize(path)
    }

    suspend fun runBenchmark(path: String) = withContext(Dispatchers.IO) {
        nativeRunBenchmark(path)
    }

    suspend fun list(currentPath: String, sortMode: Int = 0, asc: Boolean = true, filterMask: Int = 0): List<GlaiveItem> = withContext(Dispatchers.IO) {
        synchronized(bufferLock) {
            val filledBytes = nativeFillBuffer(currentPath, sharedBuffer, sharedBuffer.capacity(), sortMode, asc, filterMask)
            if (filledBytes <= 0) {
                emptyList()
            } else {
                // Copy to a new buffer to ensure stability (One-Copy)
                val stableBuffer = ByteBuffer.allocate(filledBytes).order(ByteOrder.LITTLE_ENDIAN)
                sharedBuffer.position(0)
                sharedBuffer.limit(filledBytes)
                stableBuffer.put(sharedBuffer)
                stableBuffer.rewind()
                GlaiveLazyList(stableBuffer, currentPath, filledBytes)
            }
        }
    }

    suspend fun search(root: String, query: String, filterMask: Int = 0): List<GlaiveItem> = withContext(Dispatchers.IO) {
        // Signal cancellation to any running search (on another thread)
        nativeCancelSearch()

        synchronized(bufferLock) {
            // Reset cancellation flag for this new search
            nativeResetSearch()

            val filledBytes = nativeSearch(root, query, sharedBuffer, sharedBuffer.capacity(), filterMask)
            if (filledBytes <= 0) {
                emptyList()
            } else {
                // Copy to a new buffer to ensure stability (One-Copy)
                val stableBuffer = ByteBuffer.allocate(filledBytes).order(ByteOrder.LITTLE_ENDIAN)
                sharedBuffer.position(0)
                sharedBuffer.limit(filledBytes)
                stableBuffer.put(sharedBuffer)
                stableBuffer.rewind()
                GlaiveLazyList(stableBuffer, root, filledBytes)
            }
        }
    }
}
