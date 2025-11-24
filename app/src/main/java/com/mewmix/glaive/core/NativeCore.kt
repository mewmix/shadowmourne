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

            var type = typeRaw
            // Fix: Handle DT_LNK (10) or DT_UNKNOWN (0) or any mismatch
            // If it's not explicitly marked as a dir, check the filesystem to be sure.
            // This is crucial for symlinks to directories.
            if (type != GlaiveItem.TYPE_DIR) {
                if (java.io.File(fullPath).isDirectory) {
                    type = GlaiveItem.TYPE_DIR
                } else if (type == 0) {
                     type = GlaiveItem.TYPE_FILE
                }
            }

            // Refine type based on extension if it's a file
            if (type == GlaiveItem.TYPE_FILE) {
                type = getRefinedType(name)
            }

            list.add(GlaiveItem(
                name = name,
                path = fullPath,
                type = type, // Map your C types to Kotlin constants
                size = size,
                mtime = java.io.File(fullPath).lastModified() // Populate mtime
            ))
        }
        
        // Sort in Kotlin. TimSort is optimized for partially sorted arrays.
        // list.sortWith(compareBy({ it.type != GlaiveItem.TYPE_DIR }, { it.name.lowercase() }))
        list
    }

    suspend fun search(root: String, query: String): List<GlaiveItem> = withContext(Dispatchers.IO) {
        val results = nativeSearch(root, query)?.filterNotNull() ?: return@withContext emptyList()
        results.map { item ->
            var type = item.type
            if (type == 0) {
                val isDir = java.io.File(item.path).isDirectory
                type = if (isDir) GlaiveItem.TYPE_DIR else GlaiveItem.TYPE_FILE
            }
            
            if (type == GlaiveItem.TYPE_FILE) {
                type = getRefinedType(item.name)
            }
            
            item.copy(type = type)
        }
    }

    private fun getRefinedType(name: String): Int {
        val ext = name.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "jpg", "jpeg", "png", "gif", "webp", "bmp" -> GlaiveItem.TYPE_IMG
            "mp4", "mkv", "webm", "avi", "mov", "3gp" -> GlaiveItem.TYPE_VID
            "apk" -> GlaiveItem.TYPE_APK
            "pdf", "doc", "docx", "txt", "md", "xls", "xlsx", "ppt", "pptx" -> GlaiveItem.TYPE_DOC
            else -> GlaiveItem.TYPE_FILE
        }
    }
}
