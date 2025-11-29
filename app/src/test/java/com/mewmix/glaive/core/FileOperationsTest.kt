package com.mewmix.glaive.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class FileOperationsTest {

    @Test
    fun testZipAndUnzip() {
        val tempDir = Files.createTempDirectory("glaive_test").toFile()
        val file1 = File(tempDir, "file1.txt").apply { writeText("Content 1") }
        val dir1 = File(tempDir, "dir1").apply { mkdir() }
        val file2 = File(dir1, "file2.txt").apply { writeText("Content 2") }

        val zipFile = File(tempDir, "test.zip")
        // FileOperations.zip is a suspend function. Need runBlocking.
        kotlinx.coroutines.runBlocking {
            FileOperations.zip(listOf(file1, dir1), zipFile)
        }

        assertTrue(zipFile.exists())

        // Test List
        kotlinx.coroutines.runBlocking {
            val list = FileOperations.listZip(zipFile.path, "")
            assertTrue(list.any { it.name == "file1.txt" })
            assertTrue(list.any { it.name == "dir1" })
        }

        // Test Unzip
        val unzipDir = File(tempDir, "unzipped")
        unzipDir.mkdir()
        kotlinx.coroutines.runBlocking {
            FileOperations.unzip(zipFile, unzipDir)
        }

        assertTrue(File(unzipDir, "file1.txt").exists())
        assertTrue(File(unzipDir, "dir1/file2.txt").exists())
        assertEquals("Content 2", File(unzipDir, "dir1/file2.txt").readText())

        // Test Extract Single File
        val extractedFile = File(tempDir, "extracted_file2.txt")
        kotlinx.coroutines.runBlocking {
            FileOperations.extractFile(zipFile, "dir1/file2.txt", extractedFile)
        }
        assertTrue(extractedFile.exists())
        assertEquals("Content 2", extractedFile.readText())

        tempDir.deleteRecursively()
    }
}
