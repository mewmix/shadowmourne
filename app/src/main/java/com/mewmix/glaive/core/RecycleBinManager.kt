package com.mewmix.glaive.core

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Manages the Recycle Bin (.glaive_trash folder).
 * Stores metadata in a simple object file to map trash items back to their original paths.
 */
object RecycleBinManager {
    private const val TRASH_DIR_NAME = ".glaive_trash"
    private const val INDEX_FILE_NAME = "restore.index"

    // In-memory cache of the index: Map<TrashFileName, OriginalPath>
    private val index = ConcurrentHashMap<String, String>()
    private var isInitialized = false

    private fun getTrashDir(): File {
        val root = File("/storage/emulated/0") // Hardcoded root as per project convention
        return File(root, TRASH_DIR_NAME)
    }

    private suspend fun ensureInitialized() {
        if (!isInitialized) {
            loadIndex()
            isInitialized = true
        }
    }

    private suspend fun loadIndex() = withContext(Dispatchers.IO) {
        val trashDir = getTrashDir()
        if (!trashDir.exists()) trashDir.mkdirs()

        val indexFile = File(trashDir, INDEX_FILE_NAME)
        if (indexFile.exists()) {
            try {
                ObjectInputStream(FileInputStream(indexFile)).use { ois ->
                    @Suppress("UNCHECKED_CAST")
                    val loaded = ois.readObject() as Map<String, String>
                    index.putAll(loaded)
                }
            } catch (e: Exception) {
                DebugLogger.log("Failed to load recycle bin index", e)
                // If index is corrupt, we might lose restore paths, but files are safe.
                // We could rebuild index from filenames if we encoded path in filename,
                // but for now we accept the loss of restore metadata.
            }
        }
    }

    private suspend fun saveIndex() = withContext(Dispatchers.IO) {
        val trashDir = getTrashDir()
        if (!trashDir.exists()) trashDir.mkdirs()

        val indexFile = File(trashDir, INDEX_FILE_NAME)
        try {
            ObjectOutputStream(FileOutputStream(indexFile)).use { oos ->
                oos.writeObject(HashMap(index))
            }
        } catch (e: Exception) {
            DebugLogger.log("Failed to save recycle bin index", e)
        }
    }

    suspend fun moveToTrash(file: File): Boolean = withContext(Dispatchers.IO) {
        ensureInitialized()
        DebugLogger.logSuspend("Moving ${file.path} to Recycle Bin") {
            try {
                val trashDir = getTrashDir()
                if (!trashDir.exists()) trashDir.mkdirs()

                // Unique name: timestamp_filename
                val timestamp = System.currentTimeMillis()
                val trashName = "${timestamp}_${file.name}"
                val trashFile = File(trashDir, trashName)

                if (file.renameTo(trashFile)) {
                    index[trashName] = file.absolutePath
                    saveIndex()
                    true
                } else {
                    // Fallback to copy-delete
                     if (FileOperations.copy(file, trashDir)) {
                         val copiedFile = File(trashDir, file.name)
                         // We need to rename the copied file to match our trashName scheme
                         if (copiedFile.renameTo(trashFile)) {
                            FileOperations.delete(file)
                            index[trashName] = file.absolutePath
                            saveIndex()
                            true
                         } else {
                             // Failed to rename inside trash, cleanup
                             copiedFile.delete()
                             false
                         }
                     } else {
                         false
                     }
                }
            } catch (e: Exception) {
                DebugLogger.log("Error moving to trash", e)
                false
            }
        }
    }

    suspend fun restore(trashFile: File): Boolean = withContext(Dispatchers.IO) {
        ensureInitialized()
        DebugLogger.logSuspend("Restoring ${trashFile.name}") {
            try {
                val originalPath = index[trashFile.name] ?: return@logSuspend false
                val originalFile = File(originalPath)

                // Ensure parent exists
                originalFile.parentFile?.mkdirs()

                // Check collision
                val finalDest = if (originalFile.exists()) {
                    // Conflict: rename restored file
                    File(originalFile.parent, "restored_${originalFile.name}")
                } else {
                    originalFile
                }

                if (trashFile.renameTo(finalDest)) {
                    index.remove(trashFile.name)
                    saveIndex()
                    true
                } else {
                    // Fallback copy
                    if (FileOperations.copy(trashFile, finalDest.parentFile!!)) {
                         // copy puts it as trashFile.name, rename it
                         val temp = File(finalDest.parentFile, trashFile.name)
                         temp.renameTo(finalDest)

                         trashFile.delete()
                         index.remove(trashFile.name)
                         saveIndex()
                         true
                    } else {
                        false
                    }
                }
            } catch (e: Exception) {
                DebugLogger.log("Error restoring file", e)
                false
            }
        }
    }

    suspend fun deletePermanently(trashFile: File): Boolean = withContext(Dispatchers.IO) {
        ensureInitialized()
        DebugLogger.logSuspend("Permanently deleting ${trashFile.name}") {
            val result = FileOperations.delete(trashFile)
            if (result || !trashFile.exists()) {
                index.remove(trashFile.name)
                saveIndex()
                true
            } else {
                false
            }
        }
    }

    suspend fun emptyBin(): Boolean = withContext(Dispatchers.IO) {
        ensureInitialized()
        DebugLogger.logSuspend("Emptying Recycle Bin") {
            try {
                val trashDir = getTrashDir()
                trashDir.listFiles()?.forEach { file ->
                    if (file.name != INDEX_FILE_NAME) {
                        FileOperations.delete(file)
                    }
                }
                index.clear()
                saveIndex()
                true
            } catch (e: Exception) {
                DebugLogger.log("Error emptying bin", e)
                false
            }
        }
    }

    fun isTrashItem(path: String): Boolean {
        return path.contains("/${TRASH_DIR_NAME}/")
    }

    fun getTrashPath(): String {
        return getTrashDir().absolutePath
    }

    suspend fun getOriginalPath(trashFile: File): String? {
        ensureInitialized()
        return index[trashFile.name]
    }
}
