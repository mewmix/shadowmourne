// Filename: app/src/main/java/com/mewmix/glaive/ui/GlaiveScreen.kt

package com.mewmix.glaive.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.* // Requires adding Material3 dependency or using basic Composables styled manually
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.mewmix.glaive.core.NativeCore
import com.mewmix.glaive.data.GlaiveItem
import kotlinx.coroutines.delay
import java.io.File
import java.util.Locale
import kotlin.system.measureTimeMillis

// --- THEME COLORS ---
val DeepSpace = Color(0xFF121212)
val SurfaceGray = Color(0xFF1E1E1E)
val SoftWhite = Color(0xFFE0E0E0)
val AccentPurple = Color(0xFFBB86FC)
val AccentBlue = Color(0xFF03DAC6)
val DangerRed = Color(0xFFCF6679)

enum class SortMode { NAME, DATE, SIZE, TYPE }

@Composable
fun GlaiveScreen() {
    val context = LocalContext.current
    
    // State
    var currentPath by remember { mutableStateOf("/storage/emulated/0") }
    var rawList by remember { mutableStateOf<List<GlaiveItem>>(emptyList()) }
    var sortMode by remember { mutableStateOf(SortMode.NAME) }
    var sortAscending by remember { mutableStateOf(true) }
    var searchQuery by remember { mutableStateOf("") }
    var isSearchActive by remember { mutableStateOf(false) }
    
    // Derived State (Sorting & Filtering)
    val displayedList by remember(rawList, sortMode, sortAscending, searchQuery) {
        derivedStateOf {
            var list = rawList
            
            // 1. Filter (Search)
            if (searchQuery.isNotEmpty()) {
                list = list.filter { it.name.contains(searchQuery, ignoreCase = true) }
            }

            // 2. Sort
            list = when (sortMode) {
                SortMode.NAME -> list.sortedBy { it.name.lowercase() }
                SortMode.SIZE -> list.sortedBy { it.size }
                SortMode.DATE -> list.sortedBy { it.mtime }
                SortMode.TYPE -> list.sortedBy { it.type }
            }
            
            // 3. Direction
            if (!sortAscending) list = list.reversed()
            
            // 4. Always keep Directories on top
            list.sortedByDescending { it.type == GlaiveItem.TYPE_DIR }
        }
    }

    // Load Data
    LaunchedEffect(currentPath) {
        rawList = NativeCore.list(currentPath)
    }

    // Back Handler
    BackHandler(enabled = currentPath != "/storage/emulated/0") {
        File(currentPath).parent?.let { currentPath = it }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(DeepSpace)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            
            // -- TOP HEADER --
            GlaiveHeader(
                currentPath = currentPath,
                isSearchActive = isSearchActive,
                searchQuery = searchQuery,
                onSearchQueryChange = { searchQuery = it },
                onToggleSearch = { isSearchActive = !isSearchActive; searchQuery = "" },
                onPathJump = { currentPath = it }
            )

            // -- CONTENT LIST --
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(bottom = 100.dp, top = 8.dp, start = 16.dp, end = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(displayedList, key = { it.path }) { item ->
                    FileCard(
                        item = item,
                        onClick = {
                            if (item.type == GlaiveItem.TYPE_DIR) {
                                currentPath = item.path
                            } else {
                                openFile(context, item)
                            }
                        },
                        onLongClick = { sharePath(context, item) }
                    )
                }
            }
        }

        // -- FLOATING DOCK --
        ControlDock(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 24.dp),
            currentSort = sortMode,
            ascending = sortAscending,
            onSortChange = { mode ->
                if (sortMode == mode) sortAscending = !sortAscending
                else {
                    sortMode = mode
                    sortAscending = true
                }
            }
        )
    }
}

// --- COMPONENTS ---

@Composable
fun GlaiveHeader(
    currentPath: String,
    isSearchActive: Boolean,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    onToggleSearch: () -> Unit,
    onPathJump: (String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    colors = listOf(DeepSpace, DeepSpace.copy(alpha = 0.9f))
                )
            )
            .padding(top = 16.dp, bottom = 8.dp)
    ) {
        // Title Row
        Row(
            modifier = Modifier.padding(horizontal = 24.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = if (isSearchActive) "Search" else File(currentPath).name.ifEmpty { "Storage" },
                style = TextStyle(
                    color = SoftWhite,
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold
                ),
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            IconButton(
                onClick = onToggleSearch,
                modifier = Modifier
                    .clip(CircleShape)
                    .background(SurfaceGray)
            ) {
                Icon(
                    imageVector = if (isSearchActive) Icons.Default.KeyboardArrowUp else Icons.Default.Search,
                    contentDescription = "Search",
                    tint = AccentBlue
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Breadcrumbs or Search Bar
        AnimatedContent(targetState = isSearchActive, label = "HeaderMode") { searching ->
            if (searching) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(SurfaceGray)
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                ) {
                    BasicTextField(
                        value = searchQuery,
                        onValueChange = onSearchQueryChange,
                        textStyle = TextStyle(color = SoftWhite, fontSize = 16.sp),
                        modifier = Modifier.fillMaxWidth(),
                        decorationBox = { inner ->
                            if (searchQuery.isEmpty()) Text("Type to hunt...", color = Color.Gray)
                            inner()
                        }
                    )
                }
            } else {
                BreadcrumbStrip(currentPath, onPathJump)
            }
        }
    }
}

@Composable
fun BreadcrumbStrip(currentPath: String, onPathJump: (String) -> Unit) {
    val segments = remember(currentPath) {
        val list = mutableListOf<Pair<String, String>>()
        var acc = ""
        currentPath.split("/").filter { it.isNotEmpty() }.forEach {
            acc += "/$it"
            list.add(it to acc)
        }
        if (list.isEmpty()) listOf("Root" to "/") else list
    }

    LazyRow(
        contentPadding = PaddingValues(horizontal = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(segments) { (name, path) ->
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(SurfaceGray)
                    .clickable { onPathJump(path) }
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Text(
                    text = name,
                    style = TextStyle(
                        color = if (path == currentPath) AccentBlue else Color.Gray,
                        fontWeight = FontWeight.Medium
                    )
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FileCard(
    item: GlaiveItem,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val haptic = LocalHapticFeedback.current
    var isPressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (isPressed) 0.96f else 1f, label = "press")

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .scale(scale)
            .height(72.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(SurfaceGray)
            .combinedClickable(
                onClick = { 
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onClick() 
                },
                onLongClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onLongClick()
                }
            )
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Icon Bubble
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(getTypeColor(item.type).copy(alpha = 0.2f)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = getTypeIcon(item.type),
                fontSize = 20.sp
            )
        }

        Spacer(modifier = Modifier.width(16.dp))

        // Info
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.name,
                style = TextStyle(
                    color = SoftWhite,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = if (item.type == GlaiveItem.TYPE_DIR) "Folder" else formatSize(item.size),
                style = TextStyle(
                    color = Color.Gray,
                    fontSize = 12.sp
                )
            )
        }

        // Action Indicator
        if (item.type == GlaiveItem.TYPE_DIR) {
            Icon(
                imageVector = Icons.Default.KeyboardArrowRight,
                contentDescription = null,
                tint = Color.Gray
            )
        }
    }
}

@Composable
fun ControlDock(
    modifier: Modifier = Modifier,
    currentSort: SortMode,
    ascending: Boolean,
    onSortChange: (SortMode) -> Unit
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(Color.Black.copy(alpha = 0.8f))
            .border(1.dp, SurfaceGray, RoundedCornerShape(50))
            .padding(horizontal = 8.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        SortChip("Name", SortMode.NAME, currentSort, ascending, onSortChange)
        SortChip("Size", SortMode.SIZE, currentSort, ascending, onSortChange)
        SortChip("Date", SortMode.DATE, currentSort, ascending, onSortChange)
        SortChip("Type", SortMode.TYPE, currentSort, ascending, onSortChange)
    }
}

@Composable
fun SortChip(
    label: String,
    mode: SortMode,
    current: SortMode,
    ascending: Boolean,
    onClick: (SortMode) -> Unit
) {
    val isSelected = mode == current
    val background = if (isSelected) AccentBlue else Color.Transparent
    val textColor = if (isSelected) Color.Black else Color.Gray

    Row(
        modifier = Modifier
            .clip(CircleShape)
            .background(background)
            .clickable { onClick(mode) }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = TextStyle(
                color = textColor,
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp
            )
        )
        if (isSelected) {
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = if (ascending) "↑" else "↓",
                style = TextStyle(color = textColor, fontSize = 12.sp)
            )
        }
    }
}

// --- UTILS ---

fun getTypeColor(type: Int): Color = when (type) {
    GlaiveItem.TYPE_DIR -> AccentPurple
    GlaiveItem.TYPE_IMG -> AccentBlue
    GlaiveItem.TYPE_VID -> DangerRed
    GlaiveItem.TYPE_APK -> Color(0xFFB2FF59)
    else -> Color.Gray
}

fun getTypeIcon(type: Int): String = when (type) {
    GlaiveItem.TYPE_DIR -> "📁"
    GlaiveItem.TYPE_IMG -> "🖼️"
    GlaiveItem.TYPE_VID -> "🎬"
    GlaiveItem.TYPE_APK -> "🤖"
    GlaiveItem.TYPE_DOC -> "📄"
    else -> "📝"
}

fun formatSize(bytes: Long): String {
    if (bytes <= 0) return "--"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var idx = 0
    while (value >= 1024 && idx < units.lastIndex) {
        value /= 1024
        idx++
    }
    return String.format(Locale.US, "%.1f %s", value, units[idx])
}

private fun sharePath(context: Context, item: GlaiveItem) {
    if (item.type == GlaiveItem.TYPE_DIR) return
    val file = File(item.path)
    if (!file.exists()) return

    val uri = runCatching {
        FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
    }.getOrElse { return }

    val intent = Intent(Intent.ACTION_SEND).apply {
        type = getSmartMimeType(item.path)
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, "Share"))
}

private fun openFile(context: Context, item: GlaiveItem) {
    if (item.type == GlaiveItem.TYPE_DIR) return
    val file = File(item.path)
    val uri = runCatching {
        FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
    }.getOrElse { return }

    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, mimeFor(item))
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    try { context.startActivity(intent) } catch (_: Exception) {}
}

private fun mimeFor(item: GlaiveItem): String = when (item.type) {
    GlaiveItem.TYPE_IMG -> "image/*"
    GlaiveItem.TYPE_VID -> "video/*"
    GlaiveItem.TYPE_APK -> "application/vnd.android.package-archive"
    GlaiveItem.TYPE_DOC -> "application/pdf"
    else -> "*/*"
}

private fun getSmartMimeType(path: String): String {
    val extension = MimeTypeMap.getFileExtensionFromUrl(path)
    if (extension != null) {
        val type = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension.lowercase())
        if (type != null) return type
    }
    return "*/*"
}

// Simple IconButton replacement if Material3 not available
@Composable
fun IconButton(onClick: () -> Unit, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(
        modifier = modifier
            .size(40.dp)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        content()
    }
}
