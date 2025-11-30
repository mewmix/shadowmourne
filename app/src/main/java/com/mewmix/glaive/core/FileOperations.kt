package com.mewmix.glaive.core

import com.mewmix.glaive.data.GlaiveItem
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

    fun isArchive(path: String): Boolean {
        return path.endsWith(".zip") ||
               path.endsWith(".tar") ||
               path.endsWith(".tar.zst") ||
               path.endsWith(".tzst") ||
               path.endsWith(".zst")
    }

    fun getArchiveRoot(path: String): String? {
        val extensions = listOf(".tar.zst", ".tzst", ".zip", ".tar", ".zst")
        for (ext in extensions) {
            val index = path.indexOf(ext)
            if (index != -1) {
                val endOfExt = index + ext.length
                if (endOfExt == path.length || path[endOfExt] == '/') {
                    return path.substring(0, endOfExt)
                }
            }
        }
        return null
    }

    suspend fun listArchive(path: String, internalPath: String): List<GlaiveItem> {
        if (path.endsWith(".zip")) {
            return listZip(path, internalPath)
        }
        return ArchiveUtils.listArchive(path, internalPath)
    }

    suspend fun createArchive(files: List<File>, destFile: File): Boolean {
        if (destFile.name.endsWith(".zip")) {
            return zip(files, destFile)
        }
        return ArchiveUtils.createArchive(files, destFile)
    }

    suspend fun extractArchive(archiveFile: File, destDir: File, entryPaths: List<String>? = null): Boolean {
        if (archiveFile.name.endsWith(".zip")) {
            // For Zip, we default to full unzip if entryPaths is null.
            if (entryPaths != null) {
                return unzipPartial(archiveFile, destDir, entryPaths)
            }
            return unzip(archiveFile, destDir)
        }
        return ArchiveUtils.extractArchive(archiveFile, destDir, entryPaths)
    }

    suspend fun addToArchive(archiveFile: File, files: List<File>, parentPath: String): Boolean {
        if (archiveFile.name.endsWith(".zip")) {
            return addToZip(archiveFile, files, parentPath)
        }
        return ArchiveUtils.addToArchive(archiveFile, files, parentPath)
    }

    suspend fun removeFromArchive(archiveFile: File, entryPaths: List<String>): Boolean {
        if (archiveFile.name.endsWith(".zip")) {
            return removeFromZip(archiveFile, entryPaths)
        }
        return ArchiveUtils.removeFromArchive(archiveFile, entryPaths)
    }

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

    suspend fun listZip(zipPath: String, internalPath: String): List<GlaiveItem> = withContext(Dispatchers.IO) {
        val list = mutableListOf<GlaiveItem>()
        try {
            java.util.zip.ZipFile(zipPath).use { zip ->
                val entries = zip.entries()
                val seenDirs = mutableSetOf<String>()
                
                // Normalize internal path: "folder/" or ""
                val prefix = if (internalPath.isEmpty() || internalPath == "/") "" else if (internalPath.endsWith("/")) internalPath else "$internalPath/"
                
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    val name = entry.name
                    
                    if (name.startsWith(prefix) && name != prefix) {
                        val relative = name.substring(prefix.length)
                        val slashIndex = relative.indexOf('/')
                        
                        if (slashIndex == -1) {
                            // File in this directory
                            list.add(GlaiveItem(
                                name = relative,
                                path = "$zipPath/$name", // Virtual path
                                type = if (entry.isDirectory) GlaiveItem.TYPE_DIR else GlaiveItem.TYPE_FILE,
                                size = entry.size,
                                mtime = entry.time
                            ))
                        } else {
                            // Subdirectory
                            val dirName = relative.substring(0, slashIndex)
                            if (seenDirs.add(dirName)) {
                                list.add(GlaiveItem(
                                    name = dirName,
                                    path = "$zipPath/$prefix$dirName", // Virtual path
                                    type = GlaiveItem.TYPE_DIR,
                                    size = 0,
                                    mtime = 0
                                ))
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        list
    }

    suspend fun unzip(zipFile: File, destDir: File): Boolean = withContext(Dispatchers.IO) {
        DebugLogger.logSuspend("Unzipping ${zipFile.path} to ${destDir.path}") {
            try {
                java.util.zip.ZipFile(zipFile).use { zip ->
                    val entries = zip.entries()
                    while (entries.hasMoreElements()) {
                        val entry = entries.nextElement()
                        val entryFile = File(destDir, entry.name)
                        
                        // Security check: Zip Slip
                        if (!entryFile.canonicalPath.startsWith(destDir.canonicalPath)) {
                            continue
                        }

                        if (entry.isDirectory) {
                            entryFile.mkdirs()
                        } else {
                            entryFile.parentFile?.mkdirs()
                            zip.getInputStream(entry).use { input ->
                                FileOutputStream(entryFile).use { output ->
                                    input.copyTo(output)
                                }
                            }
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

    private suspend fun unzipPartial(zipFile: File, destDir: File, entryPaths: List<String>): Boolean = withContext(Dispatchers.IO) {
        try {
            val targets = entryPaths.toSet()
            java.util.zip.ZipFile(zipFile).use { zip ->
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    // Check match
                    var shouldExtract = false
                    if (targets.contains(entry.name)) {
                        shouldExtract = true
                    } else if (targets.any { entry.name.startsWith("$it/") }) {
                        shouldExtract = true
                    }

                    if (shouldExtract) {
                        val entryFile = File(destDir, entry.name)
                        if (!entryFile.canonicalPath.startsWith(destDir.canonicalPath)) continue

                        if (entry.isDirectory) {
                            entryFile.mkdirs()
                        } else {
                            entryFile.parentFile?.mkdirs()
                            zip.getInputStream(entry).use { input ->
                                FileOutputStream(entryFile).use { output ->
                                    input.copyTo(output)
                                }
                            }
                        }
                    }
                }
            }
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    suspend fun addToZip(zipFile: File, files: List<File>, parentPathInZip: String = ""): Boolean = withContext(Dispatchers.IO) {
        val tempFile = File(zipFile.parent, "${zipFile.name}.tmp")
        try {
            ZipOutputStream(BufferedOutputStream(FileOutputStream(tempFile))).use { zos ->
                // Copy existing entries
                if (zipFile.exists()) {
                    java.util.zip.ZipFile(zipFile).use { zip ->
                        val entries = zip.entries()
                        while (entries.hasMoreElements()) {
                            val entry = entries.nextElement()
                            zos.putNextEntry(ZipEntry(entry.name))
                            zip.getInputStream(entry).copyTo(zos)
                            zos.closeEntry()
                        }
                    }
                }
                
                // Add new files
                val prefix = if (parentPathInZip.isNotEmpty() && !parentPathInZip.endsWith("/")) "$parentPathInZip/" else parentPathInZip
                files.forEach { file ->
                    if (file.isDirectory) {
                        zipDirectoryWithPrefix(zos, file, file.parent, prefix)
                    } else {
                        zipFileWithPrefix(zos, file, file.parent, prefix)
                    }
                }
            }
            if (zipFile.exists()) zipFile.delete()
            tempFile.renameTo(zipFile)
            true
        } catch (e: Exception) {
            e.printStackTrace()
            tempFile.delete()
            false
        }
    }

    private fun zipDirectoryWithPrefix(zos: ZipOutputStream, folder: File, basePath: String?, prefix: String) {
        val path = if (basePath != null) folder.path.substring(basePath.length + 1) else folder.name
        zos.putNextEntry(ZipEntry(prefix + path + "/"))
        zos.closeEntry()
        folder.listFiles()?.forEach { file ->
            if (file.isDirectory) {
                zipDirectoryWithPrefix(zos, file, basePath, prefix)
            } else {
                zipFileWithPrefix(zos, file, basePath, prefix)
            }
        }
    }

    private fun zipFileWithPrefix(zos: ZipOutputStream, file: File, basePath: String?, prefix: String) {
        val path = if (basePath != null) file.path.substring(basePath.length + 1) else file.name
        val entry = ZipEntry(prefix + path)
        zos.putNextEntry(entry)
        FileInputStream(file).use { fis ->
            BufferedInputStream(fis).use { bis ->
                bis.copyTo(zos)
            }
        }
        zos.closeEntry()
    }

    suspend fun removeFromZip(zipFile: File, entryPaths: List<String>): Boolean = withContext(Dispatchers.IO) {
        val tempFile = File(zipFile.parent, "${zipFile.name}.tmp")
        try {
            val entriesToRemove = entryPaths.toSet()
            ZipOutputStream(BufferedOutputStream(FileOutputStream(tempFile))).use { zos ->
                java.util.zip.ZipFile(zipFile).use { zip ->
                    val entries = zip.entries()
                    while (entries.hasMoreElements()) {
                        val entry = entries.nextElement()
                        // Check if this entry matches or is inside one of the removed paths
                        // entryPaths are full internal paths e.g. "folder/file.txt"
                        val shouldRemove = entriesToRemove.any { path -> 
                            entry.name == path || entry.name.startsWith("$path/")
                        }
                        
                        if (!shouldRemove) {
                            zos.putNextEntry(ZipEntry(entry.name))
                            zip.getInputStream(entry).copyTo(zos)
                            zos.closeEntry()
                        }
                    }
                }
            }
            zipFile.delete()
            tempFile.renameTo(zipFile)
            true
        } catch (e: Exception) {
            e.printStackTrace()
            tempFile.delete()
            false
        }
    }
}
