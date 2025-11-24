package com.mewmix.glaive.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.mewmix.glaive.core.NativeCore
import com.mewmix.glaive.data.GlaiveItem
import kotlinx.coroutines.delay
import java.io.File
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.system.measureTimeMillis
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size

private const val DEFAULT_ROOT = "/storage/emulated/0"

val VoidBlack = Color(0xFF000000)
val MatrixGreen = Color(0xFF00FF41)
val MetaGray = Color(0xFF888888)
val White = Color(0xFFFFFFFF)

@Composable
fun GlaiveScreen() {
    val context = LocalContext.current
    val listState = rememberLazyListState()
    val hunterIndex = remember { HunterIndex() }

    var currentPath by remember { mutableStateOf(DEFAULT_ROOT) }
    var fileList by remember { mutableStateOf<List<GlaiveItem>>(emptyList()) }
    var hunterResults by remember { mutableStateOf<List<GlaiveItem>>(emptyList()) }
    var searchQuery by remember { mutableStateOf("") }
    var telemetry by remember { mutableStateOf(TelemetryReadout()) }
    val selectedPaths = remember { mutableStateListOf<String>() }

    LaunchedEffect(currentPath) {
        val elapsed = measureTimeMillis {
            fileList = NativeCore.list(currentPath)
        }
        telemetry = telemetry.copy(scanMs = elapsed, lastEvent = "SCAN")
        searchQuery = ""
        hunterResults = emptyList()
        hunterIndex.hardReset(currentPath)
        selectedPaths.clear()
    }

    LaunchedEffect(searchQuery, currentPath) {
        val trimmed = searchQuery.trim()
        if (trimmed.isEmpty()) {
            hunterResults = emptyList()
            hunterIndex.clearQuery()
            telemetry = telemetry.copy(searchMs = 0, lastEvent = "IDLE")
        } else {
            delay(150)
            val cached = hunterIndex.tryServe(currentPath, trimmed)
            if (cached != null) {
                hunterResults = cached
                telemetry = telemetry.copy(searchMs = 0, lastEvent = "INDEX")
            } else {
                var hits: List<GlaiveItem> = emptyList()
                val elapsed = measureTimeMillis {
                    hits = NativeCore.search(currentPath, trimmed)
                }
                hunterResults = hits
                hunterIndex.commit(currentPath, trimmed, hits)
                telemetry = telemetry.copy(searchMs = elapsed, lastEvent = "HUNT")
            }
        }
    }

    val viewWindow by remember(fileList, hunterResults, searchQuery) {
        derivedStateOf {
            val base = if (searchQuery.trim().isEmpty()) fileList else hunterResults
            base.take(512)
        }
    }

    val backEnabled = currentPath != DEFAULT_ROOT
    BackHandler(enabled = backEnabled) {
        val parent = File(currentPath).parent
        currentPath = parent ?: DEFAULT_ROOT
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(VoidBlack)
            .crtScanlines()
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        StatusDeck(
            currentPath = currentPath,
            searchQuery = searchQuery,
            telemetry = telemetry,
            selectedCount = selectedPaths.size,
            listSize = viewWindow.size,
            indexArmed = hunterIndex.isWarm(),
            onQueryChange = { searchQuery = it },
            onPathJump = { destination -> currentPath = destination }
        )

        Spacer(modifier = Modifier.height(12.dp))

        if (viewWindow.isEmpty()) {
            HunterEmptyState(searchQuery = searchQuery.trim())
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                state = listState,
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(viewWindow, key = { it.path }) { item ->
                    GlaiveRow(
                        item = item,
                        selected = selectedPaths.contains(item.path),
                        onClick = {
                            if (item.type == GlaiveItem.TYPE_DIR) {
                                currentPath = item.path
                            } else {
                                openFile(context, item)
                                telemetry = telemetry.copy(lastEvent = "OPEN")
                            }
                        },
                        onLongClick = {
                            sharePath(context, item)
                            telemetry = telemetry.copy(lastEvent = "SHARE")
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun StatusDeck(
    currentPath: String,
    searchQuery: String,
    telemetry: TelemetryReadout,
    selectedCount: Int,
    listSize: Int,
    indexArmed: Boolean,
    onQueryChange: (String) -> Unit,
    onPathJump: (String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(
                Brush.linearGradient(
                    colors = listOf(Color(0xFF050505), Color(0xFF0B1B0B))
                )
            )
            .border(1.dp, Color(0xFF1A2A1A), RoundedCornerShape(22.dp))
            .padding(16.dp)
    ) {
        BreadcrumbBlade(currentPath = currentPath, onPathJump = onPathJump)
        Spacer(modifier = Modifier.height(10.dp))
        HunterBar(query = searchQuery, onQueryChange = onQueryChange)
        Spacer(modifier = Modifier.height(12.dp))
        TelemetryStrip(
            telemetry = telemetry,
            selectedCount = selectedCount,
            windowSize = listSize,
            indexArmed = indexArmed
        )
        Spacer(modifier = Modifier.height(6.dp))
        GestureHints()
    }
}

@Composable
private fun BreadcrumbBlade(
    currentPath: String,
    onPathJump: (String) -> Unit
) {
    val scrollState = rememberScrollState()
    val segments = currentPath.split("/").filter { it.isNotEmpty() }
    var accumulator = ""

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(scrollState)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        BladeChip(label = "/") { onPathJump(DEFAULT_ROOT) }
        segments.forEach { segment ->
            accumulator += "/$segment"
            BasicText(
                text = "/",
                style = TextStyle(
                    color = MetaGray,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp
                ),
                modifier = Modifier.padding(horizontal = 4.dp)
            )
            BladeChip(label = segment) { onPathJump(accumulator) }
        }
    }
}

@Composable
private fun BladeChip(label: String, onClick: () -> Unit) {
    BasicText(
        text = label,
        style = TextStyle(
            color = MatrixGreen,
            fontFamily = FontFamily.Monospace,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold
        ),
        modifier = Modifier
            .clickable { onClick() }
            .padding(horizontal = 2.dp)
    )
}

@Composable
private fun HunterBar(query: String, onQueryChange: (String) -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xFF050505))
            .border(1.dp, Color(0xFF1C1C1C), RoundedCornerShape(16.dp))
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BasicText(
                text = "HUNTER",
                style = TextStyle(
                    color = MatrixGreen,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                )
            )
            Spacer(modifier = Modifier.width(12.dp))
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                textStyle = TextStyle(
                    color = White,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 16.sp
                ),
                modifier = Modifier.weight(1f),
                decorationBox = { innerTextField ->
                    if (query.isEmpty()) {
                        BasicText(
                            text = "type * or ? to glob the void",
                            style = TextStyle(
                                color = MetaGray,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 14.sp
                            )
                        )
                    }
                    innerTextField()
                }
            )
        }
    }
}

@Composable
private fun TelemetryStrip(
    telemetry: TelemetryReadout,
    selectedCount: Int,
    windowSize: Int,
    indexArmed: Boolean
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        TelemetryChip(
            label = "👁️",
            value = "${telemetry.scanMs}ms",
            alert = telemetry.scanMs > 50
        )
        TelemetryChip(
            label = "🔍",
            value = if (telemetry.searchMs == 0L) "--" else "${telemetry.searchMs}ms",
            alert = telemetry.searchMs > 50
        )
        TelemetryChip(
            label = "🪟",
            value = "${windowSize}",
            alert = false
        )
        TelemetryChip(
            label = "☑️",
            value = selectedCount.toString(),
            alert = selectedCount > 0
        )
        TelemetryChip(
            label = "⚡",
            value = if (indexArmed) "ON" else "OFF",
            alert = !indexArmed
        )
        TelemetryChip(
            label = "📡",
            value = telemetry.lastEvent,
            alert = false
        )
    }
}

@Composable
private fun RowScope.TelemetryChip(label: String, value: String, alert: Boolean) {
    Column(
        modifier = Modifier
            .weight(1f)
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF060606))
            .border(
                1.dp,
                if (alert) Color(0xFFFF3333) else Color(0xFF1F1F1F),
                RoundedCornerShape(12.dp)
            )
            .padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        BasicText(
            text = label,
            style = TextStyle(
                color = MatrixGreen,
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp
            )
        )
        BasicText(
            text = value,
            style = TextStyle(
                color = if (alert) Color(0xFFFF3333) else White,
                fontFamily = FontFamily.Monospace,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun GestureHints() {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        BasicText(
            text = "Swipe → Share",
            style = TextStyle(
                color = MatrixGreen,
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp
            )
        )
        BasicText(
            text = "Long-press → Mark",
            style = TextStyle(
                color = MetaGray,
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp
            )
        )
    }
}

@Composable
private fun HunterEmptyState(searchQuery: String) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        BasicText(
            text = if (searchQuery.isEmpty()) "Signal idle. Navigate or pull to hunt." else "No hits for \"$searchQuery\"",
            style = TextStyle(
                color = White,
                fontFamily = FontFamily.Monospace,
                fontSize = 16.sp,
                textAlign = TextAlign.Center
            )
        )
        Spacer(modifier = Modifier.height(8.dp))
        BasicText(
            text = "Use * or ? wildcards. Swipe items to dispatch instantly.",
            style = TextStyle(
                color = MetaGray,
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                textAlign = TextAlign.Center
            )
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GlaiveRow(
    item: GlaiveItem,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val haptic = LocalHapticFeedback.current
    
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(44.dp) // Slight increase for "Touch Terminal" feel
            .background(
                if (selected) MatrixGreen.copy(alpha = 0.2f) else Color.Transparent
            )
            .combinedClickable(
                onClick = { 
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) // Mechanical click feel
                    onClick() 
                },
                onLongClick = { 
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onLongClick() 
                }
            )
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Hex Indicator
        val hexCode = item.name.hashCode().toString(16).take(4).uppercase()
        
        BasicText(
            text = "[ $hexCode ]",
            style = TextStyle(
                color = if (selected) MatrixGreen else Color(0xFF445544),
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp
            ),
            modifier = Modifier.padding(end = 8.dp)
        )

        // Icon/Type Indicator
        val (iconChar, typeColor) = when(item.type) {
            GlaiveItem.TYPE_DIR -> "📁" to MatrixGreen
            GlaiveItem.TYPE_IMG -> "🖼️" to Color(0xFF00E5FF)
            GlaiveItem.TYPE_VID -> "📼" to Color(0xFFFF4081)
            GlaiveItem.TYPE_APK -> "📦" to Color(0xFFB2FF59)
            else -> "📄" to MetaGray
        }

        BasicText(
            text = iconChar,
            style = TextStyle(fontSize = 14.sp),
            modifier = Modifier.padding(end = 12.dp)
        )

        // The Data
        Column(modifier = Modifier.weight(1f)) {
            BasicText(
                text = item.name,
                style = TextStyle(
                    color = if (item.type == GlaiveItem.TYPE_DIR) White else Color(0xFFCCCCCC),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            
            // Sub-data line (Permissions | Size)
            if (item.type != GlaiveItem.TYPE_DIR) {
                BasicText(
                    text = "RW- | ${formatSize(item.size)}",
                    style = TextStyle(
                        color = Color(0xFF556655),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp
                    )
                )
            }
        }
        
        // Directional Chevron
        if (item.type == GlaiveItem.TYPE_DIR) {
            BasicText(
                text = ">>",
                style = TextStyle(color = MatrixGreen, fontFamily = FontFamily.Monospace, fontSize = 10.sp)
            )
        }
    }
    
    // Divider line between items
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(Color(0xFF112211))
    )
}

// Simulates scanlines and phosphor glow
fun Modifier.crtScanlines(color: Color = MatrixGreen) = this.drawWithContent {
    drawContent()
    val scanLineHeight = 4.dp.toPx()
    val height = size.height
    
    // Draw Scanlines
    for (y in 0 until height.toInt() step scanLineHeight.toInt()) {
        drawRect(
            color = Color.Black.copy(alpha = 0.3f),
            topLeft = Offset(0f, y.toFloat()),
            size = Size(size.width, 1f)
        )
    }
    
    // Optional: Vignette or Glow
    drawRect(
        brush = Brush.radialGradient(
            colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.6f)),
            center = center,
            radius = size.minDimension / 0.8f
        ),
        blendMode = BlendMode.Multiply
    )
}

private data class TelemetryReadout(
    val scanMs: Long = 0,
    val searchMs: Long = 0,
    val lastEvent: String = "IDLE"
)

private class HunterIndex {
    private var root: String = DEFAULT_ROOT
    private var baseQuery: String = ""
    private var cached: List<GlaiveItem> = emptyList()

    fun hardReset(path: String) {
        root = path
        baseQuery = ""
        cached = emptyList()
    }

    fun clearQuery() {
        baseQuery = ""
        cached = emptyList()
    }

    fun commit(path: String, query: String, hits: List<GlaiveItem>) {
        root = path
        baseQuery = query
        cached = hits
    }

    fun tryServe(path: String, query: String): List<GlaiveItem>? {
        if (cached.isEmpty()) return null;
        if (root != path) return null;
        if (baseQuery.isEmpty()) return null;
        if (!query.startsWith(baseQuery, ignoreCase = true)) return null;
        return cached.filter { matchesGlob(it.name, query) }
    }

    fun isWarm(): Boolean = cached.isNotEmpty()
}

private fun matchesGlob(input: String, pattern: String): Boolean {
    var i = 0
    var p = 0
    var star = -1
    var match = 0

    while (i < input.length) {
        if (p < pattern.length) {
            val pc = pattern[p]
            val ic = foldChar(input[i])
            when {
                pc == '?' || foldChar(pc) == ic -> {
                    i++
                    p++
                    continue
                }
                pc == '*' -> {
                    star = p
                    p++
                    match = i
                    continue
                }
            }
        }
        if (star != -1) {
            p = star + 1
            match++
            i = match
        } else {
            return false
        }
    }

    while (p < pattern.length && pattern[p] == '*') {
        p++
    }
    return p == pattern.length
}

private fun foldChar(c: Char): Char =
    if (c in 'A'..'Z') (c.code + 32).toChar() else c

private fun formatSize(bytes: Long): String {
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
    if (!file.exists()) {
        Toast.makeText(context, "Void reference: ${item.name}", Toast.LENGTH_SHORT).show()
        return
    }

    // 1. SECURE THE URI
    // Wraps the raw file in a content:// wrapper
    val uri = runCatching {
        FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
    }.getOrElse {
        Toast.makeText(context, "Provider pipe sealed", Toast.LENGTH_SHORT).show()
        return
    }

    // 2. IDENTIFY THE SIGNAL
    // Dynamic look up. Zero extra memory cost (uses System shared library).
    val mimeType = getSmartMimeType(item.path)

    // 3. CONSTRUCT THE PAYLOAD
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = mimeType
        putExtra(Intent.EXTRA_STREAM, uri)
        // Critical: Telegram needs permission to read the URI we just generated
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    // 4. DISPATCH
    try {
        context.startActivity(Intent.createChooser(intent, "Dispatch [ ${mimeType} ]"))
    } catch (ex: ActivityNotFoundException) {
        Toast.makeText(context, "No receiver found", Toast.LENGTH_SHORT).show()
    }
}

// Replaces your 'mimeFor'
// Efficiently maps extension -> system mime type
private fun getSmartMimeType(path: String): String {
    val extension = MimeTypeMap.getFileExtensionFromUrl(path)
    if (extension != null) {
        val type = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension.lowercase())
        if (type != null) return type
    }
    return "*/*" // Fallback to raw binary if unknown
}

private fun openFile(context: Context, item: GlaiveItem) {
    if (item.type == GlaiveItem.TYPE_DIR) return
    val file = File(item.path)
    if (!file.exists()) {
        Toast.makeText(context, "File missing: ${item.name}", Toast.LENGTH_SHORT).show()
        return
    }

    val uri = runCatching {
        FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
    }.getOrElse {
        Toast.makeText(context, "Cannot open ${item.name}", Toast.LENGTH_SHORT).show()
        return
    }

    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, mimeFor(item))
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    try {
        context.startActivity(intent)
    } catch (ex: ActivityNotFoundException) {
        Toast.makeText(context, "No app linked for ${item.name}", Toast.LENGTH_SHORT).show()
    }
}

private fun mimeFor(item: GlaiveItem): String = when (item.type) {
    GlaiveItem.TYPE_IMG -> "image/*"
    GlaiveItem.TYPE_VID -> "video/*"
    GlaiveItem.TYPE_APK -> "application/vnd.android.package-archive"
    GlaiveItem.TYPE_DOC -> "application/pdf"
    else -> "*/*"
}
