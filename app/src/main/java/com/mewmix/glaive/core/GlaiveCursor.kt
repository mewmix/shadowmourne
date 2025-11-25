package com.mewmix.glaive.core

import com.mewmix.glaive.data.GlaiveItem
import java.nio.ByteBuffer
import java.nio.charset.Charset

/**
 * A lazy list that reads directly from the shared native buffer.
 * It avoids creating objects until get(index) is called.
 * It scans the buffer once at creation to build an index of offsets.
 */
class GlaiveLazyList(
    private val buffer: ByteBuffer,
    private val parentPath: String,
    private val limit: Int
) : AbstractList<GlaiveItem>() {

    private val offsets: IntArray
    private val _size: Int
    private val charset: Charset = Charsets.UTF_8

    init {
        // First pass: Count items and build offset index
        // We can estimate count to allocate IntArray
        val estimatedCount = if (limit > 0) limit / 64 else 10
        val tempOffsets = IntArray(estimatedCount * 2) // Simple dynamic array simulation? No, let's just use ArrayList for offsets then toIntArray
        // Actually, for "Zero Garbage", avoiding ArrayList<Integer> boxing is good.
        // But we need to know the size.
        // Let's use a simple IntArrayList logic or just two passes?
        // Two passes is safer for memory than resizing arrays if we want to be strict, but resizing primitive array is fine.
        
        var count = 0
        var pos = 0
        
        // Pass 1: Count
        while (pos < limit) {
            val nameLen = buffer.get(pos + 1).toInt() and 0xFF
            pos += (2 + nameLen + 16)
            count++
        }
        
        _size = count
        offsets = IntArray(count)
        
        // Pass 2: Fill offsets
        pos = 0
        for (i in 0 until count) {
            offsets[i] = pos
            val nameLen = buffer.get(pos + 1).toInt() and 0xFF
            pos += (2 + nameLen + 16)
        }
    }

    override val size: Int
        get() = _size

    override fun get(index: Int): GlaiveItem {
        if (index < 0 || index >= size) throw IndexOutOfBoundsException("Index: $index, Size: $size")
        
        val offset = offsets[index]
        
        val type = buffer.get(offset).toInt() and 0xFF
        val nameLen = buffer.get(offset + 1).toInt() and 0xFF
        
        val nameBytes = ByteArray(nameLen)
        val nameStart = offset + 2
        for (i in 0 until nameLen) {
            nameBytes[i] = buffer.get(nameStart + i)
        }
        val sizePos = nameStart + nameLen
        val size = buffer.getLong(sizePos)
        val time = buffer.getLong(sizePos + 8)
        
        val name = String(nameBytes, charset)
        
        // If parentPath is empty/root, or if the name implies a relative path logic?
        // The C code writes relative path for search, or filename for list.
        // For list, we need to append to parentPath.
        // For search, the C code now writes relative path from root.
        
        val path: String
        if (parentPath.isEmpty()) {
             path = name // Should not happen for list, but maybe for search results if we changed logic?
        } else {
             // Check if name is already full path? No, C writes filename or relative path.
             // If parentPath ends with /, append name.
             // If parentPath doesn't end with /, append / + name.
             // BUT: For search, we passed the root. The C code writes relative path (e.g. "subdir/file.txt").
             // So appending works for both cases!
             
             if (parentPath.endsWith("/")) {
                 path = parentPath + name
             } else {
                 path = "$parentPath/$name"
             }
        }

        return GlaiveItem(
            name = name.substringAfterLast('/'), // Display name should be just the file name
            path = path,
            type = type,
            size = size,
            mtime = time
        )
    }
}
