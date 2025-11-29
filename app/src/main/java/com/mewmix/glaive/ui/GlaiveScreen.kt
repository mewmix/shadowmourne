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
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Home
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
import androidx.compose.material.icons.filled.Settings
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
import com.mewmix.glaive.core.FavoritesManager
import com.mewmix.glaive.data.GlaiveItem

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import kotlin.system.measureTimeMillis

enum class SortMode { NAME, DATE, SIZE, TYPE }

const val ROOT_PATH = "/storage/emulated/0"

@Composable
fun GlaiveScreen() {
    val context = LocalContext.current
    
    // Theme State
    var themeConfig by remember { mutableStateOf(ThemeManager.loadTheme(context)) }
    var showThemeSettings by remember { mutableStateOf(false) }

    GlaiveTheme(config = themeConfig) {
        val theme = LocalGlaiveTheme.current

        // State
        var currentPath by remember { mutableStateOf(ROOT_PATH) }
        var rawList by remember { mutableStateOf<List<GlaiveItem>>(emptyList()) }
        var secondaryPath by remember { mutableStateOf(ROOT_PATH) }
        var secondaryRawList by remember { mutableStateOf<List<GlaiveItem>>(emptyList()) }
        var sortMode by remember { mutableStateOf(SortMode.NAME) }
        var sortAscending by remember { mutableStateOf(true) }
        var searchQuery by remember { mutableStateOf("") }
        var isSearchActive by remember { mutableStateOf(false) }
        var isSearching by remember { mutableStateOf(false) }
        var secondarySearchQuery by remember { mutableStateOf("") }
        var secondaryIsSearchActive by remember { mutableStateOf(false) }
        var secondaryIsSearching by remember { mutableStateOf(false) }
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
        var showMultiSelectionMenu by remember { mutableStateOf(false) }

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
        fun paneIsSearching(index: Int): Boolean = if (index == 0) isSearching else secondaryIsSearching

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
                    if (targetPath.contains(".zip")) {
                        val zipPath = targetPath.substringBefore(".zip") + ".zip"
                        val internalPath = targetPath.substringAfter(".zip", "")
                        val cleanInternal = if (internalPath.startsWith("/")) internalPath.substring(1) else internalPath

                        FileOperations.addToZip(File(zipPath), clipboardItems, cleanInternal)

                        // Refresh
                        if (paneIndex == 0) {
                            rawList = FileOperations.listZip(zipPath, cleanInternal)
                        } else {
                            secondaryRawList = FileOperations.listZip(zipPath, cleanInternal)
                        }
                    } else {
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
                if (!path.startsWith(ROOT_PATH)) return@log

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
                isSearching = true
                try {
                    if (currentTab == 1) {
                        rawList = FavoritesManager.getFavorites(context)
                    } else {
                        if (searchQuery.isEmpty()) {
                            rawList = NativeCore.list(
                                currentPath,
                                getSortModeInt(sortMode),
                                sortAscending,
                                getFilterMask(activeFilters)
                            )
                        } else {
                            if (currentPath.contains(".zip")) {
                                val zipPath = currentPath.substringBefore(".zip") + ".zip"
                                val internalPath = currentPath.substringAfter(".zip", "")
                                val cleanInternal = if (internalPath.startsWith("/")) internalPath.substring(1) else internalPath
                                rawList = FileOperations.listZip(zipPath, cleanInternal)
                            } else {
                                delay(150)
                                rawList = NativeCore.search(currentPath, searchQuery, getFilterMask(activeFilters))
                            }
                        }
                    }
                } finally {
                    isSearching = false
                }
            }
        }

        LaunchedEffect(secondaryPath, secondarySearchQuery, secondaryCurrentTab, sortMode, sortAscending, activeFilters, splitScopeEnabled) {
            if (!splitScopeEnabled) return@LaunchedEffect
            DebugLogger.logSuspend("Loading data for secondary path: $secondaryPath") {
                secondaryIsSearching = true
                try {
                    if (secondaryCurrentTab == 1) {
                        secondaryRawList = FavoritesManager.getFavorites(context)
                    } else {
                        if (secondarySearchQuery.isEmpty()) {
                            secondaryRawList = NativeCore.list(
                                secondaryPath,
                                getSortModeInt(sortMode),
                                sortAscending,
                                getFilterMask(activeFilters)
                            )
                        } else {
                            if (secondaryPath.contains(".zip")) {
                                val zipPath = secondaryPath.substringBefore(".zip") + ".zip"
                                val internalPath = secondaryPath.substringAfter(".zip", "")
                                val cleanInternal = if (internalPath.startsWith("/")) internalPath.substring(1) else internalPath
                                secondaryRawList = FileOperations.listZip(zipPath, cleanInternal)
                            } else {
                                delay(150)
                                secondaryRawList = NativeCore.search(secondaryPath, secondarySearchQuery, getFilterMask(activeFilters))
                            }
                        }
                    }
                } finally {
                    secondaryIsSearching = false
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

        BackHandler(enabled = (activePanePath != ROOT_PATH || activePaneSearch || selectedPaths.isNotEmpty()) || (splitScopeEnabled && secondaryPath != ROOT_PATH)) {
            if (selectedPaths.isNotEmpty()) {
                selectedPaths = emptySet()
            } else if (activePaneSearch) {
                setPaneSearchActive(activePane, false)
                setPaneSearchQuery(activePane, "")
            } else {
                if (splitScopeEnabled && activePane == 1 && secondaryPath != ROOT_PATH) {
                    File(secondaryPath).parent?.let {
                        if (it.startsWith(ROOT_PATH)) navigateTo(1, it)
                    }
                } else if (activePanePath != ROOT_PATH) {
                    if (activePanePath.contains(".zip") && !activePanePath.endsWith(".zip")) {
                         // Inside zip, go up
                         val parent = activePanePath.substringBeforeLast("/")
                         navigateTo(activePane, parent)
                    } else {
                        File(activePanePath).parent?.let {
                            if (it.startsWith(ROOT_PATH)) navigateTo(activePane, it)
                        }
                    }
                }
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(theme.colors.background)
        ) {
            val handleItemClick: (Int, GlaiveItem) -> Unit = { paneIndex, item ->
                activePane = paneIndex
                if (selectedPaths.isNotEmpty()) {
                    selectedPaths = if (selectedPaths.contains(item.path)) {
                        selectedPaths - item.path
                    } else {
                        selectedPaths + item.path
                    }
                } else if (item.type == GlaiveItem.TYPE_DIR || File(item.path).isDirectory || item.path.endsWith(".zip")) {
                    if (paneCurrentTab(paneIndex) == 1) {
                        setPaneCurrentTab(paneIndex, 0)
                    }
                    navigateTo(paneIndex, item.path)
                } else {
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
                    isSearching = paneIsSearching(activePane),
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
                    onHomeClick = { navigateTo(activePane, ROOT_PATH) },
                    onThemeSettings = { showThemeSettings = true }
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
                                    directorySizes = directorySizes,
                                    canMaximize = splitScopeEnabled && maximizedPane == -1
                                )
                            }
                            if (maximizedPane == -1) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxHeight()
                                        .width(12.dp)
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(theme.colors.surface.copy(alpha = 0.9f))
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
                                            .background(theme.colors.accent.copy(alpha = 0.7f))
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
                                    directorySizes = directorySizes,
                                    canMaximize = splitScopeEnabled && maximizedPane == -1
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
                        directorySizes = directorySizes,
                        canMaximize = false
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
                    onMoreSelection = { showMultiSelectionMenu = true },
                    clipboardActive = clipboardItems.isNotEmpty(),
                    onPaste = { handlePaste(activePane) }
                )
            }

            ReverseGestureHotZone(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .fillMaxHeight(),
                hasTrail = (pathHistory.isNotEmpty() || (File(activePanePath).parent?.startsWith(ROOT_PATH) == true && activePanePath != ROOT_PATH)),
                onNavigateUp = {
                    val parent = File(activePanePath).parent
                    if (parent != null && parent.startsWith(ROOT_PATH) && activePanePath != ROOT_PATH) {
                        navigateTo(activePane, parent)
                    }
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
                            val path = contextMenuTarget!!.path
                            if (path.contains(".zip")) {
                                val zipPath = path.substringBefore(".zip") + ".zip"
                                val internalPath = path.substringAfter(".zip", "")
                                val cleanInternal = if (internalPath.startsWith("/")) internalPath.substring(1) else internalPath

                                FileOperations.removeFromZip(File(zipPath), listOf(cleanInternal))

                                // Refresh
                                val parentInternal = cleanInternal.substringBeforeLast("/", "")
                                if (contextMenuPane == 0) {
                                    rawList = FileOperations.listZip(zipPath, parentInternal)
                                } else {
                                    secondaryRawList = FileOperations.listZip(zipPath, parentInternal)
                                }
                            } else {
                                FileOperations.delete(File(path))
                                if (contextMenuPane == 0) {
                                    rawList = NativeCore.list(currentPath)
                                } else {
                                    secondaryRawList = NativeCore.list(secondaryPath)
                                }
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
                    },
                    isFavorite = FavoritesManager.isFavorite(context, contextMenuTarget!!.path),
                    onFavorite = { shouldAdd ->
                        if (shouldAdd) FavoritesManager.addFavorite(context, contextMenuTarget!!.path)
                        else FavoritesManager.removeFavorite(context, contextMenuTarget!!.path)

                        // Refresh if in Favorites tab
                        if (paneCurrentTab(activePane) == 1) {
                             if (activePane == 0) rawList = FavoritesManager.getFavorites(context)
                             else secondaryRawList = FavoritesManager.getFavorites(context)
                        }
                        contextMenuTarget = null
                    },
                    onUnzip = {
                        scope.launch {
                            val targetFile = File(contextMenuTarget!!.path)
                            val destDir = targetFile.parentFile ?: targetFile
                            FileOperations.unzip(targetFile, destDir)
                            val updated = NativeCore.list(panePath(activePane))
                            if (activePane == 0) rawList = updated else secondaryRawList = updated
                            contextMenuTarget = null
                        }
                    },
                    onOpenFileLocation = {
                        scope.launch {
                            val targetFile = File(contextMenuTarget!!.path)
                            val parent = targetFile.parent
                            if (parent != null) {
                                if (paneIsSearchActive(contextMenuPane)) {
                                    setPaneSearchActive(contextMenuPane, false)
                                    setPaneSearchQuery(contextMenuPane, "")
                                }
                                navigateTo(contextMenuPane, parent)
                                selectedPaths = setOf(targetFile.absolutePath)
                            }
                            contextMenuTarget = null
                        }
                    }
                )
            }

            // -- MULTI SELECTION MENU --
            if (showMultiSelectionMenu) {
                MultiSelectionMenuSheet(
                    count = selectedPaths.size,
                    onDismiss = { showMultiSelectionMenu = false },
                    onCopy = {
                        clipboardItems = selectedPaths.map { File(it) }
                        isCutOperation = false
                        selectedPaths = emptySet()
                        showMultiSelectionMenu = false
                    },
                    onCut = {
                        clipboardItems = selectedPaths.map { File(it) }
                        isCutOperation = true
                        selectedPaths = emptySet()
                        showMultiSelectionMenu = false
                    },
                    onDelete = {
                        scope.launch {
                            val firstPath = selectedPaths.firstOrNull()
                            if (firstPath != null && firstPath.contains(".zip")) {
                                 val zipPath = firstPath.substringBefore(".zip") + ".zip"
                                 val internalPaths = selectedPaths.map {
                                     val ip = it.substringAfter(".zip", "")
                                     if (ip.startsWith("/")) ip.substring(1) else ip
                                 }

                                 FileOperations.removeFromZip(File(zipPath), internalPaths)

                                 // Refresh
                                 val firstInternal = internalPaths.first()
                                 val parentInternal = firstInternal.substringBeforeLast("/", "")
                                 if (activePane == 0) {
                                     rawList = FileOperations.listZip(zipPath, parentInternal)
                                 } else {
                                     secondaryRawList = FileOperations.listZip(zipPath, parentInternal)
                                 }
                            } else {
                                selectedPaths.forEach { FileOperations.delete(File(it)) }
                                val updated = NativeCore.list(panePath(activePane))
                                if (activePane == 0) rawList = updated else secondaryRawList = updated
                            }
                            selectedPaths = emptySet()
                            showMultiSelectionMenu = false
                        }
                    },
                    onZip = {
                        scope.launch {
                            val files = selectedPaths.map { File(it) }
                            if (files.isNotEmpty()) {
                                val parent = files.first().parentFile
                                val zipName = if (files.size == 1) "${files.first().name}.zip" else "archive_${System.currentTimeMillis()}.zip"
                                val zipFile = File(parent, zipName)
                                FileOperations.zip(files, zipFile)
                                val updated = NativeCore.list(panePath(activePane))
                                if (activePane == 0) rawList = updated else secondaryRawList = updated
                            }
                            selectedPaths = emptySet()
                            showMultiSelectionMenu = false
                        }
                    },
                    onCopyPath = {
                        val text = selectedPaths.joinToString("\n")
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        val clip = android.content.ClipData.newPlainText("Paths", text)
                        clipboard.setPrimaryClip(clip)
                        Toast.makeText(context, "Paths copied to clipboard", Toast.LENGTH_SHORT).show()
                        selectedPaths = emptySet()
                        showMultiSelectionMenu = false
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

            // -- THEME SETTINGS --
            if (showThemeSettings) {
                ThemeSettingsDialog(
                    currentTheme = themeConfig,
                    onDismiss = { showThemeSettings = false },
                    onApply = { newConfig ->
                        themeConfig = newConfig
                        ThemeManager.saveTheme(context, newConfig)
                        showThemeSettings = false
                    },
                    onReset = {
                        val defaults = ThemeConfig(ThemeDefaults.Colors, ThemeDefaults.Shapes)
                        themeConfig = defaults
                        ThemeManager.saveTheme(context, defaults)
                        showThemeSettings = false
                    }
                )
            }
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
    isSearching: Boolean,
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
    onHomeClick: () -> Unit,
    onThemeSettings: () -> Unit
) {
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusRequester = remember { FocusRequester() }
    val theme = LocalGlaiveTheme.current
    
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
            .background(theme.colors.background)
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
                    .background(theme.colors.surface)
                    .padding(2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TabItem("BROWSE", currentTab == 0) { onTabChange(0) }
                TabItem("FAVORITES", currentTab == 1) { onTabChange(1) }
            }

            // Minimal Stats
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "${stats.fileCount} Files · ${stats.dirCount} Folders ",
                    style = TextStyle(color = Color.Gray, fontSize = 10.sp, fontWeight = FontWeight.Medium)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = formatSize(stats.totalSize),
                    style = TextStyle(color = theme.colors.accent, fontWeight = FontWeight.Bold, fontSize = 11.sp)
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
                            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
                                BasicTextField(
                                    value = searchQuery,
                                    onValueChange = onSearchQueryChange,
                                    textStyle = TextStyle(color = theme.colors.text, fontSize = 16.sp),
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
                                if (isSearching) {
                                    CircularProgressIndicator(
                                        modifier = Modifier
                                            .size(20.dp)
                                            .align(Alignment.CenterEnd),
                                        color = theme.colors.accent,
                                        strokeWidth = 2.dp
                                    )
                                }
                            }
                        } else {
                            // Action Icons Row
                            LazyRow(
                                horizontalArrangement = Arrangement.spacedBy(16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                item { MinimalIcon(Icons.Default.Add, onAddClick) }
                                item { MinimalIcon(Icons.Default.Home, onHomeClick) }
                                item { MinimalIcon(if (isGridView) Icons.Default.ViewList else Icons.Default.GridView, onToggleView) }
                                item { MinimalIcon(Icons.Default.CallSplit, onSplitToggle, if (splitScopeEnabled) theme.colors.accent else Color.Gray) }
                                item { MinimalIcon(Icons.Default.Search, onToggleSearch) }
                                item { MinimalIcon(Icons.Default.Settings, onThemeSettings) }
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
                    .background(theme.colors.surface)
            ) {
                val icon = if (toolsExpanded || isSearchActive) Icons.Default.Close else Icons.Default.Menu
                Icon(
                    imageVector = icon,
                    contentDescription = "Menu",
                    tint = if(toolsExpanded) theme.colors.accent else theme.colors.text
                )
            }
        }
    }
}

@Composable
fun TabItem(text: String, selected: Boolean, onClick: () -> Unit) {
    val theme = LocalGlaiveTheme.current
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(if (selected) theme.colors.background else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Text(
            text = text,
            style = TextStyle(
                color = if (selected) theme.colors.accent else Color.Gray,
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
    tint: Color? = null
) {
    val theme = LocalGlaiveTheme.current
    val resolvedTint = tint ?: theme.colors.text
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick)
            .background(theme.colors.surface.copy(alpha = 0.5f)),
        contentAlignment = Alignment.Center
    ) {
        Icon(imageVector = icon, contentDescription = null, tint = resolvedTint)
    }
}

@Composable
fun BreadcrumbStrip(currentPath: String, onPathJump: (String) -> Unit) {
    val theme = LocalGlaiveTheme.current
    val segments = remember(currentPath) {
        val list = mutableListOf<Pair<String, String>>()
        var acc = ""
        val hidden = setOf("storage", "emulated")
        currentPath.split("/").filter { it.isNotEmpty() }.forEach { segment ->
            acc += "/$segment"
            if (hidden.contains(segment)) return@forEach
            val label = when (segment) {
                "0" -> "/"
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
                        color = if (path == currentPath) theme.colors.accent else Color.Gray,
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
                        tint = theme.colors.accent.copy(alpha = 0.5f),
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
    directorySizes: Map<String, Long>,
    canMaximize: Boolean = false
) {
    val theme = LocalGlaiveTheme.current
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
            visible = showMaximizeButton && canMaximize,
            enter = androidx.compose.animation.fadeIn(),
            exit = androidx.compose.animation.fadeOut(),
            modifier = Modifier.align(Alignment.TopEnd)
        ) {
            IconButton(
                onClick = { onMaximize(paneIndex) },
                modifier = Modifier
                    .padding(8.dp)
                    .clip(CircleShape)
                    .background(theme.colors.surface.copy(alpha = 0.8f))
            ) {
                Icon(imageVector = Icons.Default.AspectRatio, contentDescription = "Maximize", tint = theme.colors.accent)
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
    val theme = LocalGlaiveTheme.current

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(64.dp)
            .clip(RoundedCornerShape(theme.shapes.cornerRadius))
            .background(if (isSelected) theme.colors.accent.copy(alpha = 0.15f) else theme.colors.surface)
            .border(
                width = if (isSelected) theme.shapes.borderWidth else 0.dp,
                color = if (isSelected) theme.colors.accent else Color.Transparent,
                shape = RoundedCornerShape(theme.shapes.cornerRadius)
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
                Icon(imageVector = Icons.Default.Check, contentDescription = null, tint = theme.colors.accent)
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
                    color = if (isSelected) theme.colors.accent else theme.colors.text,
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
    val theme = LocalGlaiveTheme.current
    val quickTargets = remember {
        listOf(
            "/" to "/storage/emulated/0",
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
                    style = TextStyle(color = theme.colors.text, fontWeight = FontWeight.Bold, fontSize = 20.sp),
                    modifier = Modifier.weight(1f)
                )
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(theme.colors.surface)
                ) {
                    Icon(imageVector = Icons.Default.Close, contentDescription = "Close", tint = theme.colors.text)
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
                        accent = theme.colors.accent,
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
                        accent = theme.colors.accent,
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
                    accent = theme.colors.accent,
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
                    style = TextStyle(color = theme.colors.accent, fontWeight = FontWeight.Medium, fontSize = 14.sp)
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
                    style = TextStyle(color = theme.colors.accent, fontWeight = FontWeight.Medium, fontSize = 14.sp)
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
    val theme = LocalGlaiveTheme.current
    val label = path?.trimEnd('/')?.substringAfterLast("/")?.ifEmpty { "Root" } ?: "—"
    val clickModifier = if (path != null && onClick != null) Modifier.clickable(onClick = onClick) else Modifier
    val borderColor = if (selected) accent else accent.copy(alpha = 0.4f)
    val backgroundColor = if (selected) theme.colors.background.copy(alpha = 0.9f) else theme.colors.background.copy(alpha = 0.7f)
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(theme.shapes.cornerRadius))
            .background(backgroundColor)
            .border(theme.shapes.borderWidth, borderColor, RoundedCornerShape(theme.shapes.cornerRadius))
            .then(clickModifier)
            .padding(12.dp)
    ) {
        Text(title.uppercase(Locale.ROOT), color = accent, fontSize = 11.sp, fontWeight = FontWeight.Medium)
        Spacer(modifier = Modifier.height(4.dp))
        Text(label, color = theme.colors.text, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun HistoryChip(path: String, label: String? = null, onClick: () -> Unit) {
    val theme = LocalGlaiveTheme.current
    val resolvedLabel = label ?: path.trimEnd('/').substringAfterLast("/").ifEmpty { "Root" }
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(theme.colors.background.copy(alpha = 0.8f))
            .border(1.dp, theme.colors.surface, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Text(resolvedLabel, color = theme.colors.text, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun ReverseGestureHotZone(
    modifier: Modifier = Modifier,
    hasTrail: Boolean,
    onNavigateUp: () -> Unit
) {
    val theme = LocalGlaiveTheme.current
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
                        theme.colors.background.copy(alpha = 0.6f),
                        Color.Transparent
                    )
                )
            )
    ) {
        if (hasTrail) {
            Icon(
                imageVector =Icons.Default.KeyboardArrowLeft,
                contentDescription = "Reverse",
                tint = theme.colors.accent,
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
    onMoreSelection: () -> Unit,
    clipboardActive: Boolean,
    onPaste: () -> Unit
) {
    val theme = LocalGlaiveTheme.current
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(Color.Black.copy(alpha = 0.8f))
            .border(1.dp, theme.colors.surface, RoundedCornerShape(50))
            .padding(horizontal = 8.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        if (selectionActive) {
            IconButton(onClick = onClearSelection) { Icon(imageVector =Icons.Default.Close, contentDescription = "Clear", tint = theme.colors.error) }
            IconButton(onClick = onShareSelection) { Icon(imageVector =Icons.Default.Share, contentDescription = "Share", tint = theme.colors.accent) }
            IconButton(onClick = onMoreSelection) { Icon(imageVector = Icons.Default.MoreVert, contentDescription = "More", tint = theme.colors.text) }
        } else if (clipboardActive) {
            IconButton(onClick = onPaste) {
                Icon(imageVector = Icons.Default.ContentPaste, contentDescription = "Paste", tint = theme.colors.accent)
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
    val theme = LocalGlaiveTheme.current
    val haptic = LocalHapticFeedback.current
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(theme.shapes.cornerRadius))
            .background(if (isSelected) theme.colors.accent.copy(alpha = 0.15f) else theme.colors.surface)
            .border(if (isSelected) theme.shapes.borderWidth else 0.dp, if (isSelected) theme.colors.accent else Color.Transparent, RoundedCornerShape(theme.shapes.cornerRadius))
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
                Icon(imageVector =Icons.Default.Check, null, tint = theme.colors.accent)
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
        Text(item.name, color = theme.colors.text, maxLines = 2, overflow = TextOverflow.Ellipsis, fontSize = 11.sp, textAlign = TextAlign.Center)
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
    onZip: () -> Unit,
    onUnzip: () -> Unit,
    onFavorite: (Boolean) -> Unit,
    isFavorite: Boolean,
    onOpenFileLocation: () -> Unit
) {
    val theme = LocalGlaiveTheme.current
    val context = LocalContext.current
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = theme.colors.background) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(item.name, style = MaterialTheme.typography.titleLarge, color = theme.colors.text)
            Spacer(modifier = Modifier.height(16.dp))
            ContextMenuItem("Open File Location", Icons.Default.ArrowForward, onOpenFileLocation)
            ContextMenuItem(if (isFavorite) "Remove from Favorites" else "Add to Favorites", Icons.Default.Check, { onFavorite(!isFavorite) })
            ContextMenuItem("Select", Icons.Default.Check, onSelect)
            ContextMenuItem("Copy", Icons.Default.Share, onCopy)
            ContextMenuItem("Cut", Icons.Default.Edit, onCut)
            ContextMenuItem("Zip", Icons.Default.Archive, onZip)
            if (item.path.endsWith(".zip")) {
                 ContextMenuItem("Unzip Here", Icons.Default.Archive, onUnzip)
            }
            ContextMenuItem("Copy Path", Icons.Default.ContentPaste, {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                val clip = android.content.ClipData.newPlainText("Path", item.path)
                clipboard.setPrimaryClip(clip)
                onDismiss() // Dismiss menu after copy
            })
            ContextMenuItem("Delete", Icons.Default.Delete, onDelete, theme.colors.error)
            if (item.type != GlaiveItem.TYPE_DIR) {
                ContextMenuItem("Edit", Icons.Default.Edit, onEdit)
                ContextMenuItem("Share", Icons.Default.Share, onShare)
            }
        }
    }
}

@Composable
fun ContextMenuItem(text: String, icon: ImageVector, onClick: () -> Unit, color: Color? = null) {
    val theme = LocalGlaiveTheme.current
    val resolvedColor = color ?: theme.colors.text
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(imageVector =icon, null, tint = resolvedColor)
        Spacer(modifier = Modifier.width(16.dp))
        Text(text, color = resolvedColor, fontSize = 16.sp)
    }
}

@Composable
fun CreateDialog(onDismiss: () -> Unit, onCreate: (String, Boolean) -> Unit) {
    val theme = LocalGlaiveTheme.current
    var name by remember { mutableStateOf("") }
    var isDir by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = theme.colors.surface,
        title = { Text("Create New", color = theme.colors.text) },
        text = {
            Column {
                BasicTextField(
                    value = name,
                    onValueChange = { name = it },
                    textStyle = TextStyle(color = theme.colors.text, fontSize = 16.sp),
                    modifier = Modifier.fillMaxWidth().background(theme.colors.background, RoundedCornerShape(8.dp)).padding(12.dp)
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = isDir, onCheckedChange = { isDir = it })
                    Text("Directory", color = theme.colors.text)
                }
            }
        },
        confirmButton = {
            Button(onClick = { onCreate(name, isDir) }, colors = ButtonDefaults.buttonColors(containerColor = theme.colors.accent)) {
                Text("Create")
            }
        }
    )
}

@Composable
fun TextEditor(file: File, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    val theme = LocalGlaiveTheme.current
    var content by remember { mutableStateOf(file.readText()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = theme.colors.background,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
        title = { Text(file.name, color = theme.colors.text) },
        text = {
            BasicTextField(
                value = content,
                onValueChange = { content = it },
                textStyle = TextStyle(color = theme.colors.text, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace),
                modifier = Modifier.fillMaxSize().background(theme.colors.surface, RoundedCornerShape(8.dp)).padding(8.dp)
            )
        },
        confirmButton = {
            Button(onClick = { onSave(content) }, colors = ButtonDefaults.buttonColors(containerColor = theme.colors.accent)) {
                Text("Save")
            }
        },
        dismissButton = {
            Button(onClick = onDismiss, colors = ButtonDefaults.buttonColors(containerColor = theme.colors.error)) {
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
    val theme = LocalGlaiveTheme.current
    val isSelected = activeFilters.contains(type)
    val backgroundColor = if (isSelected) theme.colors.accent else theme.colors.surface
    val textColor = if (isSelected) theme.colors.background else theme.colors.text
    
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
    val theme = LocalGlaiveTheme.current
    val isSelected = mode == current
    val background = if (isSelected) theme.colors.accent else Color.Transparent
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MultiSelectionMenuSheet(
    count: Int,
    onDismiss: () -> Unit,
    onCopy: () -> Unit,
    onCut: () -> Unit,
    onDelete: () -> Unit,
    onZip: () -> Unit,
    onCopyPath: () -> Unit
) {
    val theme = LocalGlaiveTheme.current
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = theme.colors.background) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("$count items selected", style = MaterialTheme.typography.titleLarge, color = theme.colors.text)
            Spacer(modifier = Modifier.height(16.dp))
            ContextMenuItem("Copy", Icons.Default.Share, onCopy)
            ContextMenuItem("Cut", Icons.Default.Edit, onCut)
            ContextMenuItem("Zip", Icons.Default.Archive, onZip)
            ContextMenuItem("Copy Path", Icons.Default.ContentPaste, onCopyPath)
            ContextMenuItem("Delete", Icons.Default.Delete, onDelete, theme.colors.error)
        }
    }
}

// --- UTILS ---

@Composable
fun getTypeColor(type: Int): Color {
    val theme = LocalGlaiveTheme.current
    return when (type) {
        GlaiveItem.TYPE_DIR -> theme.colors.accent
        GlaiveItem.TYPE_IMG -> Color(0xFF2979FF)
        GlaiveItem.TYPE_VID -> theme.colors.error
        GlaiveItem.TYPE_APK -> Color(0xFFB2FF59)
        else -> Color.Gray
    }
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
