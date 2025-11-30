package com.mewmix.glaive.core

import com.github.luben.zstd.ZstdInputStream
import com.github.luben.zstd.ZstdOutputStream
import com.mewmix.glaive.data.GlaiveItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.*
import java.nio.charset.StandardCharsets
import java.util.Locale

object ArchiveUtils {

    private const val BLOCK_SIZE = 512
    private const val TYPE_FILE = '0'.code.toByte()
    private const val TYPE_DIR = '5'.code.toByte()

    suspend fun listArchive(path: String, internalPath: String): List<GlaiveItem> = withContext(Dispatchers.IO) {
        val list = mutableListOf<GlaiveItem>()
        val file = File(path)
        if (!file.exists()) return@withContext list

        val isZstd = path.endsWith(".zst") || path.endsWith(".tzst")
        val isTar = path.contains(".tar") || path.endsWith(".tzst")

        if (!isTar && isZstd) {
            val name = file.name.removeSuffix(".zst")
            list.add(GlaiveItem(
                name = name,
                path = "$path/$name",
                type = GlaiveItem.TYPE_FILE,
                size = 0,
                mtime = file.lastModified()
            ))
            return@withContext list
        }

        try {
            val fis = FileInputStream(file)
            val bis = BufferedInputStream(fis)
            val input = if (isZstd) ZstdInputStream(bis) else bis

            val entries = mutableListOf<TarEntry>()
            readTarEntries(input, entries)
            input.close()

            val prefix = if (internalPath.isEmpty() || internalPath == "/") "" else if (internalPath.endsWith("/")) internalPath else "$internalPath/"
            val seenDirs = mutableSetOf<String>()

            for (entry in entries) {
                if (entry.name.startsWith(prefix) && entry.name != prefix) {
                    val relative = entry.name.substring(prefix.length)
                    val slashIndex = relative.indexOf('/')

                    if (slashIndex == -1) {
                        val cleanName = relative.removeSuffix("/")
                        if (cleanName.isNotEmpty()) {
                             list.add(GlaiveItem(
                                name = cleanName,
                                path = "$path/$prefix$cleanName",
                                type = if (entry.isDirectory || relative.endsWith("/")) GlaiveItem.TYPE_DIR else GlaiveItem.TYPE_FILE,
                                size = entry.size,
                                mtime = entry.mtime * 1000
                            ))
                        }
                    } else {
                        val dirName = relative.substring(0, slashIndex)
                        if (seenDirs.add(dirName)) {
                            list.add(GlaiveItem(
                                name = dirName,
                                path = "$path/$prefix$dirName",
                                type = GlaiveItem.TYPE_DIR,
                                size = 0,
                                mtime = 0
                            ))
                        }
                    }
                }
            }
        } catch (e: Throwable) {
            DebugLogger.log("Error listing archive $path", e)
        }
        list
    }

    suspend fun extractArchive(archiveFile: File, destDir: File, entryPaths: List<String>? = null): Boolean = withContext(Dispatchers.IO) {
        val isZstd = archiveFile.name.endsWith(".zst") || archiveFile.name.endsWith(".tzst")
        val isTar = archiveFile.name.contains(".tar") || archiveFile.name.endsWith(".tzst")

        try {
            if (!isTar && isZstd) {
                val fis = FileInputStream(archiveFile)
                val zis = ZstdInputStream(BufferedInputStream(fis))
                val destName = archiveFile.name.removeSuffix(".zst")
                val destFile = File(destDir, destName)
                val fos = FileOutputStream(destFile)
                val bos = BufferedOutputStream(fos)
                zis.copyTo(bos)
                bos.close()
                zis.close()
                return@withContext true
            }

            val fis = FileInputStream(archiveFile)
            val bis = BufferedInputStream(fis)
            val input = if (isZstd) ZstdInputStream(bis) else bis

            extractTarStream(input, destDir, entryPaths)
            input.close()
            true
        } catch (e: Throwable) {
            DebugLogger.log("Error extracting ${archiveFile.path}", e)
            false
        }
    }

    suspend fun createArchive(files: List<File>, destFile: File): Boolean = withContext(Dispatchers.IO) {
        val isZstd = destFile.name.endsWith(".zst") || destFile.name.endsWith(".tzst")
        val isTar = destFile.name.contains(".tar") || destFile.name.endsWith(".tzst")

        try {
            if (!isTar && isZstd) {
                if (files.size != 1 || files[0].isDirectory) return@withContext false
                val src = files[0]
                val fos = FileOutputStream(destFile)
                val zos = ZstdOutputStream(BufferedOutputStream(fos))
                val fis = FileInputStream(src)
                fis.copyTo(zos)
                fis.close()
                zos.close()
                return@withContext true
            }

            val fos = FileOutputStream(destFile)
            val bos = BufferedOutputStream(fos)
            val out = if (isZstd) ZstdOutputStream(bos) else bos

            val tarOut = TarOutputStream(out)
            for (file in files) {
                addFileToTar(tarOut, file, file.parentFile?.path ?: "", "")
            }
            tarOut.finish()
            out.close()
            true
        } catch (e: Throwable) {
            DebugLogger.log("Error creating archive ${destFile.path}", e)
            if (destFile.exists()) destFile.delete()
            false
        }
    }

    suspend fun addToArchive(archiveFile: File, files: List<File>, parentPathInArchive: String): Boolean = withContext(Dispatchers.IO) {
        val tempFile = File(archiveFile.parent, "${archiveFile.name}.tmp")
        try {
            val isZstd = archiveFile.name.endsWith(".zst") || archiveFile.name.endsWith(".tzst")
            val isTar = archiveFile.name.contains(".tar") || archiveFile.name.endsWith(".tzst")

            if (!isTar) return@withContext false

            val fos = FileOutputStream(tempFile)
            val bos = BufferedOutputStream(fos)
            val out = if (isZstd) ZstdOutputStream(bos) else bos
            val tarOut = TarOutputStream(out)

            if (archiveFile.exists()) {
                val fis = FileInputStream(archiveFile)
                val bis = BufferedInputStream(fis)
                val input = if (isZstd) ZstdInputStream(bis) else bis

                val buffer = ByteArray(BLOCK_SIZE)
                while (true) {
                    if (!readBlock(input, buffer)) break
                    if (isZeroBlock(buffer)) {
                        if (!readBlock(input, buffer)) break
                        break
                    }
                    val entry = parseHeader(buffer) ?: continue
                    tarOut.putNextEntry(entry)
                    if (entry.size > 0) {
                         copyStream(input, tarOut, entry.size)
                         skipPadding(input, entry.size)
                    }
                    tarOut.closeEntry()
                }
                input.close()
            }

            val prefix = if (parentPathInArchive.isNotEmpty() && !parentPathInArchive.endsWith("/")) "$parentPathInArchive/" else parentPathInArchive
            for (file in files) {
                addFileToTar(tarOut, file, file.parentFile?.path ?: "", prefix)
            }
            tarOut.finish()
            out.close()

            archiveFile.delete()
            tempFile.renameTo(archiveFile)
            true
        } catch (e: Throwable) {
            DebugLogger.log("Error adding to archive", e)
            tempFile.delete()
            false
        }
    }

    suspend fun removeFromArchive(archiveFile: File, entryPaths: List<String>): Boolean = withContext(Dispatchers.IO) {
        val tempFile = File(archiveFile.parent, "${archiveFile.name}.tmp")
        try {
            val isZstd = archiveFile.name.endsWith(".zst") || archiveFile.name.endsWith(".tzst")
            val isTar = archiveFile.name.contains(".tar") || archiveFile.name.endsWith(".tzst")

            if (!isTar) return@withContext false

            val entriesToRemove = entryPaths.toSet()
            val fos = FileOutputStream(tempFile)
            val bos = BufferedOutputStream(fos)
            val out = if (isZstd) ZstdOutputStream(bos) else bos
            val tarOut = TarOutputStream(out)

            if (archiveFile.exists()) {
                val fis = FileInputStream(archiveFile)
                val bis = BufferedInputStream(fis)
                val input = if (isZstd) ZstdInputStream(bis) else bis

                val buffer = ByteArray(BLOCK_SIZE)
                while (true) {
                    if (!readBlock(input, buffer)) break
                    if (isZeroBlock(buffer)) {
                        if (!readBlock(input, buffer)) break
                        break
                    }
                    val entry = parseHeader(buffer) ?: continue

                    val shouldRemove = entriesToRemove.any { path ->
                         entry.name == path || entry.name.startsWith("$path/")
                    }

                    if (!shouldRemove) {
                        tarOut.putNextEntry(entry)
                        if (entry.size > 0) {
                             copyStream(input, tarOut, entry.size)
                             skipPadding(input, entry.size)
                        }
                        tarOut.closeEntry()
                    } else {
                        if (entry.size > 0) {
                            skipContent(input, entry.size)
                        }
                    }
                }
                input.close()
            }
            tarOut.finish()
            out.close()

            archiveFile.delete()
            tempFile.renameTo(archiveFile)
            true
        } catch (e: Throwable) {
            DebugLogger.log("Error removing from archive", e)
            tempFile.delete()
            false
        }
    }

    private fun addFileToTar(out: TarOutputStream, file: File, basePath: String, prefix: String) {
        val relative = if (basePath.isNotEmpty()) {
            file.path.substring(basePath.length + 1).replace('\\', '/')
        } else {
            file.name
        }
        val entryName = prefix + relative

        if (file.isDirectory) {
            val dirName = if (entryName.endsWith("/")) entryName else "$entryName/"
            out.putNextEntry(TarEntry(dirName, 0, System.currentTimeMillis() / 1000, true))
            out.closeEntry()

            val children = file.listFiles() ?: return
            for (child in children) {
                addFileToTar(out, child, basePath, prefix)
            }
        } else {
            out.putNextEntry(TarEntry(entryName, file.length(), file.lastModified() / 1000, false))
            FileInputStream(file).use { it.copyTo(out) }
            out.closeEntry()
        }
    }

    private fun readTarEntries(input: InputStream, list: MutableList<TarEntry>) {
        val buffer = ByteArray(BLOCK_SIZE)
        while (true) {
            if (!readBlock(input, buffer)) break
            if (isZeroBlock(buffer)) {
                 if (!readBlock(input, buffer)) break
                 break
            }
            val entry = parseHeader(buffer)
            if (entry != null) {
                list.add(entry)
                if (entry.size > 0) skipContent(input, entry.size)
            }
        }
    }

    private fun extractTarStream(input: InputStream, destDir: File, entryPaths: List<String>?) {
        val buffer = ByteArray(BLOCK_SIZE)
        val copyBuffer = ByteArray(8192)
        val filter = entryPaths?.toSet()

        while (true) {
            if (!readBlock(input, buffer)) break
            if (isZeroBlock(buffer)) {
                 if (!readBlock(input, buffer)) break
                 break
            }
            val entry = parseHeader(buffer) ?: continue

            var shouldExtract = filter == null
            if (!shouldExtract && filter != null) {
                if (filter.contains(entry.name) || filter.any { entry.name.startsWith("$it/") }) {
                    shouldExtract = true
                }
            }

            if (shouldExtract) {
                val target = File(destDir, entry.name)
                if (!target.canonicalPath.startsWith(destDir.canonicalPath)) {
                    if (entry.size > 0) skipContent(input, entry.size)
                    continue
                }

                if (entry.isDirectory) {
                    target.mkdirs()
                } else {
                    target.parentFile?.mkdirs()
                    FileOutputStream(target).use { output ->
                        var remaining = entry.size
                        while (remaining > 0) {
                            val toRead = if (remaining > copyBuffer.size) copyBuffer.size else remaining.toInt()
                            val read = input.read(copyBuffer, 0, toRead)
                            if (read == -1) break
                            output.write(copyBuffer, 0, read)
                            remaining -= read
                        }
                    }
                    skipPadding(input, entry.size)
                }
            } else {
                if (entry.size > 0) skipContent(input, entry.size)
            }
        }
    }

    private fun copyStream(input: InputStream, output: OutputStream, size: Long) {
        val buffer = ByteArray(8192)
        var remaining = size
        while (remaining > 0) {
            val toRead = if (remaining > buffer.size) buffer.size else remaining.toInt()
            val read = input.read(buffer, 0, toRead)
            if (read == -1) break
            output.write(buffer, 0, read)
            remaining -= read
        }
    }

    private fun readBlock(input: InputStream, buffer: ByteArray): Boolean {
        var offset = 0
        while (offset < BLOCK_SIZE) {
            val read = input.read(buffer, offset, BLOCK_SIZE - offset)
            if (read == -1) return false
            offset += read
        }
        return true
    }

    private fun skipContent(input: InputStream, size: Long) {
        var remaining = size
        val padding = (BLOCK_SIZE - (size % BLOCK_SIZE)) % BLOCK_SIZE
        remaining += padding
        while (remaining > 0) {
            val skipped = input.skip(remaining)
            if (skipped <= 0) {
                if (input.read() == -1) break
                remaining--
            } else {
                remaining -= skipped
            }
        }
    }

    private fun skipPadding(input: InputStream, size: Long) {
        val padding = (BLOCK_SIZE - (size % BLOCK_SIZE)) % BLOCK_SIZE
        var remaining = padding
        while (remaining > 0) {
            val skipped = input.skip(remaining)
            if (skipped <= 0) {
                if (input.read() == -1) break
                remaining--
            } else {
                remaining -= skipped
            }
        }
    }

    private fun isZeroBlock(buffer: ByteArray): Boolean {
        for (b in buffer) if (b != 0.toByte()) return false
        return true
    }

    private fun parseHeader(buffer: ByteArray): TarEntry? {
        val name = parseString(buffer, 0, 100)
        if (name.isEmpty()) return null
        val sizeStr = parseString(buffer, 124, 12)
        val size = try { sizeStr.trim().toLong(8) } catch (e: Exception) { 0L }
        val mtimeStr = parseString(buffer, 136, 12)
        val mtime = try { mtimeStr.trim().toLong(8) } catch (e: Exception) { 0L }
        val type = buffer[156]
        val prefix = parseString(buffer, 345, 155)
        val fullName = if (prefix.isNotEmpty()) "$prefix/$name" else name
        val isDir = type == TYPE_DIR || fullName.endsWith("/")
        return TarEntry(fullName, size, mtime, isDir)
    }

    private fun parseString(buffer: ByteArray, offset: Int, length: Int): String {
        var end = offset
        while (end < offset + length && buffer[end] != 0.toByte()) end++
        return String(buffer, offset, end - offset, StandardCharsets.UTF_8)
    }

    data class TarEntry(val name: String, val size: Long, val mtime: Long, val isDirectory: Boolean)

    class TarOutputStream(private val out: OutputStream) : OutputStream() {
        private var currBytes = 0L

        override fun write(b: Int) {
            out.write(b)
            currBytes++
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            out.write(b, off, len)
            currBytes += len
        }

        fun putNextEntry(entry: TarEntry) {
            val header = ByteArray(BLOCK_SIZE)

            var name = entry.name
            var prefix = ""

            if (name.length > 100) {
                var splitIdx = -1
                val minIdx = name.length - 101
                val maxIdx = 155

                if (minIdx <= maxIdx) {
                     var cursor = Math.min(maxIdx, name.length - 1)
                     while (cursor >= minIdx && cursor >= 0) {
                         if (name[cursor] == '/') {
                             splitIdx = cursor
                             break
                         }
                         cursor--
                     }
                }

                if (splitIdx != -1) {
                    prefix = name.substring(0, splitIdx)
                    name = name.substring(splitIdx + 1)
                } else {
                    if (name.length > 100) name = name.substring(0, 100)
                }
            }

            writeString(header, 0, 100, name)
            writeOctal(header, 100, 8, if (entry.isDirectory) 0x1edL else 0x1a4L)
            writeOctal(header, 108, 8, 0)
            writeOctal(header, 116, 8, 0)
            writeOctal(header, 124, 12, entry.size)
            writeOctal(header, 136, 12, entry.mtime)
            header[156] = if (entry.isDirectory) TYPE_DIR else TYPE_FILE
            writeString(header, 257, 6, "ustar")
            writeString(header, 263, 2, "00")
            writeString(header, 345, 155, prefix)

            // Checksum
            for (i in 0 until 8) header[148 + i] = ' '.code.toByte()
            var sum = 0L
            for (b in header) sum += (b.toInt() and 0xFF)

            val sumStr = String.format(Locale.US, "%06o", sum)
            for (i in 0 until 6) header[148 + i] = sumStr[i].code.toByte()
            header[148 + 6] = 0.toByte()
            header[148 + 7] = ' '.code.toByte()

            out.write(header)
            currBytes = 0
        }

        fun closeEntry() {
            val pad = (BLOCK_SIZE - (currBytes % BLOCK_SIZE)) % BLOCK_SIZE
            if (pad > 0) out.write(ByteArray(pad.toInt()))
        }

        fun finish() {
            out.write(ByteArray(BLOCK_SIZE))
            out.write(ByteArray(BLOCK_SIZE))
        }

        private fun writeString(buffer: ByteArray, offset: Int, length: Int, value: String) {
            val bytes = value.toByteArray(StandardCharsets.UTF_8)
            val len = Math.min(bytes.size, length)
            System.arraycopy(bytes, 0, buffer, offset, len)
        }

        private fun writeOctal(buffer: ByteArray, offset: Int, length: Int, value: Long) {
            val s = java.lang.Long.toOctalString(value)
            var idx = length - 1
            buffer[offset + idx] = 0.toByte()
            idx--
            var valIdx = s.length - 1
            while (idx >= 0) {
                if (valIdx >= 0) {
                    buffer[offset + idx] = s[valIdx].code.toByte()
                    valIdx--
                } else {
                    buffer[offset + idx] = '0'.code.toByte()
                }
                idx--
            }
        }
    }
}
