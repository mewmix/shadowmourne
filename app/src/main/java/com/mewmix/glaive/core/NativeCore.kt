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

    private external fun nativeListBuffer(path: String): ByteArray?
    private external fun nativeSearch(root: String, query: String): Array<GlaiveItem>?

    suspend fun list(currentPath: String): List<GlaiveItem> = withContext(Dispatchers.IO) {
        val rawData = nativeListBuffer(currentPath) ?: return@withContext emptyList()
        val buffer = ByteBuffer.wrap(rawData).order(ByteOrder.LITTLE_ENDIAN)
        val list = ArrayList<GlaiveItem>(rawData.size / 64) // Heuristic sizing

        while (buffer.hasRemaining()) {
            val typeRaw = buffer.get().toInt()
            val nameLen = buffer.get().toInt() and 0xFF
            val nameBytes = ByteArray(nameLen)
            buffer.get(nameBytes)
            val size = buffer.getLong()

            val name = String(nameBytes)
            // Reconstruct full path here or lazily
            val fullPath = if (currentPath.endsWith("/")) "$currentPath$name" else "$currentPath/$name"

            list.add(GlaiveItem(
                name = name,
                path = fullPath,
                type = typeRaw, // Map your C types to Kotlin constants
                size = size,
                mtime = 0 // Skip time for speed unless crucial
            ))
        }
        
        // Sort in Kotlin. TimSort is optimized for partially sorted arrays.
        // list.sortWith(compareBy({ it.type != GlaiveItem.TYPE_DIR }, { it.name.lowercase() }))
        list
    }

    suspend fun search(root: String, query: String): List<GlaiveItem> = withContext(Dispatchers.IO) {
        nativeSearch(root, query)?.filterNotNull()?.toList() ?: emptyList()
    }
}
