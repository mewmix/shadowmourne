package com.mewmix.glaive.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mewmix.glaive.core.NativeCore
import com.mewmix.glaive.data.GlaiveItem
import java.io.File

// CONSTANTS
val VoidBlack = Color(0xFF000000)
val MatrixGreen = Color(0xFF00FF41)
val MetaGray = Color(0xFF888888)
val White = Color(0xFFFFFFFF)

@Composable
fun GlaiveScreen() {
    // STATE
    var currentPath by remember { mutableStateOf("/storage/emulated/0") }
    var fileList by remember { mutableStateOf<List<GlaiveItem>>(emptyList()) }

    // LOAD
    LaunchedEffect(currentPath) {
        fileList = NativeCore.list(currentPath)
    }

    // BACK HANDLER
    BackHandler(enabled = currentPath != "/storage/emulated/0") {
        currentPath = File(currentPath).parent ?: "/storage/emulated/0"
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(VoidBlack)
    ) {

        // 1. BREADCRUMB BLADE (Simple Version)
        BasicText(
            text = "> $currentPath",
            style = TextStyle(
                color = MatrixGreen,
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
            ),
            maxLines = 1,
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF111111))
                .padding(8.dp)
        )

        // 2. KILL LIST
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(fileList) { item ->
                GlaiveRow(item = item, onClick = {
                    if (item.type == GlaiveItem.TYPE_DIR) {
                        currentPath = item.path
                    } else {
                        // OPEN FILE
                        // For MVP: Just log or Toast, implementing Open logic requires Intent construction
                    }
                })
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(Color(0xFF111111))
                )
            }
        }
    }
}

@Composable
fun GlaiveRow(item: GlaiveItem, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .clickable { onClick() }
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // TYPE INDICATOR
        val indicator = when(item.type) {
            GlaiveItem.TYPE_DIR -> "/"
            GlaiveItem.TYPE_IMG -> "IMG"
            GlaiveItem.TYPE_VID -> "VID"
            GlaiveItem.TYPE_APK -> "APK"
            else -> "DOC"
        }

        BasicText(
            text = indicator,
            style = TextStyle(
                color = if(item.type == GlaiveItem.TYPE_DIR) MatrixGreen else MetaGray,
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
            ),
            modifier = Modifier.width(32.dp)
        )

        // NAME
        BasicText(
            text = item.name,
            style = TextStyle(
                color = White,
                fontFamily = FontFamily.Monospace,
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(horizontal = 8.dp)
        )

        // META
        if (item.type != GlaiveItem.TYPE_DIR) {
            val sizeMb = item.size / 1024f / 1024f
            BasicText(
                text = "%.1fM".format(sizeMb),
                style = TextStyle(
                    color = MetaGray,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp
                )
            )
        }
    }
}
