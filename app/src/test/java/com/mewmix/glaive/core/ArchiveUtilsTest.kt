package com.mewmix.glaive.core

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ArchiveUtilsTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun testCreateAndReadTarZstd() = runBlocking {
        val srcDir = tempFolder.newFolder("src")
        val file1 = File(srcDir, "file1.txt")
        file1.writeText("Hello World")
        val file2 = File(srcDir, "file2.txt")
        file2.writeText("Glaive Project")

        val archiveFile = File(tempFolder.root, "test.tar.zst")

        val success = ArchiveUtils.createArchive(listOf(file1, file2), archiveFile)
        assertTrue("Creation failed", success)
        assertTrue("File not created", archiveFile.exists())
        assertTrue("File is empty", archiveFile.length() > 0)

        // Test Listing
        val items = ArchiveUtils.listArchive(archiveFile.absolutePath, "")
        assertEquals("Should have 2 items", 2, items.size)
        assertTrue("Missing file1", items.any { it.name == "file1.txt" })
        assertTrue("Missing file2", items.any { it.name == "file2.txt" })

        // Test Extraction
        val destDir = tempFolder.newFolder("dest")
        val extractSuccess = ArchiveUtils.extractArchive(archiveFile, destDir)
        assertTrue("Extraction failed", extractSuccess)

        val extracted1 = File(destDir, "file1.txt")
        val extracted2 = File(destDir, "file2.txt")
        assertTrue("Extracted file1 missing", extracted1.exists())
        assertTrue("Extracted file2 missing", extracted2.exists())
        assertEquals("Content mismatch", "Hello World", extracted1.readText())
    }
}
