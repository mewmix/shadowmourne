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
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
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
import com.mewmix.glaive.core.NativeCore
import com.mewmix.glaive.core.FileOperations
import com.mewmix.glaive.core.RecentFilesManager
import com.mewmix.glaive.data.GlaiveItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import kotlin.system.measureTimeMillis

// --- THEME COLORS ---
val DeepSpace = Color(0xFF121212)
val SurfaceGray = Color(0xFF1E1E1E)
val SoftWhite = Color(0xFFE0E0E0)
val AccentPurple = Color(0xFFBB86FC)
val AccentBlue = Color(0xFF03DAC6)
val AccentGreen = Color(0xFF00E676)
val DangerRed = Color(0xFFCF6679)

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
    
    // Filter State
    var activeFilters by remember { mutableStateOf<Set<Int>>(emptySet()) }
    
    // Context Menu State
    var contextMenuTarget by remember { mutableStateOf<GlaiveItem?>(null) }
    var contextMenuPane by remember { mutableStateOf(0) }
    
    val scope = rememberCoroutineScope()
    
    // Derived State: Stats
    val stats by remember(rawList, secondaryRawList, selectedPaths, activePane) {
        derivedStateOf {
            val activeList = if (activePane == 0) rawList else secondaryRawList
            val totalSize = activeList.sumOf { it.size }
            val selectedSize = activeList.filter { selectedPaths.contains(it.path) }.sumOf { it.size }
            val dirCount = activeList.count { it.type == GlaiveItem.TYPE_DIR }
            val fileCount = activeList.size - dirCount
            GlaiveStats(activeList.size, totalSize, selectedSize, dirCount, fileCount)
        }
    }
    
    // Processed List State (Background Thread Result)
    var displayedList by remember { mutableStateOf<List<GlaiveItem>>(emptyList()) }
    var secondaryDisplayedList by remember { mutableStateOf<List<GlaiveItem>>(emptyList()) }

    // Background Processing for Sorting & Filtering
    LaunchedEffect(rawList, sortMode, sortAscending, activeFilters) {
        withContext(Dispatchers.Default) {
            var list = rawList
            
            // 0. Filter
            if (activeFilters.isNotEmpty()) {
                list = list.filter { item ->
                    item.type == GlaiveItem.TYPE_DIR || activeFilters.contains(item.type)
                }
            }
            
            // 1. Sort
            list = when (sortMode) {
                SortMode.NAME -> list.sortedBy { it.name.lowercase() }
                SortMode.SIZE -> list.sortedBy { it.size }
                SortMode.DATE -> list.sortedBy { it.mtime }
                SortMode.TYPE -> list.sortedBy { it.type }
            }
            
            // 2. Direction
            if (!sortAscending) list = list.reversed()
            
            // 3. Always keep Directories on top
            list = list.sortedByDescending { it.type == GlaiveItem.TYPE_DIR }
            
            // Update UI on Main Thread
            withContext(Dispatchers.Main) {
                displayedList = list
            }
        }
    }

    LaunchedEffect(secondaryRawList, sortMode, sortAscending, activeFilters) {
        withContext(Dispatchers.Default) {
            var list = secondaryRawList
            if (activeFilters.isNotEmpty()) {
                list = list.filter { item ->
                    item.type == GlaiveItem.TYPE_DIR || activeFilters.contains(item.type)
                }
            }
            list = when (sortMode) {
                SortMode.NAME -> list.sortedBy { it.name.lowercase() }
                SortMode.SIZE -> list.sortedBy { it.size }
                SortMode.DATE -> list.sortedBy { it.mtime }
                SortMode.TYPE -> list.sortedBy { it.type }
            }
            if (!sortAscending) list = list.reversed()
            list = list.sortedByDescending { it.type == GlaiveItem.TYPE_DIR }
            withContext(Dispatchers.Main) {
                secondaryDisplayedList = list
            }
        }
    }

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

    // Navigation Helper
    fun navigateTo(paneIndex: Int, path: String) {
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

    fun toggleSearch(paneIndex: Int) {
        val currentlyActive = paneIsSearchActive(paneIndex)
        if (currentlyActive) setPaneSearchQuery(paneIndex, "")
        setPaneSearchActive(paneIndex, !currentlyActive)
    }

    // Load Data / Search with Debounce
    LaunchedEffect(currentPath, searchQuery, currentTab) {
        if (currentTab == 1) {
            rawList = RecentFilesManager.getRecents(context)
        } else {
            if (searchQuery.isEmpty()) {
                rawList = NativeCore.list(currentPath)
            } else {
                delay(300)
                rawList = NativeCore.search(currentPath, searchQuery)
            }
        }
    }

    LaunchedEffect(secondaryPath, secondarySearchQuery, secondaryCurrentTab) {
        if (secondaryCurrentTab == 1) {
            secondaryRawList = RecentFilesManager.getRecents(context)
        } else {
            if (secondarySearchQuery.isEmpty()) {
                secondaryRawList = NativeCore.list(secondaryPath)
            } else {
                delay(300)
                secondaryRawList = NativeCore.search(secondaryPath, secondarySearchQuery)
            }
        }
    }

    LaunchedEffect(splitScopeEnabled) {
        if (!splitScopeEnabled) {
            activePane = 0
        } else if (pathHistory.isNotEmpty() && secondaryPath == currentPath) {
            secondaryPath = pathHistory.first()
        }
    }

    val activePanePath = panePath(activePane)
    val activePaneSearch = paneIsSearchActive(activePane)

    // Back Handler
    BackHandler(enabled = activePanePath != "/storage/emulated/0" || activePaneSearch || selectedPaths.isNotEmpty()) {
        if (selectedPaths.isNotEmpty()) {
            selectedPaths = emptySet()
        } else if (activePaneSearch) {
            setPaneSearchActive(activePane, false)
            setPaneSearchQuery(activePane, "")
        } else {
            File(activePanePath).parent?.let { navigateTo(activePane, it) }
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
            
            // -- TOP HEADER --
            GlaiveHeader(
                currentPath = activePanePath,
                isSearchActive = activePaneSearch,
                searchQuery = paneSearchQuery(activePane),
                onSearchQueryChange = { setPaneSearchQuery(activePane, it) },
                onToggleSearch = { toggleSearch(activePane) },
                onPathJump = { navigateTo(activePane, it) },
                onHomeJump = { 
                    navigateTo(activePane, "/storage/emulated/0")
                    setPaneCurrentTab(activePane, 0)
                },
                currentTab = paneCurrentTab(activePane),
                onTabChange = { setPaneCurrentTab(activePane, it) },
                isGridView = isGridView,
                onToggleView = { isGridView = !isGridView },
                onAddClick = { showCreateDialog = true },
                stats = stats,
                splitScopeEnabled = splitScopeEnabled,
                onSplitToggle = { splitScopeEnabled = !splitScopeEnabled },
                pathHistory = pathHistory,
                onHistoryJump = { navigateTo(activePane, it) },
                activePane = activePane,
                onPaneFocus = { activePane = it },
                secondaryPath = secondaryPath
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
                        PaneBrowser(
                            paneIndex = 0,
                            modifier = Modifier
                                .weight(splitFraction)
                                .fillMaxHeight(),
                            isGridView = isGridView,
                            displayedList = displayedList,
                            selectedPaths = selectedPaths,
                            sortMode = sortMode,
                            onItemClick = handleItemClick,
                            onItemLongClick = handleItemLongPress
                        )
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
                        PaneBrowser(
                            paneIndex = 1,
                            modifier = Modifier
                                .weight(1f - splitFraction)
                                .fillMaxHeight(),
                            isGridView = isGridView,
                            displayedList = secondaryDisplayedList,
                            selectedPaths = selectedPaths,
                            sortMode = sortMode,
                            onItemClick = handleItemClick,
                            onItemLongClick = handleItemLongPress
                        )
                    }
                }
            } else {
                PaneBrowser(
                    paneIndex = activePane,
                    modifier = Modifier.weight(1f),
                    isGridView = isGridView,
                    displayedList = if (activePane == 0) displayedList else secondaryDisplayedList,
                    selectedPaths = selectedPaths,
                    sortMode = sortMode,
                    onItemClick = handleItemClick,
                    onItemLongClick = handleItemLongPress
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
                onPaste = {
                    scope.launch {
                        val targetPath = panePath(activePane)
                        val dest = File(targetPath)
                        clipboardItems.forEach { src ->
                            if (isCutOperation) FileOperations.move(src, dest)
                            else FileOperations.copy(src, dest)
                        }
                        if (isCutOperation) clipboardItems = emptyList()
                        // Refresh
                        val updated = NativeCore.list(targetPath)
                        if (activePane == 0) rawList = updated else secondaryRawList = updated
                    }
                }
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
    onHomeJump: () -> Unit,
    currentTab: Int,
    onTabChange: (Int) -> Unit,
    isGridView: Boolean,
    onToggleView: () -> Unit,
    onAddClick: () -> Unit,
    stats: GlaiveStats,
    splitScopeEnabled: Boolean,
    onSplitToggle: () -> Unit,
    pathHistory: List<String>,
    onHistoryJump: (String) -> Unit,
    activePane: Int,
    onPaneFocus: (Int) -> Unit,
    secondaryPath: String
) {
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(isSearchActive) {
        if (isSearchActive) {
            focusRequester.requestFocus()
            keyboardController?.show()
        } else {
            keyboardController?.hide()
        }
    }

    val headerTitle = when {
        isSearchActive -> "Search"
        currentTab == 1 -> "Recent Files"
        else -> "Browse"
    }

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
        // Tab Row
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.Center
        ) {
            TabButton("Browse", currentTab == 0) { onTabChange(0) }
            Spacer(modifier = Modifier.width(16.dp))
            TabButton("Recents", currentTab == 1) { onTabChange(1) }
        }

        // Title Row
        Row(
            modifier = Modifier.padding(horizontal = 24.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = headerTitle,
                style = TextStyle(
                    color = SoftWhite,
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold
                ),
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // Shortcuts
                if (!isSearchActive && currentTab == 0) {
                    IconButton(onClick = onAddClick, modifier = Modifier.clip(CircleShape).background(SurfaceGray)) {
                        Icon(imageVector =Icons.Default.Add, contentDescription = "New", tint = AccentGreen)
                    }
                    IconButton(onClick = onToggleView, modifier = Modifier.clip(CircleShape).background(SurfaceGray)) {
                        Icon(imageVector = if (isGridView) Icons.Default.List else Icons.Default.Menu, contentDescription = "View", tint = SoftWhite)
                    }
                    IconButton(onClick = onSplitToggle, modifier = Modifier.clip(CircleShape).background(SurfaceGray)) {
                        Icon(
                            imageVector =Icons.Default.MoreVert,
                            contentDescription = "Split",
                            tint = if (splitScopeEnabled) AccentGreen else SoftWhite
                        )
                    }
                    IconButton(onClick = onHomeJump, modifier = Modifier.clip(CircleShape).background(SurfaceGray)) {
                        Icon(imageVector =Icons.Default.Home, contentDescription = "Home", tint = SoftWhite)
                    }
                }

                IconButton(
                    onClick = onToggleSearch,
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(SurfaceGray)
                ) {
                    Icon(imageVector = if (isSearchActive) Icons.Default.KeyboardArrowUp else Icons.Default.Search,
                        contentDescription = "Search",
                        tint = AccentBlue
                    )
                }
            }
        }
        // ... (Search Bar / Breadcrumbs logic remains same)

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
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(focusRequester),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(
                            onSearch = { keyboardController?.hide() }
                        ),
                        decorationBox = { innerTextField ->
                            if (searchQuery.isEmpty()) Text("Type to hunt...", color = Color.Gray)
                            innerTextField()
                        }
                    )
                }
            } else {
                BreadcrumbStrip(currentPath, onPathJump)
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp),
            horizontalArrangement = Arrangement.Center
        ) {
            StatsBar(stats)
        }

        SplitScopePreview(
            enabled = splitScopeEnabled,
            primaryPath = currentPath,
            secondaryPath = secondaryPath,
            history = pathHistory,
            onHistoryJump = onHistoryJump,
            activePane = activePane,
            onPaneFocus = onPaneFocus
        )
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
                "0" -> "Internal"
                else -> segment
            }
            list.add(label to acc)
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

@Composable
fun PaneBrowser(
    paneIndex: Int,
    modifier: Modifier = Modifier,
    isGridView: Boolean,
    displayedList: List<GlaiveItem>,
    selectedPaths: Set<String>,
    sortMode: SortMode,
    onItemClick: (Int, GlaiveItem) -> Unit,
    onItemLongClick: (Int, GlaiveItem) -> Unit
) {
    if (isGridView) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 100.dp),
            modifier = modifier.fillMaxHeight(),
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
            modifier = modifier.fillMaxHeight(),
            contentPadding = PaddingValues(bottom = 140.dp, top = 8.dp, start = 16.dp, end = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(displayedList, key = { it.path }) { item ->
                FileCard(
                    item = item,
                    isSelected = selectedPaths.contains(item.path),
                    sortMode = sortMode,
                    onClick = { onItemClick(paneIndex, item) },
                    onLongClick = { onItemLongClick(paneIndex, item) }
                )
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
            .background(if (isSelected) AccentBlue.copy(alpha = 0.2f) else SurfaceGray)
            .border(
                width = if (isSelected) 2.dp else 0.dp,
                color = if (isSelected) AccentBlue else Color.Transparent,
                shape = RoundedCornerShape(24.dp)
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
            if (isSelected) {
                Icon(imageVector =Icons.Default.Check, contentDescription = null, tint = AccentBlue)
            } else {
                Text(
                    text = getTypeIcon(item.type),
                    fontSize = 20.sp
                )
            }
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
            
            val infoText = when {
                item.type == GlaiveItem.TYPE_DIR -> "Folder"
                sortMode == SortMode.DATE -> formatDate(item.mtime)
                sortMode == SortMode.SIZE -> formatSize(item.size)
                else -> formatSize(item.size)
            }
            
            Text(
                text = infoText,
                style = TextStyle(
                    color = Color.Gray,
                    fontSize = 12.sp
                )
            )
        }

        // Action Indicator
        if (item.type == GlaiveItem.TYPE_DIR) {
            Icon(imageVector = Icons.Default.KeyboardArrowRight,
                contentDescription = null,
                tint = Color.Gray
            )
        }
    }
}

@Composable
fun StatsBar(stats: GlaiveStats) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(SurfaceGray.copy(alpha = 0.8f))
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        StatItem("Files", "${stats.fileCount}")
        StatItem("Dirs", "${stats.dirCount}")
        StatItem("Total", formatSize(stats.totalSize))
        if (stats.selectedSize > 0) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(AccentBlue)
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            ) {
                Text(
                    text = "Sel: ${formatSize(stats.selectedSize)}",
                    style = TextStyle(color = DeepSpace, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                )
            }
        }
    }
}

@Composable
fun StatItem(label: String, value: String) {
    Row(verticalAlignment = Alignment.Bottom) {
        Text(text = value, style = TextStyle(color = SoftWhite, fontWeight = FontWeight.Bold, fontSize = 12.sp))
        Spacer(modifier = Modifier.width(2.dp))
        Text(text = label, style = TextStyle(color = Color.Gray, fontSize = 10.sp))
    }
}

@Composable
fun SplitScopePreview(
    enabled: Boolean,
    primaryPath: String,
    secondaryPath: String,
    history: List<String>,
    onHistoryJump: (String) -> Unit,
    activePane: Int,
    onPaneFocus: (Int) -> Unit
) {
    AnimatedVisibility(visible = enabled) {
        val upcoming = history.firstOrNull()
        val hasSecondaryPath = secondaryPath.isNotEmpty()
        val secondaryDisplay = if (hasSecondaryPath) secondaryPath else upcoming ?: ""
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 12.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(SurfaceGray.copy(alpha = 0.7f))
                .border(1.dp, AccentBlue.copy(alpha = 0.4f), RoundedCornerShape(24.dp))
                .padding(16.dp)
        ) {
            Text(
                text = "Split Scope",
                style = TextStyle(color = AccentBlue, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            )
            Spacer(modifier = Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                SplitPaneCard(
                    modifier = Modifier.weight(1f),
                    title = "Pane A",
                    path = primaryPath,
                    accent = AccentBlue,
                    onClick = { onPaneFocus(0) },
                    selected = activePane == 0
                )
                SplitPaneCard(
                    modifier = Modifier.weight(1f),
                    title = "Pane B",
                    path = secondaryDisplay.takeIf { it.isNotEmpty() },
                    accent = AccentGreen,
                    onClick = when {
                        hasSecondaryPath -> { { onPaneFocus(1) } }
                        upcoming != null -> { { onHistoryJump(upcoming) } }
                        else -> null
                    },
                    selected = activePane == 1
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            if (history.isNotEmpty()) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(history.take(6)) { path ->
                        HistoryChip(path = path, onClick = { onHistoryJump(path) })
                    }
                }
            } else {
                Text("No previous directories yet.", color = Color.Gray, fontSize = 12.sp)
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
    val pathLine = path ?: ""
    val clickModifier = if (path != null && onClick != null) {
        Modifier.clickable(onClick = onClick)
    } else {
        Modifier
    }
    val borderColor = if (selected) accent else accent.copy(alpha = 0.4f)
    val backgroundColor = if (selected) DeepSpace.copy(alpha = 0.9f) else DeepSpace.copy(alpha = 0.7f)
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .background(backgroundColor)
            .border(1.dp, borderColor, RoundedCornerShape(20.dp))
            .then(clickModifier)
            .padding(12.dp)
    ) {
        Text(title.uppercase(Locale.ROOT), color = accent, fontSize = 11.sp, fontWeight = FontWeight.Medium)
        Spacer(modifier = Modifier.height(4.dp))
        Text(label, color = SoftWhite, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(modifier = Modifier.height(2.dp))
        Text(pathLine.ifEmpty { "..." }, color = Color.Gray, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun HistoryChip(path: String, onClick: () -> Unit) {
    val label = path.trimEnd('/').substringAfterLast("/").ifEmpty { "Root" }
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(DeepSpace.copy(alpha = 0.8f))
            .border(1.dp, SurfaceGray, RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Text(label, color = SoftWhite, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Text(path, color = Color.Gray, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
                tint = AccentGreen,
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
            IconButton(onClick = onShareSelection) { Icon(imageVector =Icons.Default.Share, contentDescription = "Share", tint = AccentBlue) }
        } else if (clipboardActive) {
            IconButton(onClick = onPaste) { 
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 8.dp)) {
                    Icon(imageVector =Icons.Default.Check, contentDescription = "Paste", tint = AccentGreen)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Paste", color = AccentGreen, fontWeight = FontWeight.Bold)
                }
            }
        } else {
            SortChip("Name", SortMode.NAME, currentSort, ascending, onSortChange)
            SortChip("Size", SortMode.SIZE, currentSort, ascending, onSortChange)
            SortChip("Date", SortMode.DATE, currentSort, ascending, onSortChange)
            SortChip("Type", SortMode.TYPE, currentSort, ascending, onSortChange)
        }
    }
}

@Composable
fun TabButton(text: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(if (selected) AccentBlue else SurfaceGray)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Text(text, color = if (selected) DeepSpace else SoftWhite, fontWeight = FontWeight.Bold)
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
            .clip(RoundedCornerShape(16.dp))
            .background(if (isSelected) AccentBlue.copy(alpha = 0.2f) else SurfaceGray)
            .border(if (isSelected) 2.dp else 0.dp, if (isSelected) AccentBlue else Color.Transparent, RoundedCornerShape(16.dp))
            .combinedClickable(
                onClick = { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); onClick() },
                onLongClick = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); onLongClick() }
            )
            .padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier.size(48.dp).clip(CircleShape).background(getTypeColor(item.type).copy(alpha = 0.2f)),
            contentAlignment = Alignment.Center
        ) {
            if (isSelected) Icon(imageVector =Icons.Default.Check, null, tint = AccentBlue) else Text(getTypeIcon(item.type), fontSize = 24.sp)
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(item.name, color = SoftWhite, maxLines = 2, overflow = TextOverflow.Ellipsis, fontSize = 12.sp, textAlign = TextAlign.Center)
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
    onSelect: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = DeepSpace) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(item.name, style = MaterialTheme.typography.titleLarge, color = SoftWhite)
            Spacer(modifier = Modifier.height(16.dp))
            ContextMenuItem("Select", Icons.Default.Check, onSelect)
            ContextMenuItem("Copy", Icons.Default.Share, onCopy)
            ContextMenuItem("Cut", Icons.Default.Edit, onCut)
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
            Button(onClick = { onCreate(name, isDir) }, colors = ButtonDefaults.buttonColors(containerColor = AccentBlue)) {
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
            Button(onClick = { onSave(content) }, colors = ButtonDefaults.buttonColors(containerColor = AccentGreen)) {
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
    val backgroundColor = if (isSelected) AccentBlue else SurfaceGray
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

fun formatDate(millis: Long): String {
    if (millis <= 0) return "--"
    val date = java.util.Date(millis)
    val format = java.text.SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.US)
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
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        content()
    }
}
