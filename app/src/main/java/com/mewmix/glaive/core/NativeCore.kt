package com.mewmix.glaive.core

import com.mewmix.glaive.data.GlaiveItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object NativeCore {
    init {
        System.loadLibrary("glaive_core")
    }

    private external fun nativeList(path: String): Array<GlaiveItem>?
    private external fun nativeSearch(root: String, query: String): Array<GlaiveItem>?

    suspend fun list(path: String): List<GlaiveItem> = withContext(Dispatchers.IO) {
        nativeList(path)?.toList() ?: emptyList()
    }

    suspend fun search(root: String, query: String): List<GlaiveItem> = withContext(Dispatchers.IO) {
        nativeSearch(root, query)?.filterNotNull()?.toList() ?: emptyList()
    }
}
