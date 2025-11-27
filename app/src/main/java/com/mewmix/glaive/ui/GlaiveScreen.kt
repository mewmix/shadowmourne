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
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.CallSplit
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.ViewList
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import coil.compose.AsyncImage
import com.mewmix.glaive.core.DebugLogger
import com.mewmix.glaive.core.FileOperations
import com.mewmix.glaive.core.NativeCore
import com.mewmix.glaive.core.RecentFilesManager
import com.mewmix.glaive.data.GlaiveItem

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import kotlin.system.measureTimeMillis

// --- THEME COLORS (UNIFIED GREEN) ---
val DeepSpace = Color(0xFF0A0A0A)
val SurfaceGray = Color(0xFF181818)
val SoftWhite = Color(0xFFEEEEEE)
val NeonGreen = Color(0xFF00E676) 
val DarkGreen = Color(0xFF003318)
val DangerRed = Color(0xFFD32F2F)

// Aliases to maintain code compatibility
val AccentPurple = NeonGreen 
val AccentBlue = NeonGreen
val AccentGreen = NeonGreen

enum class SortMode { NAME, DATE, SIZE, TYPE }

@Composable
fun GlaiveScreen() {
    val context = LocalContext.current
    
    // State
    var currentPath by remember { mutableStateOf("/storage/emulated/0") }
    var rawList by remember { mutableStateOf<List<GlaiveItem>>(emptyList()) }
    var secondaryPath by remember { mutableStateOf("/storage/emulated/0") }
    var secondaryRawList by remember { mutableStateOf<List<GlaiveItem>>(emptyList()) }
    var sortMode by remember { mutableStateOf(SortMode.NAME) }
    var sortAscending by remember { mutableStateOf(true) }
    var searchQuery by remember { mutableStateOf("") }
    var isSearchActive by remember { mutableStateOf(false) }
    var secondarySearchQuery by remember { mutableStateOf("") }
    var secondaryIsSearchActive by remember { mutableStateOf(false) }
    var selectedPaths by remember { mutableStateOf<Set<String>>(emptySet()) }
    
    // New State
    var isGridView by remember { mutableStateOf(false) }
    var currentTab by remember { mutableStateOf(0) } // 0 = Browse, 1 = Recents
    var secondaryCurrentTab by remember { mutableStateOf(0) }
    var activePane by remember { mutableStateOf(0) }
    var clipboardItems by remember { mutableStateOf<List<File>>(emptyList()) }
    var isCutOperation by remember { mutableStateOf(false) }
    var showCreateDialog by remember { mutableStateOf(false) }
    var showEditor by remember { mutableStateOf(false) }
    var editorFile by remember { mutableStateOf<File?>(null) }
    val pathHistory = remember { mutableStateListOf<String>() }
    var splitScopeEnabled by remember { mutableStateOf(false) }
    var splitFraction by remember { mutableStateOf(0.5f) }
    val minSplitFraction = 0.3f
    val maxSplitFraction = 0.7f
    var navigationPaneVisible by remember { mutableStateOf(false) }
    var navigationPanePulse by remember { mutableStateOf(0) }
    
    // Filter State
    var activeFilters by remember { mutableStateOf<Set<Int>>(emptySet()) }
    
    // Context Menu State
    var contextMenuTarget by remember { mutableStateOf<GlaiveItem?>(null) }
    var contextMenuPane by remember { mutableStateOf(0) }
    var maximizedPane by remember { mutableStateOf(-1) }

    val directorySizes = remember { mutableStateMapOf<String, Long>() }
    
    val scope = rememberCoroutineScope()

    fun panePath(index: Int): String = if (index == 0) currentPath else secondaryPath
    fun setPanePath(index: Int, path: String) {
        if (index == 0) currentPath = path else secondaryPath = path
    }
    fun paneSearchQuery(index: Int): String = if (index == 0) searchQuery else secondarySearchQuery
    fun setPaneSearchQuery(index: Int, query: String) {
        if (index == 0) searchQuery = query else secondarySearchQuery = query
    }
    fun paneIsSearchActive(index: Int): Boolean = if (index == 0) isSearchActive else secondaryIsSearchActive
    fun setPaneSearchActive(index: Int, active: Boolean) {
        if (index == 0) isSearchActive = active else secondaryIsSearchActive = active
    }
    fun paneCurrentTab(index: Int): Int = if (index == 0) currentTab else secondaryCurrentTab
    fun setPaneCurrentTab(index: Int, tab: Int) {
        if (index == 0) currentTab = tab else secondaryCurrentTab = tab
    }

    fun keepNavigationPaneAlive() {
        if (navigationPaneVisible) {
            navigationPanePulse++
        }
    }

    fun handlePaste(paneIndex: Int) {
        DebugLogger.log("Pasting ${clipboardItems.size} items to pane $paneIndex") {
            scope.launch {
                val targetPath = panePath(paneIndex)
                val dest = File(targetPath)
                clipboardItems.forEach { src ->
                    if (isCutOperation) FileOperations.move(src, dest)
                    else FileOperations.copy(src, dest)
                }
                if (isCutOperation) clipboardItems = emptyList()
                // Refresh
                val updated = NativeCore.list(targetPath)
                if (paneIndex == 0) rawList = updated else secondaryRawList = updated
            }
        }
    }
    
    fun getFilterMask(filters: Set<Int>): Int {
        var mask = 0
        for (type in filters) mask = mask or (1 shl type)
        return mask
    }
    
    fun getSortModeInt(mode: SortMode): Int {
        return when(mode) {
            SortMode.NAME -> 0
            SortMode.DATE -> 1
            SortMode.SIZE -> 2
            SortMode.TYPE -> 3
        }
    }

    // Navigation Helper
    fun navigateTo(paneIndex: Int, path: String) {
        DebugLogger.log("Navigating to $path in pane $paneIndex") {
            val origin = panePath(paneIndex)
            if (path != origin) {
                pathHistory.remove(path)
                if (origin.isNotEmpty()) {
                    pathHistory.remove(origin)
                    pathHistory.add(0, origin)
                    if (pathHistory.size > 6) {
                        pathHistory.removeAt(pathHistory.lastIndex)
                    }
                }
            }
            setPanePath(paneIndex, path)
            setPaneSearchQuery(paneIndex, "")
            setPaneSearchActive(paneIndex, false)
            selectedPaths = emptySet()
        }
    }

    fun toggleSearch(paneIndex: Int) {
        val currentlyActive = paneIsSearchActive(paneIndex)
        if (currentlyActive) setPaneSearchQuery(paneIndex, "")
        setPaneSearchActive(paneIndex, !currentlyActive)
    }

    // Load Data / Search with Debounce
    LaunchedEffect(currentPath, searchQuery, currentTab, sortMode, sortAscending, activeFilters) {
        DebugLogger.logSuspend("Loading data for path: $currentPath") {
            if (currentTab == 1) {
                rawList = RecentFilesManager.getRecents(context)
            } else {
                if (searchQuery.isEmpty()) {
                    rawList = NativeCore.list(
                        currentPath, 
                        getSortModeInt(sortMode), 
                        sortAscending, 
                        getFilterMask(activeFilters)
                    )
                } else {
                    delay(300)
                    rawList = NativeCore.search(currentPath, searchQuery, getFilterMask(activeFilters))
                }
            }
        }
    }

    LaunchedEffect(secondaryPath, secondarySearchQuery, secondaryCurrentTab, sortMode, sortAscending, activeFilters, splitScopeEnabled) {
        if (!splitScopeEnabled) return@LaunchedEffect
        DebugLogger.logSuspend("Loading data for secondary path: $secondaryPath") {
            if (secondaryCurrentTab == 1) {
                secondaryRawList = RecentFilesManager.getRecents(context)
            } else {
                if (secondarySearchQuery.isEmpty()) {
                    secondaryRawList = NativeCore.list(
                        secondaryPath,
                        getSortModeInt(sortMode),
                        sortAscending,
                        getFilterMask(activeFilters)
                    )
                } else {
                    delay(300)
                    secondaryRawList = NativeCore.search(secondaryPath, secondarySearchQuery, getFilterMask(activeFilters))
                }
            }
        }
    }
    
    // Stats Calculation (Background)
    var stats by remember { mutableStateOf(GlaiveStats(0, 0, 0, 0, 0)) }
    LaunchedEffect(rawList, secondaryRawList, selectedPaths, activePane, directorySizes) {
        withContext(Dispatchers.Default) {
            val activeList = if (activePane == 0) rawList else secondaryRawList
            val totalSize = activeList.sumOf { item ->
                if (item.type == GlaiveItem.TYPE_DIR) {
                    directorySizes.getOrDefault(item.path, 0L)
                } else {
                    item.size
                }
            }
            val selectedSize = activeList.filter { selectedPaths.contains(it.path) }.sumOf { item ->
                if (item.type == GlaiveItem.TYPE_DIR) {
                    directorySizes.getOrDefault(item.path, 0L)
                } else {
                    item.size
                }
            }
            val dirCount = activeList.count { it.type == GlaiveItem.TYPE_DIR }
            val fileCount = activeList.size - dirCount
            withContext(Dispatchers.Main) {
                stats = GlaiveStats(activeList.size, totalSize, selectedSize, dirCount, fileCount)
            }
        }
    }

    LaunchedEffect(rawList, secondaryRawList, activePane) {
        val activeList = if (activePane == 0) rawList else secondaryRawList
        activeList.forEach { item ->
            if (item.type == GlaiveItem.TYPE_DIR && !directorySizes.containsKey(item.path)) {
                scope.launch {
                    val size = NativeCore.calculateDirectorySize(item.path)
                    directorySizes[item.path] = size
                }
            }
        }
    }

    LaunchedEffect(splitScopeEnabled) {
        if (splitScopeEnabled) {
            secondaryPath = currentPath
        } else {
            activePane = 0
        }
    }

    LaunchedEffect(navigationPaneVisible, navigationPanePulse) {
        if (navigationPaneVisible) {
            delay(4200)
            navigationPaneVisible = false
        }
    }

    val activePanePath = panePath(activePane)
    val activePaneSearch = paneIsSearchActive(activePane)

    BackHandler(enabled = (activePanePath != "/storage/emulated/0" || activePaneSearch || selectedPaths.isNotEmpty()) || (splitScopeEnabled && secondaryPath != "/storage/emulated/0")) {
        if (selectedPaths.isNotEmpty()) {
            selectedPaths = emptySet()
        } else if (activePaneSearch) {
            setPaneSearchActive(activePane, false)
            setPaneSearchQuery(activePane, "")
        } else {
            if (splitScopeEnabled && activePane == 1 && secondaryPath != "/storage/emulated/0") {
                File(secondaryPath).parent?.let { navigateTo(1, it) }
            } else {
                File(activePanePath).parent?.let { navigateTo(activePane, it) }
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(DeepSpace)
    ) {
        val handleItemClick: (Int, GlaiveItem) -> Unit = { paneIndex, item ->
            activePane = paneIndex
            if (selectedPaths.isNotEmpty()) {
                selectedPaths = if (selectedPaths.contains(item.path)) {
                    selectedPaths - item.path
                } else {
                    selectedPaths + item.path
                }
            } else if (item.type == GlaiveItem.TYPE_DIR || File(item.path).isDirectory) {
                navigateTo(paneIndex, item.path)
            } else {
                RecentFilesManager.addRecent(context, item.path)
                openFile(context, item)
            }
        }
        val handleItemLongPress: (Int, GlaiveItem) -> Unit = { paneIndex, item ->
            activePane = paneIndex
            contextMenuPane = paneIndex
            contextMenuTarget = item
        }

        Column(modifier = Modifier.fillMaxSize()) {
            
            // -- TOP HEADER (CONDENSED & COLLAPSIBLE) --
            GlaiveHeader(
                currentPath = activePanePath,
                isSearchActive = activePaneSearch,
                searchQuery = paneSearchQuery(activePane),
                onSearchQueryChange = { setPaneSearchQuery(activePane, it) },
                onToggleSearch = { toggleSearch(activePane) },
                onPathJump = { navigateTo(activePane, it) },
                currentTab = paneCurrentTab(activePane),
                onTabChange = { setPaneCurrentTab(activePane, it) },
                isGridView = isGridView,
                onToggleView = { isGridView = !isGridView },
                onAddClick = { showCreateDialog = true },
                stats = stats,
                splitScopeEnabled = splitScopeEnabled,
                onSplitToggle = { splitScopeEnabled = !splitScopeEnabled },
            )
            
            // -- FILTER BAR --
            if (!splitScopeEnabled && !paneIsSearchActive(activePane) && paneCurrentTab(activePane) == 0) {
                FilterBar(
                    activeFilters = activeFilters,
                    onFilterToggle = { type ->
                        activeFilters = if (activeFilters.contains(type)) {
                            activeFilters - type
                        } else {
                            activeFilters + type
                        }
                    }
                )
            }

            // -- CONTENT LIST --
            if (splitScopeEnabled) {
                BoxWithConstraints(
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 8.dp)
                ) {
                    val density = LocalDensity.current
                    val totalWidthPx = remember(maxWidth, density) { with(density) { maxWidth.toPx() } }
                    Row(
                        modifier = Modifier.fillMaxSize(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (maximizedPane == -1 || maximizedPane == 0) {
                            PaneBrowser(
                                paneIndex = 0,
                                modifier = Modifier
                                    .weight(if (maximizedPane == 0) 1f else splitFraction)
                                    .fillMaxHeight(),
                                isGridView = isGridView,
                                displayedList = rawList,
                                selectedPaths = selectedPaths,
                                sortMode = sortMode,
                                onItemClick = handleItemClick,
                                onItemLongClick = handleItemLongPress,
                                onMaximize = { maximizedPane = if (maximizedPane == 0) -1 else 0 },
                                onPaste = { handlePaste(0) },
                                clipboardActive = clipboardItems.isNotEmpty(),
                                directorySizes = directorySizes
                            )
                        }
                        if (maximizedPane == -1) {
                            Box(
                                modifier = Modifier
                                    .fillMaxHeight()
                                    .width(12.dp)
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(SurfaceGray.copy(alpha = 0.9f))
                                    .pointerInput(totalWidthPx) {
                                        detectHorizontalDragGestures { _, dragAmount ->
                                            if (totalWidthPx > 0f) {
                                                val delta = dragAmount / totalWidthPx
                                                splitFraction = (splitFraction + delta).coerceIn(minSplitFraction, maxSplitFraction)
                                            }
                                        }
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Box(
                                    modifier = Modifier
                                        .width(2.dp)
                                        .fillMaxHeight(0.6f)
                                        .clip(RoundedCornerShape(1.dp))
                                        .background(AccentBlue.copy(alpha = 0.7f))
                                )
                            }
                        }
                        if (maximizedPane == -1 || maximizedPane == 1) {
                            PaneBrowser(
                                paneIndex = 1,
                                modifier = Modifier
                                    .weight(if (maximizedPane == 1) 1f else 1f - splitFraction)
                                    .fillMaxHeight(),
                                isGridView = isGridView,
                                displayedList = secondaryRawList,
                                selectedPaths = selectedPaths,
                                sortMode = sortMode,
                                onItemClick = handleItemClick,
                                onItemLongClick = handleItemLongPress,
                                onMaximize = { maximizedPane = if (maximizedPane == 1) -1 else 1 },
                                onPaste = { handlePaste(1) },
                                clipboardActive = clipboardItems.isNotEmpty(),
                                directorySizes = directorySizes
                            )
                        }
                    }
                }

            } else {

                PaneBrowser(
                    paneIndex = activePane,
                    modifier = Modifier.weight(1f),
                    isGridView = isGridView,
                    displayedList = if (activePane == 0) rawList else secondaryRawList,
                    selectedPaths = selectedPaths,
                    sortMode = sortMode,
                    onItemClick = handleItemClick,
                    onItemLongClick = handleItemLongPress,
                    onMaximize = {},
                    onPaste = { handlePaste(activePane) },
                    clipboardActive = clipboardItems.isNotEmpty(),
                    directorySizes = directorySizes
                )
            }
        }

        // -- STATS & DOCK --
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            ControlDock(
                currentSort = sortMode,
                ascending = sortAscending,
                onSortChange = { mode ->
                    if (sortMode == mode) sortAscending = !sortAscending
                    else {
                        sortMode = mode
                        sortAscending = true
                    }
                },
                selectionActive = selectedPaths.isNotEmpty(),
                onShareSelection = {
                    val lastPath = selectedPaths.lastOrNull()
                    if (lastPath != null) {
                        val item = (rawList + secondaryRawList).find { it.path == lastPath }
                        if (item != null) sharePath(context, item)
                    }
                },
                onClearSelection = { selectedPaths = emptySet() },
                clipboardActive = clipboardItems.isNotEmpty(),
                onPaste = { handlePaste(activePane) }
            )
        }

        ReverseGestureHotZone(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .fillMaxHeight(),
            hasTrail = pathHistory.isNotEmpty() || File(activePanePath).parent != null,
            onNavigateUp = {
                File(activePanePath).parent?.let { navigateTo(activePane, it) }
            }
        )

        AnimatedVisibility(
            visible = navigationPaneVisible,
            enter = fadeIn() + slideInVertically(initialOffsetY = { it }),
            exit = fadeOut() + slideOutVertically(targetOffsetY = { it / 2 }),
            modifier = Modifier.align(Alignment.Center)
        ) {
            NavigationPaneOverlay(
                activePane = activePane,
                splitScopeEnabled = splitScopeEnabled,
                currentPath = currentPath,
                secondaryPath = secondaryPath,
                pathHistory = pathHistory,
                onPaneFocus = { paneIndex ->
                    activePane = paneIndex
                    keepNavigationPaneAlive()
                },
                onPathSelected = { paneIndex, path ->
                    activePane = paneIndex
                    navigateTo(paneIndex, path)
                    navigationPaneVisible = false
                },
                onDismiss = { navigationPaneVisible = false },
                onInteract = { keepNavigationPaneAlive() }
            )
        }
        
        // -- CONTEXT MENU --
        if (contextMenuTarget != null) {
            ContextMenuSheet(
                item = contextMenuTarget!!,
                onDismiss = { contextMenuTarget = null },
                onCopy = { 
                    clipboardItems = listOf(File(contextMenuTarget!!.path))
                    isCutOperation = false
                    contextMenuTarget = null
                },
                onCut = {
                    clipboardItems = listOf(File(contextMenuTarget!!.path))
                    isCutOperation = true
                    contextMenuTarget = null
                },
                onDelete = {
                    scope.launch {
                        FileOperations.delete(File(contextMenuTarget!!.path))
                        if (contextMenuPane == 0) {
                            rawList = NativeCore.list(currentPath)
                        } else {
                            secondaryRawList = NativeCore.list(secondaryPath)
                        }
                        contextMenuTarget = null
                    }
                },
                onEdit = {
                    editorFile = File(contextMenuTarget!!.path)
                    showEditor = true
                    contextMenuTarget = null
                },
                onShare = {
                    sharePath(context, contextMenuTarget!!)
                    contextMenuTarget = null
                },
                onSelect = {
                    selectedPaths = selectedPaths + contextMenuTarget!!.path
                    contextMenuTarget = null
                },
                onZip = {
                    scope.launch {
                        val targetFile = File(contextMenuTarget!!.path)
                        val zipFile = File(targetFile.parent, "${targetFile.name}.zip")
                        if (targetFile.canonicalPath == zipFile.canonicalPath) {
                            Toast.makeText(context, "Cannot zip a file into itself", Toast.LENGTH_SHORT).show()
                            return@launch
                        }
                        FileOperations.zip(listOf(targetFile), zipFile)
                        if (contextMenuPane == 0) {
                            rawList = NativeCore.list(currentPath)
                        } else {
                            secondaryRawList = NativeCore.list(secondaryPath)
                        }
                        contextMenuTarget = null
                    }
                }
            )
        }
        
        // -- CREATE DIALOG --
        if (showCreateDialog) {
            CreateDialog(
                onDismiss = { showCreateDialog = false },
                onCreate = { name, isDir ->
                    scope.launch {
                        val targetPath = panePath(activePane)
                        val parent = File(targetPath)
                        if (isDir) FileOperations.createDir(parent, name)
                        else FileOperations.createFile(parent, name)
                        val updated = NativeCore.list(targetPath)
                        if (activePane == 0) rawList = updated else secondaryRawList = updated
                        showCreateDialog = false
                    }
                }
            )
        }

        // -- EDITOR --
        if (showEditor && editorFile != null) {
            TextEditor(
                file = editorFile!!,
                onDismiss = { showEditor = false; editorFile = null },
                onSave = { content ->
                    scope.launch {
                        editorFile!!.writeText(content)
                        showEditor = false
                        editorFile = null
                    }
                }
            )
        }
    }
}

data class GlaiveStats(
    val count: Int,
    val totalSize: Long,
    val selectedSize: Long,
    val dirCount: Int,
    val fileCount: Int
)

// --- COMPONENTS ---

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun GlaiveHeader(
    currentPath: String,
    isSearchActive: Boolean,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    onToggleSearch: () -> Unit,
    onPathJump: (String) -> Unit,
    currentTab: Int,
    onTabChange: (Int) -> Unit,
    isGridView: Boolean,
    onToggleView: () -> Unit,
    onAddClick: () -> Unit,
    stats: GlaiveStats,
    splitScopeEnabled: Boolean,
    onSplitToggle: () -> Unit,
) {
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusRequester = remember { FocusRequester() }
    
    // State to toggle the "Press and Reveal" tool buttons
    var toolsExpanded by remember { mutableStateOf(false) }

    LaunchedEffect(isSearchActive) {
        if (isSearchActive) {
            focusRequester.requestFocus()
            keyboardController?.show()
        } else {
            keyboardController?.hide()
        }
    }

    // Auto-collapse tools if search becomes active
    LaunchedEffect(isSearchActive) {
        if (isSearchActive) toolsExpanded = true
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(DeepSpace)
            .padding(top = 16.dp, bottom = 4.dp)
    ) {
        // --- TOP ROW: Tabs (Left) & Minimal Stats (Right) ---
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // Sleek Tab Switcher
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(SurfaceGray)
                    .padding(2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TabItem("BROWSE", currentTab == 0) { onTabChange(0) }
                TabItem("RECENTS", currentTab == 1) { onTabChange(1) }
            }

            // Minimal Stats
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "${stats.fileCount}F · ${stats.dirCount}D ",
                    style = TextStyle(color = Color.Gray, fontSize = 10.sp, fontWeight = FontWeight.Medium)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = formatSize(stats.totalSize),
                    style = TextStyle(color = NeonGreen, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // --- BOTTOM ROW: Breadcrumbs OR Tools (Press and Reveal) ---
        // Layout: [ Content Area (Weight 1) ] [ Menu Toggle ]
        
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            
            // EXPANDABLE CONTENT AREA
            Box(modifier = Modifier.weight(1f).height(42.dp), contentAlignment = Alignment.CenterStart) {
                androidx.compose.animation.AnimatedContent(
                    targetState = toolsExpanded || isSearchActive,
                    transitionSpec = {
                        (fadeIn() + slideInHorizontally { -it/2 }).togetherWith(fadeOut() + slideOutHorizontally { -it/2 })
                    },
                    label = "HeaderTools"
                ) { showTools ->
                    if (showTools) {
                        // -- TOOLBAR MODE --
                        if (isSearchActive) {
                            // Search Field
                            BasicTextField(
                                value = searchQuery,
                                onValueChange = onSearchQueryChange,
                                textStyle = TextStyle(color = SoftWhite, fontSize = 16.sp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .focusRequester(focusRequester),
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                                keyboardActions = KeyboardActions(onSearch = { keyboardController?.hide() }),
                                decorationBox = { innerTextField ->
                                    if (searchQuery.isEmpty()) Text("Type to hunt...", color = Color.Gray)
                                    innerTextField()
                                }
                            )
                        } else {
                            // Action Icons Row
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(16.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                MinimalIcon(Icons.Default.Add, onAddClick)
                                MinimalIcon(if (isGridView) Icons.Default.ViewList else Icons.Default.GridView, onToggleView)
                                MinimalIcon(Icons.Default.CallSplit, onSplitToggle, if (splitScopeEnabled) NeonGreen else Color.Gray)
                                MinimalIcon(Icons.Default.Search, onToggleSearch)
                            }
                        }
                    } else {
                        // -- BREADCRUMB MODE --
                        BreadcrumbStrip(currentPath, onPathJump)
                    }
                }
            }
            
            Spacer(modifier = Modifier.width(8.dp))

            // MENU TOGGLE BUTTON
            // Rotates when expanded
            IconButton(
                onClick = { 
                    if (isSearchActive) {
                        onToggleSearch() // Close search if active
                    } else {
                        toolsExpanded = !toolsExpanded 
                    }
                },
                modifier = Modifier
                    .size(42.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(SurfaceGray)
            ) {
                val icon = if (toolsExpanded || isSearchActive) Icons.Default.Close else Icons.Default.Menu
                Icon(
                    imageVector = icon,
                    contentDescription = "Menu",
                    tint = if(toolsExpanded) NeonGreen else SoftWhite
                )
            }
        }
    }
}

@Composable
fun TabItem(text: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(if (selected) DeepSpace else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Text(
            text = text,
            style = TextStyle(
                color = if (selected) NeonGreen else Color.Gray,
                fontWeight = FontWeight.Bold,
                fontSize = 11.sp
            )
        )
    }
}

@Composable
fun MinimalIcon(
    icon: ImageVector,
    onClick: () -> Unit,
    tint: Color = SoftWhite
) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick)
            .background(SurfaceGray.copy(alpha = 0.5f)),
        contentAlignment = Alignment.Center
    ) {
        Icon(imageVector = icon, contentDescription = null, tint = tint)
    }
}

@Composable
fun BreadcrumbStrip(currentPath: String, onPathJump: (String) -> Unit) {
    val segments = remember(currentPath) {
        val list = mutableListOf<Pair<String, String>>()
        var acc = ""
        val hidden = setOf("storage", "emulated")
        currentPath.split("/").filter { it.isNotEmpty() }.forEach { segment ->
            acc += "/$segment"
            if (hidden.contains(segment)) return@forEach
            val label = when (segment) {
                "0" -> "INT"
                else -> segment
            }
            list.add(label to acc)
        }
        if (list.isEmpty()) listOf("ROOT" to "/") else list
    }

    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        items(segments) { (name, path) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = name.uppercase(),
                    style = TextStyle(
                        color = if (path == currentPath) NeonGreen else Color.Gray,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 13.sp
                    ),
                    modifier = Modifier
                        .clickable { onPathJump(path) }
                        .padding(horizontal = 2.dp, vertical = 4.dp)
                )
                if (path != currentPath) {
                    Icon(
                        imageVector = Icons.Default.KeyboardArrowRight,
                        contentDescription = null,
                        tint = DarkGreen,
                        modifier = Modifier.size(12.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun PaneBrowser(
    paneIndex: Int,
    modifier: Modifier = Modifier,
    isGridView: Boolean,
    displayedList: List<GlaiveItem>,
    selectedPaths: Set<String>,
    sortMode: SortMode,
    onItemClick: (Int, GlaiveItem) -> Unit,
    onItemLongClick: (Int, GlaiveItem) -> Unit,
    onMaximize: (Int) -> Unit,
    onPaste: () -> Unit,
    clipboardActive: Boolean,
    directorySizes: Map<String, Long>
) {
    var showMaximizeButton by remember { mutableStateOf(true) }
    
    LaunchedEffect(showMaximizeButton) {
        if (showMaximizeButton) {
            delay(3000)
            showMaximizeButton = false
        }
    }

    Box(modifier = modifier.pointerInput(clipboardActive) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                if (event.changes.any { it.pressed }) {
                    showMaximizeButton = true
                }
            }
        }
    }.pointerInput(clipboardActive) {
        if (clipboardActive) {
            detectTapGestures(
                onTap = { onPaste() }
            )
        }
    }) {
        if (isGridView) {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 100.dp),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 140.dp, top = 8.dp, start = 16.dp, end = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(displayedList, key = { it.path }) { item ->
                    FileGridItem(
                        item = item,
                        isSelected = selectedPaths.contains(item.path),
                        onClick = { onItemClick(paneIndex, item) },
                        onLongClick = { onItemLongClick(paneIndex, item) }
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 140.dp, top = 8.dp, start = 16.dp, end = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(displayedList, key = { it.path }) { item ->
                    FileCard(
                        item = item,
                        isSelected = selectedPaths.contains(item.path),
                        sortMode = sortMode,
                        onClick = { onItemClick(paneIndex, item) },
                        onLongClick = { onItemLongClick(paneIndex, item) },
                        directorySize = directorySizes[item.path]
                    )
                }
            }
        }
        
        androidx.compose.animation.AnimatedVisibility(
            visible = showMaximizeButton,
            enter = androidx.compose.animation.fadeIn(),
            exit = androidx.compose.animation.fadeOut(),
            modifier = Modifier.align(Alignment.TopEnd)
        ) {
            IconButton(
                onClick = { onMaximize(paneIndex) },
                modifier = Modifier
                    .padding(8.dp)
                    .clip(CircleShape)
                    .background(SurfaceGray.copy(alpha = 0.8f))
            ) {
                Icon(imageVector = Icons.Default.AspectRatio, contentDescription = "Maximize", tint = NeonGreen)
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FileCard(
    item: GlaiveItem,
    isSelected: Boolean,
    sortMode: SortMode,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    directorySize: Long?
) {
    val haptic = LocalHapticFeedback.current

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(64.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (isSelected) NeonGreen.copy(alpha = 0.15f) else SurfaceGray)
            .border(
                width = if (isSelected) 1.dp else 0.dp,
                color = if (isSelected) NeonGreen else Color.Transparent,
                shape = RoundedCornerShape(12.dp)
            )
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
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier.size(32.dp),
            contentAlignment = Alignment.Center
        ) {
            if (isSelected) {
                Icon(imageVector = Icons.Default.Check, contentDescription = null, tint = NeonGreen)
            } else {
                Text(
                    text = getTypeIcon(item.type),
                    fontSize = 18.sp
                )
            }
        }

        Spacer(modifier = Modifier.width(16.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.name,
                style = TextStyle(
                    color = if (isSelected) NeonGreen else SoftWhite,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            
            val infoText = when {
                item.type == GlaiveItem.TYPE_DIR -> {
                    if (directorySize != null) formatSize(directorySize) else "..."
                }
                sortMode == SortMode.DATE -> formatDate(item.mtime)
                sortMode == SortMode.SIZE -> formatSize(item.size)
                else -> formatSize(item.size)
            }
            
            Text(
                text = infoText,
                style = TextStyle(
                    color = Color.Gray,
                    fontSize = 11.sp
                )
            )
        }
    }
}

@Composable
fun NavigationPaneOverlay(
    activePane: Int,
    splitScopeEnabled: Boolean,
    currentPath: String,
    secondaryPath: String,
    pathHistory: List<String>,
    onPaneFocus: (Int) -> Unit,
    onPathSelected: (Int, String) -> Unit,
    onDismiss: () -> Unit,
    onInteract: () -> Unit
) {
    val quickTargets = remember {
        listOf(
            "INT" to "/storage/emulated/0",
            "DL" to "/storage/emulated/0/Download",
            "DCIM" to "/storage/emulated/0/DCIM",
            "MOV" to "/storage/emulated/0/Movies",
            "DOC" to "/storage/emulated/0/Documents"
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.95f))
    ) {
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Navigation",
                    style = TextStyle(color = SoftWhite, fontWeight = FontWeight.Bold, fontSize = 20.sp),
                    modifier = Modifier.weight(1f)
                )
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(SurfaceGray)
                ) {
                    Icon(imageVector = Icons.Default.Close, contentDescription = "Close", tint = SoftWhite)
                }
            }

            if (splitScopeEnabled) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    SplitPaneCard(
                        modifier = Modifier.weight(1f),
                        title = "PANE A",
                        path = currentPath,
                        accent = NeonGreen,
                        onClick = {
                            onInteract()
                            onPaneFocus(0)
                        },
                        selected = activePane == 0
                    )
                    SplitPaneCard(
                        modifier = Modifier.weight(1f),
                        title = "PANE B",
                        path = secondaryPath,
                        accent = NeonGreen,
                        onClick = {
                            onInteract()
                            onPaneFocus(1)
                        },
                        selected = activePane == 1
                    )
                }
            } else {
                SplitPaneCard(
                    modifier = Modifier.fillMaxWidth(),
                    title = "ACTIVE",
                    path = currentPath,
                    accent = NeonGreen,
                    onClick = {
                        onInteract()
                        onPaneFocus(0)
                    },
                    selected = true
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Quick Roots",
                    style = TextStyle(color = NeonGreen, fontWeight = FontWeight.Medium, fontSize = 14.sp)
                )
                LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(quickTargets) { (label, path) ->
                        HistoryChip(
                            path = path,
                            label = label,
                            onClick = {
                                onInteract()
                                onPathSelected(activePane, path)
                            }
                        )
                    }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "History",
                    style = TextStyle(color = NeonGreen, fontWeight = FontWeight.Medium, fontSize = 14.sp)
                )
                if (pathHistory.isEmpty()) {
                    Text("No history yet.", color = Color.Gray, fontSize = 12.sp)
                } else {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(pathHistory) { path ->
                            HistoryChip(
                                path = path,
                                onClick = {
                                    onInteract()
                                    onPathSelected(activePane, path)
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}


@Composable
fun SplitPaneCard(
    modifier: Modifier = Modifier,
    title: String,
    path: String?,
    accent: Color,
    onClick: (() -> Unit)?,
    selected: Boolean = false
) {
    val label = path?.trimEnd('/')?.substringAfterLast("/")?.ifEmpty { "Root" } ?: "—"
    val clickModifier = if (path != null && onClick != null) Modifier.clickable(onClick = onClick) else Modifier
    val borderColor = if (selected) accent else accent.copy(alpha = 0.4f)
    val backgroundColor = if (selected) DeepSpace.copy(alpha = 0.9f) else DeepSpace.copy(alpha = 0.7f)
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(backgroundColor)
            .border(1.dp, borderColor, RoundedCornerShape(12.dp))
            .then(clickModifier)
            .padding(12.dp)
    ) {
        Text(title.uppercase(Locale.ROOT), color = accent, fontSize = 11.sp, fontWeight = FontWeight.Medium)
        Spacer(modifier = Modifier.height(4.dp))
        Text(label, color = SoftWhite, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun HistoryChip(path: String, label: String? = null, onClick: () -> Unit) {
    val resolvedLabel = label ?: path.trimEnd('/').substringAfterLast("/").ifEmpty { "Root" }
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(DeepSpace.copy(alpha = 0.8f))
            .border(1.dp, SurfaceGray, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Text(resolvedLabel, color = SoftWhite, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun ReverseGestureHotZone(
    modifier: Modifier = Modifier,
    hasTrail: Boolean,
    onNavigateUp: () -> Unit
) {
    val haptic = LocalHapticFeedback.current
    Box(
        modifier = modifier
            .width(28.dp)
            .pointerInput(hasTrail) {
                var dragDistance = 0f
                detectHorizontalDragGestures(
                    onHorizontalDrag = { _, dragAmount ->
                        dragDistance += dragAmount
                        if (dragDistance > 60f) {
                            onNavigateUp()
                            if (hasTrail) haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            dragDistance = 0f
                        }
                    },
                    onDragCancel = { dragDistance = 0f },
                    onDragEnd = { dragDistance = 0f }
                )
            }
            .alpha(if (hasTrail) 0.35f else 0.12f)
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color.Transparent,
                        DeepSpace.copy(alpha = 0.6f),
                        Color.Transparent
                    )
                )
            )
    ) {
        if (hasTrail) {
            Icon(
                imageVector =Icons.Default.KeyboardArrowLeft,
                contentDescription = "Reverse",
                tint = NeonGreen,
                modifier = Modifier.align(Alignment.Center)
            )
        }
    }
}

@Composable
fun ControlDock(
    modifier: Modifier = Modifier,
    currentSort: SortMode,
    ascending: Boolean,
    onSortChange: (SortMode) -> Unit,
    selectionActive: Boolean,
    onShareSelection: () -> Unit,
    onClearSelection: () -> Unit,
    clipboardActive: Boolean,
    onPaste: () -> Unit
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(Color.Black.copy(alpha = 0.8f))
            .border(1.dp, SurfaceGray, RoundedCornerShape(50))
            .padding(horizontal = 8.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        if (selectionActive) {
            IconButton(onClick = onClearSelection) { Icon(imageVector =Icons.Default.Close, contentDescription = "Clear", tint = DangerRed) }
            IconButton(onClick = onShareSelection) { Icon(imageVector =Icons.Default.Share, contentDescription = "Share", tint = NeonGreen) }
        } else if (clipboardActive) {
            IconButton(onClick = onPaste) {
                Icon(imageVector = Icons.Default.ContentPaste, contentDescription = "Paste", tint = NeonGreen)
            }
        } else {
            SortChip("Name", SortMode.NAME, currentSort, ascending, onSortChange)
            SortChip("Size", SortMode.SIZE, currentSort, ascending, onSortChange)
            SortChip("Date", SortMode.DATE, currentSort, ascending, onSortChange)
            SortChip("Type", SortMode.TYPE, currentSort, ascending, onSortChange)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FileGridItem(
    item: GlaiveItem,
    isSelected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val haptic = LocalHapticFeedback.current
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (isSelected) NeonGreen.copy(alpha = 0.15f) else SurfaceGray)
            .border(if (isSelected) 1.dp else 0.dp, if (isSelected) NeonGreen else Color.Transparent, RoundedCornerShape(12.dp))
            .combinedClickable(
                onClick = { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); onClick() },
                onLongClick = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); onLongClick() }
            )
            .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier.size(40.dp).clip(CircleShape).background(getTypeColor(item.type).copy(alpha = 0.2f)),
            contentAlignment = Alignment.Center
        ) {
            if (isSelected) {
                Icon(imageVector =Icons.Default.Check, null, tint = NeonGreen)
            } else if (item.type == GlaiveItem.TYPE_IMG) {
                AsyncImage(
                    model = File(item.path),
                    contentDescription = item.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Text(getTypeIcon(item.type), fontSize = 20.sp)
            }
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(item.name, color = SoftWhite, maxLines = 2, overflow = TextOverflow.Ellipsis, fontSize = 11.sp, textAlign = TextAlign.Center)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContextMenuSheet(
    item: GlaiveItem,
    onDismiss: () -> Unit,
    onCopy: () -> Unit,
    onCut: () -> Unit,
    onDelete: () -> Unit,
    onEdit: () -> Unit,
    onShare: () -> Unit,
    onSelect: () -> Unit,
    onZip: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = DeepSpace) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(item.name, style = MaterialTheme.typography.titleLarge, color = SoftWhite)
            Spacer(modifier = Modifier.height(16.dp))
            ContextMenuItem("Select", Icons.Default.Check, onSelect)
            ContextMenuItem("Copy", Icons.Default.Share, onCopy)
            ContextMenuItem("Cut", Icons.Default.Edit, onCut)
            ContextMenuItem("Zip", Icons.Default.Archive, onZip)
            ContextMenuItem("Delete", Icons.Default.Delete, onDelete, DangerRed)
            if (item.type != GlaiveItem.TYPE_DIR) {
                ContextMenuItem("Edit", Icons.Default.Edit, onEdit)
                ContextMenuItem("Share", Icons.Default.Share, onShare)
            }
        }
    }
}

@Composable
fun ContextMenuItem(text: String, icon: ImageVector, onClick: () -> Unit, color: Color = SoftWhite) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(imageVector =icon, null, tint = color)
        Spacer(modifier = Modifier.width(16.dp))
        Text(text, color = color, fontSize = 16.sp)
    }
}

@Composable
fun CreateDialog(onDismiss: () -> Unit, onCreate: (String, Boolean) -> Unit) {
    var name by remember { mutableStateOf("") }
    var isDir by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = SurfaceGray,
        title = { Text("Create New", color = SoftWhite) },
        text = {
            Column {
                BasicTextField(
                    value = name,
                    onValueChange = { name = it },
                    textStyle = TextStyle(color = SoftWhite, fontSize = 16.sp),
                    modifier = Modifier.fillMaxWidth().background(DeepSpace, RoundedCornerShape(8.dp)).padding(12.dp)
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = isDir, onCheckedChange = { isDir = it })
                    Text("Directory", color = SoftWhite)
                }
            }
        },
        confirmButton = {
            Button(onClick = { onCreate(name, isDir) }, colors = ButtonDefaults.buttonColors(containerColor = NeonGreen)) {
                Text("Create")
            }
        }
    )
}

@Composable
fun TextEditor(file: File, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var content by remember { mutableStateOf(file.readText()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = DeepSpace,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
        title = { Text(file.name, color = SoftWhite) },
        text = {
            BasicTextField(
                value = content,
                onValueChange = { content = it },
                textStyle = TextStyle(color = SoftWhite, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace),
                modifier = Modifier.fillMaxSize().background(SurfaceGray, RoundedCornerShape(8.dp)).padding(8.dp)
            )
        },
        confirmButton = {
            Button(onClick = { onSave(content) }, colors = ButtonDefaults.buttonColors(containerColor = NeonGreen)) {
                Text("Save")
            }
        },
        dismissButton = {
            Button(onClick = onDismiss, colors = ButtonDefaults.buttonColors(containerColor = DangerRed)) {
                Text("Close")
            }
        }
    )
}

@Composable
fun FilterBar(
    activeFilters: Set<Int>,
    onFilterToggle: (Int) -> Unit
) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item { FilterChip("Images", GlaiveItem.TYPE_IMG, activeFilters, onFilterToggle) }
        item { FilterChip("Videos", GlaiveItem.TYPE_VID, activeFilters, onFilterToggle) }
        item { FilterChip("Audio", 0, activeFilters, onFilterToggle, enabled = false) } // Placeholder
        item { FilterChip("Docs", GlaiveItem.TYPE_DOC, activeFilters, onFilterToggle) }
        item { FilterChip("APKs", GlaiveItem.TYPE_APK, activeFilters, onFilterToggle) }
    }
}

@Composable
fun FilterChip(
    label: String,
    type: Int,
    activeFilters: Set<Int>,
    onToggle: (Int) -> Unit,
    enabled: Boolean = true
) {
    val isSelected = activeFilters.contains(type)
    val backgroundColor = if (isSelected) NeonGreen else SurfaceGray
    val textColor = if (isSelected) DeepSpace else SoftWhite
    
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(backgroundColor)
            .clickable(enabled = enabled) { onToggle(type) }
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .then(if (!enabled) Modifier.alpha(0.5f) else Modifier)
    ) {
        Text(label, color = textColor, fontWeight = FontWeight.Bold, fontSize = 12.sp)
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
    val background = if (isSelected) NeonGreen else Color.Transparent
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
    GlaiveItem.TYPE_DIR -> NeonGreen
    GlaiveItem.TYPE_IMG -> Color(0xFF2979FF)
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

fun formatDate(millis: Long): String {
    if (millis <= 0) return "--"
    val date = java.util.Date(millis)
    val format = java.text.SimpleDateFormat("MMM dd, yyyy", Locale.US)
    return format.format(date)
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
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        content()
    }
}