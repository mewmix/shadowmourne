package com.mewmix.glaive.data

import androidx.annotation.Keep

@Keep
data class GlaiveItem(
    var name: String,
    var path: String,
    var type: Int,
    var size: Long,
    var mtime: Long
) {
    companion object {
        const val TYPE_UNKNOWN = 0
        const val TYPE_DIR = 1
        const val TYPE_IMG = 2
        const val TYPE_VID = 3
        const val TYPE_APK = 4
        const val TYPE_DOC = 5
        const val TYPE_FILE = 6
        const val TYPE_AUDIO = 7
    }
}
