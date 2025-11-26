package com.mewmix.glaive.core

import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object FileOperations {

    suspend fun zip(files: List<File>, destZip: File): Boolean = withContext(Dispatchers.IO) {
        DebugLogger.logSuspend("Zipping ${files.size} files to ${destZip.path}") {
            try {
                ZipOutputStream(BufferedOutputStream(FileOutputStream(destZip))).use { zos ->
                    files.forEach { file ->
                        if (file.isDirectory) {
                            zipDirectory(zos, file, file.parent)
                        } else {
                            zipFile(zos, file, file.parent)
                        }
                    }
                }
                true
            } catch (e: Exception) {
                e.printStackTrace()
                false
            }
        }
    }

    private fun zipDirectory(zos: ZipOutputStream, folder: File, basePath: String?) {
        val path = if (basePath != null) folder.path.substring(basePath.length + 1) else folder.name
        zos.putNextEntry(ZipEntry(path + "/"))
        zos.closeEntry()
        folder.listFiles()?.forEach { file ->
            if (file.isDirectory) {
                zipDirectory(zos, file, basePath)
            } else {
                zipFile(zos, file, basePath)
            }
        }
    }

    private fun zipFile(zos: ZipOutputStream, file: File, basePath: String?) {
        val path = if (basePath != null) file.path.substring(basePath.length + 1) else file.name
        val entry = ZipEntry(path)
        zos.putNextEntry(entry)
        FileInputStream(file).use { fis ->
            BufferedInputStream(fis).use { bis ->
                bis.copyTo(zos)
            }
        }
        zos.closeEntry()
    }


    suspend fun copy(source: File, destDir: File): Boolean = withContext(Dispatchers.IO) {
        DebugLogger.logSuspend("Copying ${source.path} to ${destDir.path}") {
            try {
                if (source.isDirectory) {
                    source.copyRecursively(File(destDir, source.name), overwrite = true)
                } else {
                    source.copyTo(File(destDir, source.name), overwrite = true)
                    true
                }
            } catch (e: Exception) {
                e.printStackTrace()
                false
            }
        }
    }

    suspend fun move(source: File, destDir: File): Boolean = withContext(Dispatchers.IO) {
        DebugLogger.logSuspend("Moving ${source.path} to ${destDir.path}") {
            try {
                val destFile = File(destDir, source.name)
                // Try atomic move first
                if (source.renameTo(destFile)) return@logSuspend true

                // Fallback: Copy then Delete
                if (copy(source, destDir)) {
                    delete(source)
                } else {
                    false
                }
            } catch (e: Exception) {
                e.printStackTrace()
                false
            }
        }
    }

    suspend fun delete(target: File): Boolean = withContext(Dispatchers.IO) {
        DebugLogger.logSuspend("Deleting ${target.path}") {
            try {
                target.deleteRecursively()
            } catch (e: Exception) {
                e.printStackTrace()
                false
            }
        }
    }

    suspend fun createFile(parent: File, name: String, content: String = ""): Boolean = withContext(Dispatchers.IO) {
        DebugLogger.logSuspend("Creating file $name in ${parent.path}") {
            try {
                val file = File(parent, name)
                if (file.exists()) return@logSuspend false
                file.writeText(content)
                true
            } catch (e: Exception) {
                e.printStackTrace()
                false
            }
        }
    }

    suspend fun createDir(parent: File, name: String): Boolean = withContext(Dispatchers.IO) {
        DebugLogger.logSuspend("Creating directory $name in ${parent.path}") {
            try {
                val dir = File(parent, name)
                if (dir.exists()) return@logSuspend false
                dir.mkdirs()
            } catch (e: Exception) {
                e.printStackTrace()
                false
            }
        }
    }
}
