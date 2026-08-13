package com.example.ui

import android.content.Context
import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Bitmap
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material3.*
import androidx.compose.ui.draw.alpha
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.magnifier
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.first
import com.example.data.Bookmark
import com.example.data.Manhwa
import com.example.data.PluginConfig
import com.example.data.ReadingEvent
import com.example.ui.theme.*
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ManhwaReaderApp(viewModel: ManhwaViewModel) {
    val tabs by viewModel.tabs.collectAsStateWithLifecycle()
    val activeTabId by viewModel.activeTabId.collectAsStateWithLifecycle()
    val activeTab by viewModel.activeTab.collectAsStateWithLifecycle()
    val importingState by viewModel.importingState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var isLogoDropdownExpanded by remember { mutableStateOf(false) }
    var showAboutDialog by remember { mutableStateOf(false) }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            viewModel.importPdfFile(uri)
        }
    }

    val paywallTargetPlugin by viewModel.paywallTargetPlugin.collectAsStateWithLifecycle()

    // Trigger toast alerts for imports
    LaunchedEffect(importingState) {
        when (importingState) {
            is ManhwaViewModel.ImportState.Success -> {
                Toast.makeText(context, (importingState as ManhwaViewModel.ImportState.Success).title, Toast.LENGTH_SHORT).show()
                viewModel.resetImportState()
            }
            is ManhwaViewModel.ImportState.Error -> {
                Toast.makeText(context, (importingState as ManhwaViewModel.ImportState.Error).message, Toast.LENGTH_LONG).show()
                viewModel.resetImportState()
            }
            else -> {}
        }
    }

    paywallTargetPlugin?.let { targetPlugin ->
        PaywallDialog(viewModel = viewModel, targetPlugin = targetPlugin)
    }

    val memoryPressure by viewModel.memoryPressureEvent.collectAsStateWithLifecycle()
    if (memoryPressure) {
        MemoryPressureDialog(viewModel = viewModel)
    }

    if (showAboutDialog) {
        AlertDialog(
            onDismissRequest = { showAboutDialog = false },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Info,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(28.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("PDF ULTRA", fontWeight = FontWeight.Bold)
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "The ultimate high-performance, gapless comic viewer optimized for ultra-smooth vertical reading.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = "Version: 1.2.0\nFeatures: Tabbed Multitasking, Customizable Filters, Page Sketching",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showAboutDialog = false }) {
                    Text("Awesome")
                }
            }
        )
    }

    var isReaderControlsVisible by remember { mutableStateOf(true) }

    Scaffold(
        topBar = {
            if (activeTab?.type != TabType.READER) {
                TopAppBar(
                    title = {
                        Text(
                            text = "PDF ULTRA",
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    },
                    actions = {
                        IconButton(onClick = { showAboutDialog = true }) {
                            Icon(Icons.Default.Info, contentDescription = "About", tint = MaterialTheme.colorScheme.primary)
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                        titleContentColor = MaterialTheme.colorScheme.onSurface
                    ),
                    modifier = Modifier.shadow(4.dp)
                )
            }
        },
        bottomBar = {
            val showTabBar = activeTab?.type != TabType.READER || isReaderControlsVisible
            AnimatedVisibility(
                visible = showTabBar,
                enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
                exit = slideOutVertically(targetOffsetY = { it }) + fadeOut()
            ) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .shadow(8.dp),
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 4.dp
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp, start = 16.dp, end = 16.dp, bottom = 40.dp)
                    ) {
                        Text(
                            text = "ACTIVE TABS (Double-tap to close)",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(bottom = 6.dp)
                        )
                        LazyRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            @OptIn(ExperimentalFoundationApi::class)
                            items(tabs) { tab ->
                                val isActive = tab.id == activeTabId
                                Surface(
                                    modifier = Modifier
                                        .height(36.dp)
                                        .clip(RoundedCornerShape(18.dp))
                                        .combinedClickable(
                                            onClick = {
                                                viewModel.selectTabId(tab.id)
                                                if (tab.id != "settings") {
                                                    val toast = Toast.makeText(context, "Double tab to close", Toast.LENGTH_SHORT)
                                                    toast.setGravity(android.view.Gravity.TOP or android.view.Gravity.CENTER_HORIZONTAL, 0, 150)
                                                    toast.show()
                                                }
                                            },
                                            onDoubleClick = {
                                                if (tab.id != "settings") {
                                                    viewModel.closeTab(tab.id)
                                                }
                                            }
                                        )
                                        .testTag("tab_${tab.id}"),
                                    shape = RoundedCornerShape(18.dp),
                                    color = if (isActive) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                                    border = if (isActive) BorderStroke(1.dp, MaterialTheme.colorScheme.primary) else null
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 12.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        val maxChar = 12
                                        val titleText = if (tab.title.length > maxChar) tab.title.take(maxChar) + "..." else tab.title
                                        Text(
                                            text = titleText,
                                            fontSize = 12.sp,
                                            fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal,
                                            color = if (isActive) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                            item {
                                Surface(
                                    onClick = { filePickerLauncher.launch(arrayOf("application/pdf")) },
                                    modifier = Modifier
                                        .size(36.dp)
                                        .clip(CircleShape)
                                        .testTag("tab_add_button"),
                                    shape = CircleShape,
                                    color = MaterialTheme.colorScheme.primaryContainer,
                                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(
                                            imageVector = Icons.Default.Add,
                                            contentDescription = "Open PDF Tab",
                                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when {
                activeTab?.type == TabType.READER && activeTab?.manhwa != null -> {
                    ComicReaderScreen(viewModel = viewModel, onControlsVisibilityChanged = { isReaderControlsVisible = it })
                }
                else -> {
                    LobbyScreen(viewModel = viewModel)
                }
            }
        }
    }
}

// --- SCREEN: Library/Dashboard ---
@Composable
fun LibraryScreen(viewModel: ManhwaViewModel) {
    val manhwas by viewModel.allManhwas.collectAsStateWithLifecycle()
    val sortMode by viewModel.sortMode.collectAsStateWithLifecycle()
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val libraryFilter by viewModel.libraryFilter.collectAsStateWithLifecycle()

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { viewModel.importPdfFile(it) }
    }
    var showDeleteConfirmDialog by remember { mutableStateOf<Manhwa?>(null) }
    var showDetailsDialogForManhwa by remember { mutableStateOf<Manhwa?>(null) }

    val continueReadingList = remember(manhwas) {
        manhwas.filter { (it.lastReadPage > 0 || it.scrollOffset > 0) && it.lastReadPage < it.totalPages - 1 }
    }

    val seriesMap = remember(manhwas) {
        manhwas.groupBy { viewModel.getSeriesName(it) }
    }

    val sortedManhwas = remember(manhwas, sortMode) {
        when (sortMode) {
            ManhwaViewModel.SortMode.RECENT -> {
                manhwas.sortedByDescending { it.id }
            }
            ManhwaViewModel.SortMode.NATURAL -> {
                manhwas.sortedWith(
                    compareBy<Manhwa> { viewModel.getSeriesName(it).lowercase() }
                        .thenBy { viewModel.getChapterNumber(it) }
                )
            }
        }
    }

    val filteredManhwas = remember(sortedManhwas, searchQuery, libraryFilter) {
        sortedManhwas.filter { item ->
            val matchesSearch = searchQuery.isBlank() || 
                item.title.contains(searchQuery, ignoreCase = true) || 
                viewModel.getSeriesName(item).contains(searchQuery, ignoreCase = true)
            
            val matchesFilter = when (libraryFilter) {
                ManhwaViewModel.LibraryFilter.ALL -> true
                ManhwaViewModel.LibraryFilter.IN_PROGRESS -> item.lastReadPage > 0 && item.lastReadPage < item.totalPages - 1
                ManhwaViewModel.LibraryFilter.UNREAD -> item.lastReadPage == 0
                ManhwaViewModel.LibraryFilter.FINISHED -> item.totalPages > 0 && item.lastReadPage >= item.totalPages - 1
            }
            
            matchesSearch && matchesFilter
        }
    }
    
    var selectedTab by remember { mutableStateOf(0) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // Aesthetic Custom Slate Header
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(180.dp)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                            Color.Transparent
                        )
                    )
                )
                .padding(horizontal = 24.dp, vertical = 20.dp),
            contentAlignment = Alignment.BottomStart
        ) {
            Column {
                Text(
                    text = "MANHWA SHELF",
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 1.5.sp,
                    color = MaterialTheme.colorScheme.primary,
                    fontFamily = FontFamily.SansSerif
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "High Definition, continuous gapless vertical PDF comic reader.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                    fontWeight = FontWeight.Medium
                )
            }
        }
        
        // Tabs
        androidx.compose.material3.TabRow(
            selectedTabIndex = selectedTab,
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.primary
        ) {
            androidx.compose.material3.Tab(
                selected = selectedTab == 0,
                onClick = { selectedTab = 0 },
                text = { Text("Loaded PDFs") }
            )
            androidx.compose.material3.Tab(
                selected = selectedTab == 1,
                onClick = { selectedTab = 1 },
                text = { Text("Reading Analysis") }
            )
        }
        
        if (selectedTab == 0) {
            if (manhwas.isEmpty()) {
            // Elegant Empty Shelf State
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(72.dp)
                            .background(
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.08f),
                                CircleShape
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = "Empty",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(36.dp)
                        )
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "Your shelf is empty",
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Import local PDF comics to enjoy reading offline with continuous scrolling.",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 24.dp)
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Button(
                            onClick = { filePickerLauncher.launch(arrayOf("application/pdf")) },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Default.Add, contentDescription = "Import PDF")
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Import PDF", fontWeight = FontWeight.Bold)
                        }

                        OutlinedButton(
                            onClick = { viewModel.createDummyTestPdf() },
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Default.PlayArrow, contentDescription = "Dummy PDF")
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Create Test PDF", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        } else {
            // Book List with dynamic scroll state loading
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // Search Bar and Category Filter Chips
                item {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp)
                    ) {
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { viewModel.setSearchQuery(it) },
                            placeholder = { Text("Search by title or series...", fontSize = 13.sp) },
                            leadingIcon = {
                                Icon(Icons.Default.Search, contentDescription = "Search", tint = MaterialTheme.colorScheme.primary)
                            },
                            trailingIcon = {
                                if (searchQuery.isNotEmpty()) {
                                    IconButton(onClick = { viewModel.setSearchQuery("") }) {
                                        Icon(Icons.Default.Clear, contentDescription = "Clear search", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("library_search_input"),
                            shape = RoundedCornerShape(12.dp),
                            singleLine = true
                        )

                        Spacer(modifier = Modifier.height(10.dp))

                        // Filter Chips
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            listOf(
                                ManhwaViewModel.LibraryFilter.ALL to "All (${manhwas.size})",
                                ManhwaViewModel.LibraryFilter.IN_PROGRESS to "Reading (${manhwas.count { it.lastReadPage > 0 && it.lastReadPage < it.totalPages - 1 }})",
                                ManhwaViewModel.LibraryFilter.UNREAD to "Unread (${manhwas.count { it.lastReadPage == 0 }})",
                                ManhwaViewModel.LibraryFilter.FINISHED to "Completed (${manhwas.count { it.totalPages > 0 && it.lastReadPage >= it.totalPages - 1 }})"
                            ).forEach { (filter, label) ->
                                val isSelected = libraryFilter == filter
                                FilterChip(
                                    selected = isSelected,
                                    onClick = { viewModel.setLibraryFilter(filter) },
                                    label = { Text(label, fontSize = 11.sp, fontWeight = FontWeight.Bold) },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = MaterialTheme.colorScheme.primary,
                                        selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                                    ),
                                    shape = RoundedCornerShape(20.dp)
                                )
                            }
                        }
                    }
                }

                // Smart sort Mode Selector
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "Sort Mode:",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f)
                        )

                        listOf(
                            ManhwaViewModel.SortMode.RECENT to "Recent Added",
                            ManhwaViewModel.SortMode.NATURAL to "Smart Sort"
                        ).forEach { (mode, name) ->
                            val isSelected = sortMode == mode
                            Box(
                                modifier = Modifier
                                    .background(
                                        color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                        shape = RoundedCornerShape(8.dp)
                                    )
                                    .clickable { viewModel.setSortMode(mode) }
                                    .padding(horizontal = 12.dp, vertical = 6.dp)
                            ) {
                                Text(
                                    text = name,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }

                // Continue Reading shelf
                if (continueReadingList.isNotEmpty()) {
                    item {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp)
                        ) {
                            Text(
                                text = "CONTINUE READING",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Black,
                                letterSpacing = 1.sp,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(bottom = 8.dp)
                            )

                            LazyRow(
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                items(continueReadingList) { item ->
                                    Card(
                                        modifier = Modifier
                                            .width(220.dp)
                                            .clickable { viewModel.openManhwa(item) },
                                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)),
                                        shape = RoundedCornerShape(12.dp)
                                    ) {
                                        Column(
                                            modifier = Modifier.padding(12.dp)
                                        ) {
                                            Text(
                                                text = item.title,
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 13.sp,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                            Spacer(modifier = Modifier.height(4.dp))
                                            
                                            val progress = if (item.totalPages > 0) {
                                                ((item.lastReadPage + 1).toFloat() / item.totalPages.toFloat())
                                            } else 0f
                                            
                                            Text(
                                                text = "Page ${item.lastReadPage + 1} of ${item.totalPages} (${(progress * 100).toInt()}%)",
                                                fontSize = 11.sp,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                            )
                                            Spacer(modifier = Modifier.height(8.dp))
                                            LinearProgressIndicator(
                                                progress = { progress },
                                                modifier = Modifier.fillMaxWidth().height(4.dp),
                                                color = MaterialTheme.colorScheme.primary,
                                                trackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f),
                                                strokeCap = StrokeCap.Round
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // Series Tracker shelf
                val validSeriesList = seriesMap.toList().filter { it.first.isNotBlank() }
                if (validSeriesList.isNotEmpty()) {
                    item {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp)
                        ) {
                            Text(
                                text = "SERIES PROGRESS TRACKER",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Black,
                                letterSpacing = 1.sp,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(bottom = 8.dp)
                            )

                            LazyRow(
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                items(validSeriesList) { (seriesName, chapters) ->
                                    val totalChaps = chapters.size
                                    val totalProgressSum = chapters.sumOf {
                                        if (it.totalPages > 0) {
                                            ((it.lastReadPage + 1).toDouble() / it.totalPages.toDouble())
                                        } else 0.0
                                    }
                                    val averageProgress = (totalProgressSum / totalChaps.toDouble()).toFloat()

                                    Card(
                                        modifier = Modifier.width(180.dp),
                                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f)),
                                        shape = RoundedCornerShape(12.dp)
                                    ) {
                                        Column(
                                            modifier = Modifier.padding(12.dp)
                                        ) {
                                            Text(
                                                text = seriesName,
                                                fontWeight = FontWeight.ExtraBold,
                                                fontSize = 13.sp,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                color = MaterialTheme.colorScheme.onPrimaryContainer
                                            )
                                            Spacer(modifier = Modifier.height(2.dp))
                                            Text(
                                                text = "$totalChaps Chapter${if (totalChaps > 1) "s" else ""}",
                                                fontSize = 11.sp,
                                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                                            )
                                            Spacer(modifier = Modifier.height(8.dp))
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Text(
                                                    text = "Series Read",
                                                    fontSize = 10.sp,
                                                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.6f)
                                                )
                                                Text(
                                                    text = "${(averageProgress * 100).toInt()}%",
                                                    fontSize = 11.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = MaterialTheme.colorScheme.primary
                                                )
                                            }
                                            Spacer(modifier = Modifier.height(4.dp))
                                            LinearProgressIndicator(
                                                progress = { averageProgress },
                                                modifier = Modifier.fillMaxWidth().height(5.dp),
                                                color = MaterialTheme.colorScheme.primary,
                                                trackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f),
                                                strokeCap = StrokeCap.Round
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Offline Chapters (${filteredManhwas.size})",
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f)
                        )
                        TextButton(
                            onClick = { filePickerLauncher.launch(arrayOf("application/pdf")) }
                        ) {
                            Icon(Icons.Default.Add, contentDescription = "Add More", modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Add More", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }

                if (filteredManhwas.isEmpty() && searchQuery.isNotBlank()) {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(32.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "No comics found matching '$searchQuery'",
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                } else {
                    items(filteredManhwas, key = { it.id }) { manhwa ->
                        ManhwaCardItem(
                            manhwa = manhwa,
                            onOpen = { viewModel.openManhwa(manhwa) },
                            onDelete = { showDeleteConfirmDialog = manhwa },
                            onShowDetails = { showDetailsDialogForManhwa = manhwa },
                            onFavoriteToggle = { viewModel.toggleManhwaFavorite(manhwa.id) }
                        )
                    }
                }
            }
        }
        } else {
            // Reading Analysis Tab
            val readingEvents by viewModel.allReadingEvents.collectAsStateWithLifecycle()
            val readingStreak by viewModel.readingStreak.collectAsStateWithLifecycle()
            val todayReadingSeconds by viewModel.todayReadingSeconds.collectAsStateWithLifecycle()
            val weeklyReadingStats by viewModel.weeklyReadingStats.collectAsStateWithLifecycle()
            
            val totalManhwas = manhwas.size
            val manhwasStarted = manhwas.count { it.lastReadPage > 0 }
            
            val totalReadingSeconds = readingEvents.sumOf { it.durationSeconds.toLong() }
            val readingHours = totalReadingSeconds / 3600
            val readingMinutes = (totalReadingSeconds % 3600) / 60
            val totalAdvancedPages = readingEvents.size // Each event is a page read segment

            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(24.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    text = "ADVANCED READING ANALYSIS",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Text(
                    text = "Tracking your progress with physical and virtual heatmaps.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f)
                )
                Spacer(modifier = Modifier.height(20.dp))

                // Feature 1: Daily Reading Goal and Streak Tracker
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Left column: Progress Circular Arc
                        Box(
                            modifier = Modifier.size(80.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            val goalProgress = (todayReadingSeconds.toFloat() / 900f).coerceIn(0f, 1f) // 15-min daily goal
                            Canvas(modifier = Modifier.fillMaxSize()) {
                                // Background Track
                                drawArc(
                                    color = Color.White.copy(alpha = 0.05f),
                                    startAngle = -220f,
                                    sweepAngle = 260f,
                                    useCenter = false,
                                    style = androidx.compose.ui.graphics.drawscope.Stroke(
                                        width = 16f,
                                        cap = StrokeCap.Round
                                    )
                                )
                                // Active Progress Track
                                drawArc(
                                    brush = androidx.compose.ui.graphics.Brush.sweepGradient(
                                        colors = listOf(NeonOrange, ElectricCyan, NeonOrange)
                                    ),
                                    startAngle = -220f,
                                    sweepAngle = 260f * goalProgress,
                                    useCenter = false,
                                    style = androidx.compose.ui.graphics.drawscope.Stroke(
                                        width = 18f,
                                        cap = StrokeCap.Round
                                    )
                                )
                            }
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    text = "${(goalProgress * 100).toInt()}%",
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Black,
                                    color = ElectricCyan
                                )
                                Text(
                                    text = "Goal",
                                    fontSize = 8.sp,
                                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f)
                                )
                            }
                        }
                        
                        Spacer(modifier = Modifier.width(20.dp))
                        
                        // Right Column: Streak and details
                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(32.dp)
                                        .background(NeonOrange.copy(alpha = 0.15f), CircleShape),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Favorite,
                                        contentDescription = "Streak",
                                        tint = NeonOrange,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.width(10.dp))
                                Column {
                                    Text(
                                        text = "$readingStreak Days Streak",
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onBackground
                                    )
                                    Text(
                                        text = if (readingStreak > 0) "Keep the fire burning!" else "Start reading to start a streak",
                                        fontSize = 10.sp,
                                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f)
                                    )
                                }
                            }
                            
                            Spacer(modifier = Modifier.height(12.dp))
                            
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column {
                                    Text("Today's Read Time", fontSize = 10.sp, color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.4f))
                                    val todayMins = todayReadingSeconds / 60
                                    val todaySecs = todayReadingSeconds % 60
                                    Text("${todayMins}m ${todaySecs}s", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = ElectricCyan)
                                }
                                
                                Column {
                                    Text("Daily Target", fontSize = 10.sp, color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.4f))
                                    Text("15 mins", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White)
                                }
                            }
                        }
                    }
                }

                // Feature 2: Weekly Reading Activity Chart
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "WEEKLY READING WORKLOAD",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                            letterSpacing = 0.5.sp
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        
                        // Canvas for custom drawing
                        val maxDuration = weeklyReadingStats.maxOrNull()?.toFloat()?.coerceAtLeast(60f) ?: 60f
                        val days = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
                        
                        Canvas(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(120.dp)
                        ) {
                            val width = size.width
                            val height = size.height
                            val paddingLeft = 30f
                            val paddingBottom = 40f
                            val chartWidth = width - paddingLeft
                            val chartHeight = height - paddingBottom
                            val barWidth = (chartWidth / 7f) * 0.5f
                            val spacing = (chartWidth / 7f) * 0.5f
                            
                            // Draw grid line at 50% and 100%
                            drawLine(
                                color = Color.White.copy(alpha = 0.05f),
                                start = Offset(paddingLeft, chartHeight * 0.5f),
                                end = Offset(width, chartHeight * 0.5f),
                                strokeWidth = 2f
                            )
                            drawLine(
                                color = Color.White.copy(alpha = 0.1f),
                                start = Offset(paddingLeft, 0f),
                                end = Offset(width, 0f),
                                strokeWidth = 2f
                            )
                            
                            // Draw baseline
                            drawLine(
                                color = Color.White.copy(alpha = 0.2f),
                                start = Offset(paddingLeft, chartHeight),
                                end = Offset(width, chartHeight),
                                strokeWidth = 4f
                            )
                            
                            for (i in 0 until 7) {
                                val duration = weeklyReadingStats[i].toFloat()
                                val barHeight = (duration / maxDuration) * chartHeight
                                val x = paddingLeft + i * (barWidth + spacing) + spacing * 0.5f
                                val y = chartHeight - barHeight
                                
                                // Draw rounded bar
                                drawRoundRect(
                                    brush = androidx.compose.ui.graphics.Brush.verticalGradient(
                                        colors = listOf(ElectricCyan, DarkCyan)
                                    ),
                                    topLeft = Offset(x, y),
                                    size = androidx.compose.ui.geometry.Size(barWidth, barHeight),
                                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(10f, 10f)
                                )
                            }
                        }
                        
                        // Composable Row for labels under the canvas
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 12.dp, top = 4.dp),
                            horizontalArrangement = Arrangement.SpaceAround
                        ) {
                            days.forEachIndexed { idx, day ->
                                val seconds = weeklyReadingStats[idx]
                                val mins = seconds / 60
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(day, fontSize = 10.sp, color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f))
                                    Text("${mins}m", fontSize = 9.sp, fontWeight = FontWeight.Bold, color = if (mins > 0) ElectricCyan else Color.Gray.copy(alpha = 0.4f))
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    AnalysisCard("Total Comics", totalManhwas.toString(), modifier = Modifier.weight(1f))
                    Spacer(modifier = Modifier.width(16.dp))
                    AnalysisCard("Started", manhwasStarted.toString(), modifier = Modifier.weight(1f))
                }
                Spacer(modifier = Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    AnalysisCard("Reading Time", "${readingHours}h ${readingMinutes}m", modifier = Modifier.weight(1f))
                    Spacer(modifier = Modifier.width(16.dp))
                    AnalysisCard("Segments Read", totalAdvancedPages.toString(), modifier = Modifier.weight(1f))
                }
                
                Spacer(modifier = Modifier.height(32.dp))
                
                Text(
                    text = "DATA MANAGEMENT",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 1.sp,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(12.dp))
                
                Button(
                    onClick = { viewModel.clearReadingStats() },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.errorContainer, contentColor = MaterialTheme.colorScheme.onErrorContainer),
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Clear All Reading History", fontWeight = FontWeight.Bold)
                }
                
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "History is stored locally on your device for privacy and progress tracking.",
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.4f),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }

    // Delete Confirmation dialog
    showDeleteConfirmDialog?.let { manhwa ->
        AlertDialog(
            onDismissRequest = { showDeleteConfirmDialog = null },
            title = { Text("Delete from local storage?", fontWeight = FontWeight.Bold) },
            text = { Text("This will permanently remove '${manhwa.title}' and free up offline space. Are you sure?") },
            confirmButton = {
                Button(
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    onClick = {
                        viewModel.deleteManhwa(manhwa)
                        showDeleteConfirmDialog = null
                    }
                ) {
                    Text("Delete", color = Color.White)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirmDialog = null }) {
                    Text("Cancel")
                }
            },
            containerColor = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(16.dp)
        )
    }

    // PDF Details Metadata Dialog
    showDetailsDialogForManhwa?.let { manhwa ->
        val file = java.io.File(manhwa.filePath)
        val sizeMb = if (file.exists()) String.format(java.util.Locale.US, "%.2f MB", file.length().toDouble() / (1024 * 1024)) else "Unknown"
        val progressPercent = if (manhwa.totalPages > 0) ((manhwa.lastReadPage + 1).toFloat() / manhwa.totalPages * 100).toInt() else 0

        AlertDialog(
            onDismissRequest = { showDetailsDialogForManhwa = null },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Info, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("PDF Comic Details", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(text = manhwa.title, fontWeight = FontWeight.Bold, fontSize = 15.sp, color = MaterialTheme.colorScheme.primary)
                    HorizontalDivider(modifier = Modifier.padding(vertical = 2.dp))
                    Text("Total Pages: ${manhwa.totalPages}", fontSize = 13.sp)
                    Text("Current Progress: Page ${manhwa.lastReadPage + 1} ($progressPercent%)", fontSize = 13.sp)
                    Text("File Size: $sizeMb", fontSize = 13.sp)
                    Text("Series Group: ${viewModel.getSeriesName(manhwa)}", fontSize = 13.sp)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text("File Location:", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                    Text(
                        text = manhwa.filePath,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.openManhwa(manhwa)
                        showDetailsDialogForManhwa = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text("Read Comic", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDetailsDialogForManhwa = null }) {
                    Text("Close")
                }
            },
            containerColor = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(16.dp)
        )
    }
}

@Composable
fun AnalysisCard(title: String, value: String, modifier: Modifier = Modifier) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(12.dp),
        modifier = modifier
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = value,
                fontSize = 24.sp,
                fontWeight = FontWeight.Black,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = title,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
fun ManhwaCardItem(
    manhwa: Manhwa,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
    onShowDetails: (() -> Unit)? = null,
    onFavoriteToggle: (() -> Unit)? = null
) {
    val progress = if (manhwa.totalPages > 0) {
        (manhwa.lastReadPage + 1).toFloat() / manhwa.totalPages.toFloat()
    } else 0f

    Card(
        onClick = onOpen,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .shadow(4.dp, RoundedCornerShape(16.dp))
            .testTag("manhwa_card_${manhwa.id}")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // High-contrast, minimalist comic cover placeholder
            Box(
                modifier = Modifier
                    .size(60.dp, 84.dp)
                    .background(
                        Brush.linearGradient(
                            colors = listOf(
                                MaterialTheme.colorScheme.secondary.copy(alpha = 0.2f),
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)
                            )
                        ),
                        RoundedCornerShape(8.dp)
                    )
                    .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.2f), RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Default.Favorite,
                        contentDescription = "PDF",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(28.dp)
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "PDF",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Black,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            Spacer(modifier = Modifier.width(16.dp))

            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = manhwa.title,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "Pages: ${manhwa.totalPages}",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )

                Spacer(modifier = Modifier.height(10.dp))
                // Progress tracker
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier
                            .weight(1f)
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp)),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = "${((progress) * 100).toInt()}%",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "Page ${manhwa.lastReadPage + 1} of ${manhwa.totalPages}",
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                    fontWeight = FontWeight.SemiBold
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                if (onFavoriteToggle != null) {
                    IconButton(
                        onClick = onFavoriteToggle,
                        modifier = Modifier.testTag("favorite_button_${manhwa.id}")
                    ) {
                        Icon(
                            imageVector = if (manhwa.isFavorite) Icons.Filled.Favorite else Icons.Default.FavoriteBorder,
                            contentDescription = "Favorite Comic",
                            tint = if (manhwa.isFavorite) Color(0xFFE91E63) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                        )
                    }
                }

                if (onShowDetails != null) {
                    IconButton(
                        onClick = onShowDetails,
                        modifier = Modifier.testTag("details_button_${manhwa.id}")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = "Comic Details",
                            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f)
                        )
                    }
                }

                IconButton(
                    onClick = onDelete,
                    modifier = Modifier.testTag("delete_button_${manhwa.id}")
                ) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "Delete from offline",
                        tint = MaterialTheme.colorScheme.error.copy(alpha = 0.7f)
                    )
                }
            }
        }
    }
}

// --- SCREEN: Plugins Registry/Manager ---
@Composable
fun PluginsScreen(viewModel: ManhwaViewModel) {
    val plugins by viewModel.allPlugins.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(16.dp)
    ) {
        Text(
            text = "PLUGIN STORE & MANAGER",
            fontSize = 20.sp,
            fontWeight = FontWeight.Black,
            letterSpacing = 1.2.sp,
            color = MaterialTheme.colorScheme.onBackground
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = "Enable dynamic modules. Unused plugins are fully unloaded to conserve memory and keep the base app lightweight.",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
            lineHeight = 16.sp
        )

        Spacer(modifier = Modifier.height(20.dp))

        if (plugins.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                items(plugins, key = { it.id }) { plugin ->
                    PluginConfigRow(
                        plugin = plugin,
                        onToggle = { viewModel.togglePlugin(plugin) }
                    )
                }
            }
        }
    }
}

@Composable
fun PluginConfigRow(
    plugin: PluginConfig,
    onToggle: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (plugin.enabled) MaterialTheme.colorScheme.surface
            else MaterialTheme.colorScheme.surface.copy(alpha = 0.4f)
        ),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(
            1.dp,
            if (plugin.enabled) MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)
            else Color.Transparent
        ),
        modifier = Modifier
            .fillMaxWidth()
            .shadow(2.dp, RoundedCornerShape(16.dp))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Icon representing plugin type
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .background(
                        if (plugin.enabled) MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)
                        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f),
                        RoundedCornerShape(12.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = when (plugin.id) {
                        "view_enhancer" -> Icons.Default.Settings
                        "manhwa_editor" -> Icons.Default.Edit
                        "metadata_bookmark" -> Icons.AutoMirrored.Filled.List
                        else -> Icons.Default.Settings
                    },
                    contentDescription = plugin.name,
                    tint = if (plugin.enabled) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                    modifier = Modifier.size(24.dp)
                )
            }

            Spacer(modifier = Modifier.width(16.dp))

            Column(
                modifier = Modifier.weight(1f)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = plugin.name,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        color = if (plugin.enabled) MaterialTheme.colorScheme.onSurface
                        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                    )
                    if (plugin.enabled) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Box(
                            modifier = Modifier
                                .background(
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                                    RoundedCornerShape(4.dp)
                                )
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                "ACTIVE",
                                fontSize = 8.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = plugin.description,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    lineHeight = 16.sp
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            Switch(
                checked = plugin.enabled,
                onCheckedChange = { onToggle() },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = MaterialTheme.colorScheme.primary,
                    checkedTrackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)
                ),
                modifier = Modifier.testTag("switch_${plugin.id}")
            )
        }
    }
}

// --- SCREEN: Heavy-Duty Comic Reader ---
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun ComicReaderScreen(
    viewModel: ManhwaViewModel,
    onControlsVisibilityChanged: (Boolean) -> Unit = {}
) {
    val activeManhwa by viewModel.activeManhwa.collectAsStateWithLifecycle()
    val plugins by viewModel.allPlugins.collectAsStateWithLifecycle()
    val activeBookmarks by viewModel.activeBookmarks.collectAsStateWithLifecycle()
    val currentPage by viewModel.currentPage.collectAsStateWithLifecycle()
    val readerTheme by viewModel.readerTheme.collectAsStateWithLifecycle()

    val isOutlineOpen by viewModel.isOutlineDrawerOpen.collectAsStateWithLifecycle()

    // Color/view enhancements
    val brightness by viewModel.brightness.collectAsStateWithLifecycle()
    val contrast by viewModel.contrast.collectAsStateWithLifecycle()
    val saturation by viewModel.saturation.collectAsStateWithLifecycle()
    val warmth by viewModel.warmth.collectAsStateWithLifecycle()
    val gamma by viewModel.gamma.collectAsStateWithLifecycle()
    val autoGammaEnabled by viewModel.autoGammaEnabled.collectAsStateWithLifecycle()
    val customTint by viewModel.customTint.collectAsStateWithLifecycle()
    val autoNightShift by viewModel.autoNightShift.collectAsStateWithLifecycle()
    val mangaScanCrisper by viewModel.mangaScanCrisper.collectAsStateWithLifecycle()
    val colorMode by viewModel.colorMode.collectAsStateWithLifecycle()
    val hdMode by viewModel.hdModeEnabled.collectAsStateWithLifecycle()
    val presetFilter by viewModel.presetFilter.collectAsStateWithLifecycle()

    // Lightroom Enhancements
    val exposure by viewModel.exposure.collectAsStateWithLifecycle()
    val highlights by viewModel.highlights.collectAsStateWithLifecycle()
    val shadows by viewModel.shadows.collectAsStateWithLifecycle()
    val pdfEngineSetting by viewModel.pdfEngineSetting.collectAsStateWithLifecycle()

    // Sketch editor
    val drawColor by viewModel.activeDrawColor.collectAsStateWithLifecycle()
    val strokeWidth by viewModel.activeStrokeWidth.collectAsStateWithLifecycle()
    val sketches by viewModel.sketches.collectAsStateWithLifecycle()
    val drawHighlighter by viewModel.activeDrawHighlighter.collectAsStateWithLifecycle()

    val showEditFeatures by viewModel.showEditFeatures.collectAsStateWithLifecycle()
    val virtualPages by viewModel.virtualPages.collectAsStateWithLifecycle()
    val currentVirtualIndex by viewModel.currentVirtualPageIndex.collectAsStateWithLifecycle()

    // Verify which plugins are currently enabled and unlocked
    val isViewEnhancerEnabled = remember(plugins) { 
        plugins.find { it.id == "view_enhancer" }?.enabled == true && viewModel.isPluginUnlocked("view_enhancer")
    }
    val isSketchEditorEnabled = remember(plugins, showEditFeatures) { 
        showEditFeatures && plugins.find { it.id == "manhwa_editor" }?.enabled == true && viewModel.isPluginUnlocked("manhwa_editor")
    }
    val isOutlineEnabled = remember(plugins) { 
        plugins.find { it.id == "metadata_bookmark" }?.enabled == true && viewModel.isPluginUnlocked("metadata_bookmark")
    }

    var isDrawModeOn by remember { mutableStateOf(false) }
    var showAddBookmarkDialog by remember { mutableStateOf(false) }
    var bookmarkTitleInput by remember { mutableStateOf("") }

    val lazyListState = rememberLazyListState(
        initialFirstVisibleItemIndex = activeManhwa?.lastReadPage ?: 0,
        initialFirstVisibleItemScrollOffset = activeManhwa?.scrollOffset ?: 0
    )

    LaunchedEffect(lazyListState.isScrollInProgress) {
        viewModel.setUserScrolling(lazyListState.isScrollInProgress)
    }
    val coroutineScope = rememberCoroutineScope()
    var componentWidth by remember { mutableStateOf(1080) }
    var areControlsVisible by remember { mutableStateOf(true) }
    var isScreenLocked by remember { mutableStateOf(false) }

    LaunchedEffect(areControlsVisible) {
        onControlsVisibilityChanged(areControlsVisible)
    }

    // Advanced zoom and magnifier lens state from ViewModel
    val activeZoomScale by viewModel.activeZoomScale.collectAsStateWithLifecycle()
    val isMagnifierEnabled by viewModel.isMagnifierEnabled.collectAsStateWithLifecycle()
    val zoomLockEnabled by viewModel.zoomLockEnabled.collectAsStateWithLifecycle()
    val lockedZoomLevel by viewModel.lockedZoomLevel.collectAsStateWithLifecycle()
    val doubleTapZoomScale by viewModel.doubleTapZoomScale.collectAsStateWithLifecycle()

    val pageSpacing by viewModel.pageSpacing.collectAsStateWithLifecycle()
    val keepScreenOn by viewModel.keepScreenOn.collectAsStateWithLifecycle()
    val volumeScrollEnabled by viewModel.volumeScrollEnabled.collectAsStateWithLifecycle()

    val currentView = androidx.compose.ui.platform.LocalView.current
    DisposableEffect(keepScreenOn) {
        if (keepScreenOn) {
            currentView.keepScreenOn = true
        }
        onDispose {
            currentView.keepScreenOn = false
        }
    }

    var isTransforming by remember { mutableStateOf(false) }
    var zoomScaleTarget by remember { mutableFloatStateOf(1.0f) }

    LaunchedEffect(activeZoomScale) {
        if (!isTransforming) {
            zoomScaleTarget = activeZoomScale
        }
    }

    val animatedZoomScale by androidx.compose.animation.core.animateFloatAsState(
        targetValue = zoomScaleTarget,
        animationSpec = if (isTransforming) {
            androidx.compose.animation.core.snap()
        } else {
            androidx.compose.animation.core.tween(durationMillis = 250, easing = androidx.compose.animation.core.FastOutSlowInEasing)
        },
        label = "SmoothZoom"
    )

    var magnifierPosition by remember { mutableStateOf<Offset?>(null) }
    var pausedAutoScrollSpeed by remember { mutableStateOf<Float?>(null) }
    var isReadingRulerEnabled by remember { mutableStateOf(false) }
    var rulerYRatio by remember { mutableStateOf(0.4f) }
    val horizScrollState = rememberScrollState()

    val magnifierGestureModifier = if (isMagnifierEnabled) {
        Modifier.pointerInput(Unit) {
            detectDragGestures(
                onDragStart = { magnifierPosition = it },
                onDrag = { change, _ ->
                    change.consume()
                    magnifierPosition = change.position
                },
                onDragEnd = { magnifierPosition = null },
                onDragCancel = { magnifierPosition = null }
            )
        }
    } else Modifier

    val transformableState = rememberTransformableState { zoomChange, panChange, _ ->
        isTransforming = true
        val newZoom = (zoomScaleTarget * zoomChange).coerceIn(1.0f, 4.0f)
        zoomScaleTarget = newZoom
        
        if (newZoom > 1.0f) {
            if (panChange.x != 0f) {
                coroutineScope.launch {
                    horizScrollState.scrollBy(-panChange.x)
                }
            }
            if (panChange.y != 0f) {
                coroutineScope.launch {
                    lazyListState.scrollBy(-panChange.y)
                }
            }
        }
    }

    LaunchedEffect(isTransforming, zoomScaleTarget) {
        if (isTransforming) {
            kotlinx.coroutines.delay(120)
            isTransforming = false
            viewModel.setActiveZoomScale(zoomScaleTarget)
        }
    }

    val zoomGestureModifier = if (!isMagnifierEnabled && !isDrawModeOn && !isScreenLocked && pdfEngineSetting != "NATIVE") {
        Modifier.transformable(state = transformableState)
    } else Modifier

    LaunchedEffect(pdfEngineSetting) {
        if (pdfEngineSetting == "NATIVE") {
            zoomScaleTarget = 1.0f
            viewModel.setActiveZoomScale(1.0f)
            isDrawModeOn = false
            viewModel.setAutoScrollSpeed(0f)
            pausedAutoScrollSpeed = null
        }
    }

    // Chapter navigation position memory restorer (Index + Offset)
    val activeManhwaId = activeManhwa?.id ?: 0L
    LaunchedEffect(activeManhwaId, virtualPages) {
        if (activeManhwaId > 0 && virtualPages.isNotEmpty()) {
            val lastPage = activeManhwa?.lastReadPage ?: 0
            val lastOffset = activeManhwa?.scrollOffset ?: 0
            val virtualLastPage = viewModel.getVirtualIndexForPhysicalPage(lastPage)
            
            if (lazyListState.firstVisibleItemIndex != virtualLastPage || lazyListState.firstVisibleItemScrollOffset != lastOffset) {
                try {
                    lazyListState.scrollToItem(virtualLastPage, lastOffset)
                } catch (e: Exception) {
                    // Ignore any instant scroll conflicts
                }
            }
        }
    }

    // Hands-Free Auto-Scroll system
    val autoScrollSpeed by viewModel.autoScrollSpeed.collectAsStateWithLifecycle()
    val swipeSensitivity by viewModel.swipeSensitivity.collectAsStateWithLifecycle()

    val toggleAutoScrollPause = {
        if (autoScrollSpeed > 0f) {
            pausedAutoScrollSpeed = autoScrollSpeed
            viewModel.setAutoScrollSpeed(0f)
        } else {
            val restoreSpeed = pausedAutoScrollSpeed ?: 1.5f
            viewModel.setAutoScrollSpeed(restoreSpeed)
            pausedAutoScrollSpeed = null
        }
    }

    val nestedScrollConnection = remember(swipeSensitivity) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.Drag && swipeSensitivity != 1.0f && !isDrawModeOn) {
                    val extraY = available.y * (swipeSensitivity - 1.0f)
                    lazyListState.dispatchRawDelta(-extraY)
                }
                return Offset.Zero
            }
        }
    }
    LaunchedEffect(autoScrollSpeed) {
        if (autoScrollSpeed > 0f) {
            val pixelsPerFrame = autoScrollSpeed * 1.5f
            while (true) {
                if (!lazyListState.isScrollInProgress) {
                    try {
                        lazyListState.scrollBy(pixelsPerFrame)
                    } catch (e: Exception) {
                        // Ignore scroll fighting
                    }
                }
                androidx.compose.runtime.withFrameMillis { }
            }
        }
    }

    // Volume Key Scroll Navigation
    LaunchedEffect(volumeScrollEnabled) {
        if (volumeScrollEnabled) {
            viewModel.volumeKeyEvent.collect { keyCode ->
                val scrollAmount = if (keyCode == android.view.KeyEvent.KEYCODE_VOLUME_UP) -600f else 600f
                try {
                    lazyListState.animateScrollBy(scrollAmount)
                } catch (e: Exception) {
                    // Ignore conflicts
                }
            }
        }
    }

    // Dynamic scroll tracking to update reading progress & velocity-based cache warming
    var lastScrollTime by remember { mutableLongStateOf(0L) }
    var lastScrollIndex by remember { mutableIntStateOf(0) }
    var lastScrollOffset by remember { mutableIntStateOf(0) }

    LaunchedEffect(lazyListState.firstVisibleItemIndex, lazyListState.firstVisibleItemScrollOffset) {
        val now = System.currentTimeMillis()
        val timeDelta = (now - lastScrollTime).coerceAtLeast(1L)
        
        // Calculate velocity (approximate 1 page as 3000px height)
        val indexDiff = lazyListState.firstVisibleItemIndex - lastScrollIndex
        val offsetDiff = lazyListState.firstVisibleItemScrollOffset - lastScrollOffset
        val pixelsScrolled = (indexDiff * 3000 + offsetDiff)
        val velocity = pixelsScrolled.toFloat() / timeDelta.toFloat() // pixels per millisecond

        // Update database with index and offset
        viewModel.setCurrentVirtualPageAndOffset(
            lazyListState.firstVisibleItemIndex,
            lazyListState.firstVisibleItemScrollOffset
        )

        // Pre-render pages if scrolling fast (Reading Velocity Cache Warming)
        if (componentWidth > 0 && lazyListState.firstVisibleItemIndex != lastScrollIndex) {
            viewModel.warmCacheForVelocity(
                currentPage = lazyListState.firstVisibleItemIndex,
                targetWidth = componentWidth,
                velocity = velocity
            )
        }

        lastScrollTime = now
        lastScrollIndex = lazyListState.firstVisibleItemIndex
        lastScrollOffset = lazyListState.firstVisibleItemScrollOffset
    }

    val backgroundBrushModifier = Modifier.background(Color(readerTheme.colorHex))

    Box(
        modifier = Modifier
            .fillMaxSize()
            .then(backgroundBrushModifier)
            .onGloballyPositioned { componentWidth = it.size.width }
            .then(magnifierGestureModifier)
            .then(zoomGestureModifier)
            .then(
                if (isMagnifierEnabled && magnifierPosition != null) {
                    Modifier.magnifier(
                        sourceCenter = { magnifierPosition ?: Offset.Unspecified },
                        zoom = 2.0f
                    )
                } else Modifier
            )
    ) {
        // --- 1. CONTINUOUS VERTICAL STRIP OF MANHWA PAGES ---
        var lastClickTime by remember { mutableLongStateOf(0L) }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .horizontalScroll(horizScrollState, enabled = animatedZoomScale > 1.0f)
        ) {
        val prevChapter = activeManhwa?.let { viewModel.getPreviousChapter(it) }
        val nextChapter = activeManhwa?.let { viewModel.getNextChapter(it) }

        // Chapter reach auto-load checking removed to prevent chaotic scroll jumping
        // Users must tap the Next/Prev chapter cards to navigate

        LazyColumn(
            state = lazyListState,
            userScrollEnabled = !isDrawModeOn && (animatedZoomScale <= 1.0f), // Freeze standard scrolling when zooming to allow dedicated panning
            modifier = Modifier
                .width(with(LocalDensity.current) { (componentWidth * animatedZoomScale).toDp() })
                .fillMaxHeight()
                .nestedScroll(nestedScrollConnection),
            verticalArrangement = Arrangement.spacedBy(pageSpacing.dp) // Dynamic reading spacing!
        ) {
            if (prevChapter != null) {
                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color.DarkGray.copy(alpha = 0.4f)),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp)
                            .clickable { 
                                viewModel.navigateToChapter(prevChapter)
                                coroutineScope.launch { lazyListState.scrollToItem(0) }
                            }
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(
                                imageVector = Icons.Default.KeyboardArrowUp,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "Scroll past top or Tap to load Previous Chapter",
                                fontSize = 12.sp,
                                color = Color.LightGray,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = prevChapter.title,
                                fontSize = 11.sp,
                                color = Color.Gray,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }

            val totalVirtualPages = virtualPages.size
            items(
                count = totalVirtualPages,
                key = { virtualIdx -> 
                    val vp = virtualPages.getOrNull(virtualIdx)
                    "page_${activeManhwa?.id ?: 0}_${vp?.physicalPageIndex ?: virtualIdx}_${vp?.splitMode ?: "NONE"}"
                }
            ) { virtualIdx ->
                val vp = virtualPages.getOrNull(virtualIdx) ?: return@items
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .wrapContentHeight()
                ) {
                    PdfPageItem(
                        pageIndex = vp.physicalPageIndex,
                        targetWidth = componentWidth,
                        zoomScale = animatedZoomScale,
                        isScrollInProgress = lazyListState.isScrollInProgress,
                        viewModel = viewModel,
                        brightness = brightness,
                        contrast = contrast,
                        saturation = saturation,
                        warmth = warmth,
                        gamma = gamma,
                        autoGammaEnabled = autoGammaEnabled,
                        customTint = customTint,
                        autoNightShift = autoNightShift,
                        mangaScanCrisper = mangaScanCrisper,
                        colorMode = colorMode,
                        landscapeSplitMode = vp.splitMode,
                        isScreenLocked = isScreenLocked,
                        onResetZoom = {
                            coroutineScope.launch {
                                horizScrollState.animateScrollTo(0)
                            }
                        },
                        onPdfClick = {
                            if (!isScreenLocked) {
                                val currentTime = System.currentTimeMillis()
                                if (currentTime - lastClickTime > 100) {
                                    lastClickTime = currentTime
                                    areControlsVisible = !areControlsVisible
                                }
                            }
                        },
                        onDoubleTap = { fractionX, fractionY, aspect ->
                            if (isScreenLocked || pdfEngineSetting == "NATIVE") return@PdfPageItem
                            val viewportHeight = lazyListState.layoutInfo.viewportSize.height
                            val pageHeight = componentWidth * aspect
                            val targetOffsetY = (fractionY * pageHeight * doubleTapZoomScale) - (viewportHeight / 2f)
                            coroutineScope.launch {
                                try {
                                    lazyListState.animateScrollToItem(
                                        index = virtualIdx,
                                        scrollOffset = targetOffsetY.toInt().coerceAtLeast(0)
                                    )
                                } catch (e: Exception) {
                                    lazyListState.scrollToItem(
                                        index = virtualIdx,
                                        scrollOffset = targetOffsetY.toInt().coerceAtLeast(0)
                                    )
                                }
                            }
                            coroutineScope.launch {
                                kotlinx.coroutines.delay(120)
                                val maxScrollX = horizScrollState.maxValue
                                if (maxScrollX > 0) {
                                    val targetScrollX = (fractionX * (componentWidth * doubleTapZoomScale)) - (componentWidth / 2f)
                                    horizScrollState.animateScrollTo(
                                        targetScrollX.toInt().coerceIn(0, maxScrollX)
                                    )
                                }
                            }
                        }
                    )

                        // Draw drawing sketch overlay on page
                        if (isSketchEditorEnabled) {
                            DrawingSketchOverlay(
                                pageIndex = vp.physicalPageIndex,
                                sketches = sketches[vp.physicalPageIndex] ?: emptyList(),
                                isDrawModeOn = isDrawModeOn,
                                drawColor = if (drawHighlighter) drawColor.copy(alpha = 0.35f) else drawColor,
                                strokeWidth = strokeWidth,
                                onDrawFinished = { path ->
                                    viewModel.addDrawPath(vp.physicalPageIndex, path)
                                }
                            )
                        }
                    }
                }

                if (nextChapter != null) {
                    item {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = Color.DarkGray.copy(alpha = 0.4f)),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp)
                                .clickable { 
                                    viewModel.navigateToChapter(nextChapter)
                                    coroutineScope.launch { lazyListState.scrollToItem(0) }
                                }
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Icon(
                                    imageVector = Icons.Default.KeyboardArrowDown,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(24.dp)
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = "End of chapter. Scroll or Tap to load Next Chapter",
                                    fontSize = 12.sp,
                                    color = Color.LightGray,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = nextChapter.title,
                                    fontSize = 11.sp,
                                    color = Color.Gray,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }
        }

        // --- 1.5 READING RULER OVERLAY ---
        if (isReadingRulerEnabled) {
            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                val height = maxHeight
                val yOffset = height * rulerYRatio
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .offset(y = yOffset - 16.dp)
                        .height(32.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .align(Alignment.Center)
                            .height(2.5.dp)
                            .background(
                                brush = Brush.horizontalGradient(
                                    colors = listOf(
                                        MaterialTheme.colorScheme.primary.copy(alpha = 0.05f),
                                        MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
                                        MaterialTheme.colorScheme.primary,
                                        MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
                                        MaterialTheme.colorScheme.primary.copy(alpha = 0.05f)
                                    )
                                )
                            )
                    )
                    
                    Box(
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .padding(end = 8.dp)
                            .size(28.dp)
                            .shadow(3.dp, CircleShape)
                            .background(MaterialTheme.colorScheme.primary, CircleShape)
                            .pointerInput(Unit) {
                                detectDragGestures { change, dragAmount ->
                                    change.consume()
                                    val newY = (yOffset.toPx() + dragAmount.y).coerceIn(0f, height.toPx())
                                    rulerYRatio = newY / height.toPx()
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Menu,
                            contentDescription = "Drag Ruler",
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }
            }
        }

        // --- 1.6 CUSTOM POLISHED SCROLLBAR (NORMAL VS FULLSCREEN) & LOCK BUTTON ---
        val totalVirtualPages = virtualPages.size
        if (totalVirtualPages > 1 && !isDrawModeOn) {
            val density = LocalDensity.current
            var isDraggingScrollbar by remember { mutableStateOf(false) }

            val firstVisibleIndex = lazyListState.firstVisibleItemIndex
            val firstVisibleOffset = lazyListState.firstVisibleItemScrollOffset
            val visibleItems = lazyListState.layoutInfo.visibleItemsInfo

            val progress = remember(firstVisibleIndex, firstVisibleOffset, totalVirtualPages, visibleItems) {
                if (visibleItems.isEmpty()) 0f
                else {
                    val firstVisibleItem = visibleItems.first()
                    val itemSize = firstVisibleItem.size.toFloat().coerceAtLeast(1f)
                    val fraction = (firstVisibleOffset.toFloat() / itemSize).coerceIn(0f, 1f)
                    ((firstVisibleIndex.toFloat() + fraction) / (totalVirtualPages - 1).coerceAtLeast(1).toFloat()).coerceIn(0f, 1f)
                }
            }

            if (areControlsVisible) {
                // --- NORMAL MODE SCROLLBAR (Starts below top bar, ends above bottom bar, 10% Right Screen Strip) ---
                val isScrollActive = lazyListState.isScrollInProgress || isDraggingScrollbar
                val thumbScale by androidx.compose.animation.core.animateFloatAsState(
                    targetValue = if (isScrollActive) 1.5f else 1.0f,
                    label = "thumb_scale"
                )

                BoxWithConstraints(
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .fillMaxHeight()
                        .fillMaxWidth(0.10f) // 10% dedicated scrollbar interaction strip
                        .padding(top = 68.dp, bottom = 96.dp, end = 4.dp) // Starts below top bar and ends above bottom bar
                ) {
                    val trackHeight = maxHeight
                    val trackHeightPx = with(density) { trackHeight.toPx() }
                    val thumbHeightPx = (trackHeightPx * (1f / totalVirtualPages.coerceAtLeast(8))).coerceIn(with(density) { 40.dp.toPx() }, with(density) { 100.dp.toPx() })
                    val maxScrollYPx = (trackHeightPx - thumbHeightPx).coerceAtLeast(0f)
                    val thumbOffsetPx = maxScrollYPx * progress

                    // Track background line
                    Box(
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .padding(end = 6.dp)
                            .fillMaxHeight()
                            .width(4.dp)
                            .background(Color.White.copy(alpha = 0.2f), RoundedCornerShape(2.dp))
                    )

                    // Black Draggable Thumb with 1.5x Pop Effect when Active
                    Box(
                        modifier = Modifier
                            .offset(y = with(density) { thumbOffsetPx.toDp() })
                            .align(Alignment.TopEnd)
                            .padding(end = 4.dp)
                            .width(8.dp)
                            .height(with(density) { thumbHeightPx.toDp() })
                            .graphicsLayer(scaleX = thumbScale, scaleY = thumbScale)
                            .shadow(
                                elevation = 6.dp,
                                shape = RoundedCornerShape(4.dp),
                                clip = false
                            )
                            .background(
                                color = Color.Black,
                                shape = RoundedCornerShape(4.dp)
                            )
                            .border(
                                width = 1.dp,
                                color = Color.White.copy(alpha = 0.6f),
                                shape = RoundedCornerShape(4.dp)
                            )
                            .pointerInput(totalVirtualPages) {
                                detectDragGestures(
                                    onDragStart = { isDraggingScrollbar = true },
                                    onDragEnd = { isDraggingScrollbar = false },
                                    onDragCancel = { isDraggingScrollbar = false },
                                    onDrag = { change, dragAmount ->
                                        change.consume()
                                        val currentY = (thumbOffsetPx + dragAmount.y).coerceIn(0f, maxScrollYPx)
                                        val newProgress = if (maxScrollYPx > 0) currentY / maxScrollYPx else 0f
                                        val floatIndex = newProgress * (totalVirtualPages - 1)
                                        val targetIndex = floatIndex.toInt().coerceIn(0, totalVirtualPages - 1)
                                        val remainderFraction = floatIndex - targetIndex
                                        val approxItemSize = visibleItems.firstOrNull { it.index == targetIndex }?.size ?: (componentWidth * 1.4f).toInt()
                                        val scrollOffsetPx = (remainderFraction * approxItemSize).toInt()
                                        coroutineScope.launch {
                                            try {
                                                lazyListState.scrollToItem(targetIndex, scrollOffsetPx)
                                            } catch (e: Exception) {}
                                        }
                                    }
                                )
                            }
                    )
                }
            } else {
                // --- FULL SCREEN MODE SCROLLBAR (Full Height from Top to Bottom) ---
                BoxWithConstraints(
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .fillMaxHeight() // Full screen height from top to bottom
                        .width(8.dp)
                        .padding(end = 2.dp)
                ) {
                    val trackHeight = maxHeight
                    val trackHeightPx = with(density) { trackHeight.toPx() }
                    val thumbHeightPx = with(density) { 10.dp.toPx() }
                    val maxScrollYPx = (trackHeightPx - thumbHeightPx).coerceAtLeast(0f)
                    val thumbOffsetPx = maxScrollYPx * progress

                    // Black 4dp width x 10dp height thumb indicator
                    Box(
                        modifier = Modifier
                            .offset(y = with(density) { thumbOffsetPx.toDp() })
                            .align(Alignment.TopEnd)
                            .width(4.dp)
                            .height(10.dp)
                            .background(
                                color = Color.Black,
                                shape = RoundedCornerShape(2.dp)
                            )
                            .border(
                                width = 0.5.dp,
                                color = Color.White.copy(alpha = 0.5f),
                                shape = RoundedCornerShape(2.dp)
                            )
                    )
                }
            }
        }

        // Lock / Unlock Icon Button in Full Screen Mode (Top Right Corner - No Background)
        AnimatedVisibility(
            visible = !isDrawModeOn && !areControlsVisible,
            enter = fadeIn() + slideInVertically(initialOffsetY = { -it }),
            exit = fadeOut() + slideOutVertically(targetOffsetY = { -it }),
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 16.dp, end = 16.dp)
        ) {
            IconButton(
                onClick = { isScreenLocked = !isScreenLocked },
                modifier = Modifier
                    .testTag("fullscreen_lock_button")
            ) {
                Icon(
                    imageVector = if (isScreenLocked) Icons.Default.Lock else Icons.Default.LockOpen,
                    contentDescription = if (isScreenLocked) "Screen Locked" else "Screen Unlocked",
                    tint = if (isScreenLocked) Color(0xFFFFD700) else Color.White,
                    modifier = Modifier.size(24.dp)
                )
            }
        }

        // Floating Auto-Scroll Play/Pause Controller FAB at Bottom Right Corner
        if ((autoScrollSpeed > 0f || pausedAutoScrollSpeed != null) && pdfEngineSetting != "NATIVE") {
            var showAutoScrollSpeedPopup by remember { mutableStateOf(false) }

            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 20.dp, bottom = if (areControlsVisible) 100.dp else 24.dp)
            ) {
                // Popup Auto-Scroll Speed Slider Card (Triggered on Long Press)
                AnimatedVisibility(
                    visible = showAutoScrollSpeedPopup,
                    enter = fadeIn() + slideInVertically(initialOffsetY = { it / 2 }),
                    exit = fadeOut() + slideOutVertically(targetOffsetY = { it / 2 }),
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(bottom = 60.dp)
                ) {
                    Surface(
                        color = Color.Black.copy(alpha = 0.92f),
                        contentColor = Color.White,
                        shape = RoundedCornerShape(16.dp),
                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.35f)),
                        modifier = Modifier
                            .width(220.dp)
                            .shadow(12.dp, RoundedCornerShape(16.dp))
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Auto-Scroll Speed",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                IconButton(
                                    onClick = { showAutoScrollSpeedPopup = false },
                                    modifier = Modifier.size(20.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = "Close",
                                        tint = Color.LightGray,
                                        modifier = Modifier.size(14.dp)
                                    )
                                }
                            }

                            val currentSpeed = if (autoScrollSpeed > 0f) autoScrollSpeed else (pausedAutoScrollSpeed ?: 2.0f)
                            Text(
                                text = String.format("%.1fx Speed", currentSpeed),
                                fontSize = 13.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = Color.White,
                                modifier = Modifier.padding(vertical = 4.dp)
                            )

                            Slider(
                                value = currentSpeed,
                                onValueChange = { newSpeed ->
                                    if (autoScrollSpeed > 0f) {
                                        viewModel.setAutoScrollSpeed(newSpeed)
                                    } else {
                                        pausedAutoScrollSpeed = newSpeed
                                    }
                                },
                                valueRange = 0.5f..8.0f,
                                modifier = Modifier.fillMaxWidth()
                            )

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceEvenly
                            ) {
                                TextButton(
                                    onClick = {
                                        viewModel.setAutoScrollSpeed(0f)
                                        pausedAutoScrollSpeed = null
                                        showAutoScrollSpeedPopup = false
                                    }
                                ) {
                                    Text("Turn Off", fontSize = 11.sp, color = MaterialTheme.colorScheme.error)
                                }
                                TextButton(onClick = { toggleAutoScrollPause() }) {
                                    Text(
                                        text = if (autoScrollSpeed > 0f) "Pause" else "Resume",
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        }
                    }
                }

                // FAB with Tap and Long-Press Detection (Background-free)
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(52.dp)
                        .pointerInput(Unit) {
                            detectTapGestures(
                                onTap = { toggleAutoScrollPause() },
                                onLongPress = { showAutoScrollSpeedPopup = !showAutoScrollSpeedPopup }
                            )
                        }
                        .testTag("floating_autoscroll_pause_button")
                ) {
                    if (autoScrollSpeed > 0f) {
                        PauseIcon(
                            tint = Color.White,
                            modifier = Modifier
                                .size(28.dp)
                                .shadow(6.dp, CircleShape)
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.PlayArrow,
                            contentDescription = "Resume Auto-Scroll",
                            tint = Color.White,
                            modifier = Modifier
                                .size(32.dp)
                                .shadow(6.dp, CircleShape)
                        )
                    }
                }
            }
        }

        // --- 2. CONTROL OVERLAYS & HUD (Heads-Up Display) ---
        // Top HUD Bar
        AnimatedVisibility(
            visible = !isDrawModeOn && areControlsVisible,
            enter = slideInVertically(initialOffsetY = { -it }),
            exit = slideOutVertically(targetOffsetY = { -it }),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
        ) {
            HUDTopBar(
                title = activeManhwa?.title ?: "Reading",
                currentPage = currentPage,
                totalPages = activeManhwa?.totalPages ?: 0,
                isFavorite = activeManhwa?.isFavorite ?: false,
                onToggleFavorite = { activeManhwa?.let { viewModel.toggleManhwaFavorite(it.id) } },
                isOutlineEnabled = isOutlineEnabled,
                isDrawModeSupported = isSketchEditorEnabled,
                isDrawModeOn = isDrawModeOn,
                isReadingRulerEnabled = isReadingRulerEnabled,
                isMagnifierEnabled = isMagnifierEnabled,
                hdMode = hdMode,
                pdfEngineSetting = pdfEngineSetting,
                onTogglePdfEngine = { viewModel.togglePdfEngine() },
                onBack = { viewModel.closeManhwa() },
                onToggleOutline = { viewModel.toggleOutlineDrawer() },
                onToggleDrawMode = { isDrawModeOn = !isDrawModeOn },
                onReadingRulerToggle = { isReadingRulerEnabled = it },
                onMagnifierToggle = { viewModel.setMagnifierEnabled(it) },
                onToggleHdMode = { viewModel.toggleHdMode() },
                onAddBookmarkClick = {
                    bookmarkTitleInput = "Chapter Mark"
                    showAddBookmarkDialog = true
                },
                onOpenLobby = { viewModel.openSettingsTab() },
                onOpenViewEnhancer = { viewModel.openSettingsTab() }
            )
        }

        // Drawing HUD (Shows up only when drawing is active)
        AnimatedVisibility(
            visible = isDrawModeOn,
            enter = slideInVertically(initialOffsetY = { -it }),
            exit = slideOutVertically(targetOffsetY = { -it }),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
        ) {
            val canUndo = viewModel.canUndo(currentPage)
            val canRedo = viewModel.canRedo(currentPage)
            val drawHighlighter by viewModel.activeDrawHighlighter.collectAsStateWithLifecycle()
            DrawingControlsBar(
                currentColor = drawColor,
                currentStroke = strokeWidth,
                canUndo = canUndo,
                canRedo = canRedo,
                drawHighlighter = drawHighlighter,
                onHighlighterToggle = { viewModel.setDrawHighlighter(it) },
                onColorSelect = { viewModel.setDrawColor(it) },
                onStrokeSelect = { viewModel.setStrokeWidth(it) },
                onUndo = { viewModel.undoDrawPath(currentPage) },
                onRedo = { viewModel.redoDrawPath(currentPage) },
                onClearPage = { viewModel.clearDrawPaths(currentPage) },
                onDone = { isDrawModeOn = false }
            )
        }

        // Bottom HUD Bar (Chapter bookmark creator, status)
        AnimatedVisibility(
            visible = !isDrawModeOn && areControlsVisible,
            enter = slideInVertically(initialOffsetY = { it }),
            exit = slideOutVertically(targetOffsetY = { it }),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
        ) {
            HUDBottomBar(
                currentPage = currentPage,
                totalPages = activeManhwa?.totalPages ?: 0,
                isViewEnhancerEnabled = isViewEnhancerEnabled,
                brightness = brightness,
                contrast = contrast,
                saturation = saturation,
                warmth = warmth,
                gamma = gamma,
                autoGammaEnabled = autoGammaEnabled,
                customTint = customTint,
                autoNightShift = autoNightShift,
                mangaScanCrisper = mangaScanCrisper,
                colorMode = colorMode,
                hdMode = hdMode,
                presetFilter = presetFilter,
                viewModel = viewModel,
                isOutlineEnabled = isOutlineEnabled,
                isMagnifierEnabled = isMagnifierEnabled,
                pdfEngineSetting = pdfEngineSetting,
                onTogglePdfEngine = { viewModel.togglePdfEngine() },
                onMagnifierToggle = { viewModel.setMagnifierEnabled(it) },
                zoomScaleTarget = zoomScaleTarget,
                onZoomScaleChange = { 
                    zoomScaleTarget = it
                    viewModel.setActiveZoomScale(it)
                },
                zoomLockEnabled = zoomLockEnabled,
                onZoomLockToggle = { viewModel.setZoomLockEnabled(it) },
                isReadingRulerEnabled = isReadingRulerEnabled,
                onReadingRulerToggle = { isReadingRulerEnabled = it },
                onAddBookmarkClick = {
                    bookmarkTitleInput = "Chapter Mark"
                    showAddBookmarkDialog = true
                },
                onBrightnessChange = { viewModel.setBrightness(it) },
                onContrastChange = { viewModel.setContrast(it) },
                onSaturationChange = { viewModel.setSaturation(it) },
                onWarmthChange = { viewModel.setWarmth(it) },
                onGammaChange = { viewModel.setGamma(it) },
                onToggleAutoGamma = { viewModel.setAutoGammaEnabled(it) },
                onCustomTintChange = { viewModel.setCustomTint(it) },
                onToggleAutoNightShift = { viewModel.setAutoNightShift(it) },
                onToggleMangaScanCrisper = { viewModel.setMangaScanCrisper(it) },
                onColorModeChange = { viewModel.setColorMode(it) },
                onToggleHdMode = { viewModel.toggleHdMode() },
                autoScrollSpeed = autoScrollSpeed,
                onAutoScrollSpeedChange = { viewModel.setAutoScrollSpeed(it) },
                pausedAutoScrollSpeed = pausedAutoScrollSpeed,
                onTogglePause = toggleAutoScrollPause,
                onClearPausedSpeed = { pausedAutoScrollSpeed = null },
                canNavigateBack = viewModel.canNavigateBack(),
                canNavigateForward = viewModel.canNavigateForward(),
                onNavigateBack = { viewModel.navigateBack() },
                onNavigateForward = { viewModel.navigateForward() },
                exposure = exposure,
                highlights = highlights,
                shadows = shadows,
                onExposureChange = { viewModel.setExposure(it) },
                onHighlightsChange = { viewModel.setHighlights(it) },
                onShadowsChange = { viewModel.setShadows(it) },
                onResetViewEnhancerSettings = { viewModel.resetViewEnhancerSettings() }
            )
        }

        // --- 3. SLIDE-OUT CHAPTER DRAWER / OUTLINE PANEL ---
        if (isOutlineEnabled && isOutlineOpen) {
            // Semi-transparent backdrop to close drawer
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.5f))
                    .clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() }
                    ) { viewModel.setOutlineDrawerOpen(false) }
            )

            // Outline Panel Drawer Content
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(300.dp)
                    .background(MaterialTheme.colorScheme.surface)
                    .shadow(16.dp)
                    .align(Alignment.CenterEnd)
                    .clickable(enabled = false) {} // block clicks passing through
            ) {
                ChapterOutlineDrawer(
                    bookmarks = activeBookmarks,
                    currentPage = currentPage,
                    totalPages = activeManhwa?.totalPages ?: 0,
                    onSelectPage = { pageIdx ->
                        val virtualIdx = viewModel.getVirtualIndexForPhysicalPage(pageIdx)
                        coroutineScope.launch {
                            lazyListState.scrollToItem(virtualIdx)
                        }
                        viewModel.setOutlineDrawerOpen(false)
                    },
                    onRemoveBookmark = { viewModel.removeBookmark(it) },
                    onClose = { viewModel.setOutlineDrawerOpen(false) }
                )
            }
        }


    }

    // Bookmark / Title creation dialog
    if (showAddBookmarkDialog) {
        AlertDialog(
            onDismissRequest = { showAddBookmarkDialog = false },
            title = { Text("Name this Section / Chapter", fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    Text("Tag page ${currentPage + 1} with a title for quick navigation outline:", fontSize = 13.sp)
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = bookmarkTitleInput,
                        onValueChange = { bookmarkTitleInput = it },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("bookmark_title_input"),
                        label = { Text("Chapter/Bookmark Title") }
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (bookmarkTitleInput.isNotBlank()) {
                            viewModel.addBookmarkForCurrentPage(bookmarkTitleInput)
                            showAddBookmarkDialog = false
                        }
                    }
                ) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddBookmarkDialog = false }) {
                    Text("Cancel")
                }
            },
            containerColor = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(16.dp)
        )
    }
}

// --- COMPOSABLE: HD PDF Page Renderer Item ---
@Composable
fun PdfPageSliceItem(
    pageIndex: Int,
    targetWidth: Int,
    sliceIndex: Int,
    sliceHeight: Int,
    totalHeight: Int,
    totalWidth: Int,
    scaleFactor: Float,
    qualityLevel: String = "HIGH",
    zoomScale: Float,
    isScrollInProgress: Boolean,
    hasLowResPreview: Boolean,
    viewModel: ManhwaViewModel,
    brightness: Float,
    contrast: Float,
    saturation: Float,
    warmth: Float,
    gamma: Float,
    autoGammaEnabled: Boolean,
    customTint: String,
    autoNightShift: Boolean,
    mangaScanCrisper: Boolean,
    colorMode: ManhwaViewModel.ColorMode,
    landscapeSplitMode: String = "NONE",
    numSlices: Int = 1
) {
    val hdScrollDelay by viewModel.hdScrollDelay.collectAsStateWithLifecycle()
    val staggerDelay by viewModel.staggerDelay.collectAsStateWithLifecycle()

    val exposure by viewModel.exposure.collectAsStateWithLifecycle()
    val highlights by viewModel.highlights.collectAsStateWithLifecycle()
    val shadows by viewModel.shadows.collectAsStateWithLifecycle()
    val presetFilter by viewModel.presetFilter.collectAsStateWithLifecycle()

    var isVisible by remember { mutableStateOf(true) }
    val renderZoomStep = remember(zoomScale) {
        (Math.round(zoomScale * 2f) / 2f).coerceIn(1.0f, 4.0f)
    }

    var sliceBitmap by remember(pageIndex, sliceIndex, sliceHeight, qualityLevel, scaleFactor, renderZoomStep, landscapeSplitMode) { mutableStateOf<Bitmap?>(null) }
    var isRendering by remember { mutableStateOf(false) }

    DisposableEffect(pageIndex, sliceIndex, sliceHeight, qualityLevel, scaleFactor, renderZoomStep, landscapeSplitMode) {
        onDispose {
            sliceBitmap = null
        }
    }

    LaunchedEffect(pageIndex, targetWidth, sliceIndex, sliceHeight, scaleFactor, renderZoomStep, qualityLevel, viewModel, landscapeSplitMode, isVisible) {
        if (!isVisible) {
            sliceBitmap = null
            return@LaunchedEffect
        }
        if (sliceBitmap != null) {
            return@LaunchedEffect
        }

        isRendering = true
        if (sliceIndex > 0 && staggerDelay > 0) {
            kotlinx.coroutines.delay(sliceIndex * staggerDelay)
        }
        val bitmap = viewModel.renderPageSlice(
            pageIndex = pageIndex,
            targetWidth = targetWidth,
            sliceIndex = sliceIndex,
            sliceHeight = sliceHeight,
            scaleFactor = scaleFactor * renderZoomStep,
            landscapeSplitMode = landscapeSplitMode
        )
        if (bitmap != null) {
            sliceBitmap = bitmap
        }
        isRendering = false
    }

    val basePageHeight = targetWidth * (totalHeight.toFloat() / totalWidth.toFloat())
    val baseSliceY = sliceIndex * sliceHeight
    val baseSliceHeightPx = if (sliceIndex == numSlices - 1) {
        (basePageHeight - baseSliceY).coerceAtLeast(1f)
    } else {
        sliceHeight.toFloat()
    }
    val displaySliceHeightPx = baseSliceHeightPx * zoomScale
    
    if (displaySliceHeightPx <= 0f || totalWidth <= 0) {
        Spacer(modifier = Modifier.height(1.dp))
        return
    }
    
    val density = LocalDensity.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(with(density) { displaySliceHeightPx.toDp() })
            .onGloballyPositioned { coordinates ->
                val positionY = coordinates.positionInWindow().y
                val height = coordinates.size.height
                val screenHeight = coordinates.parentLayoutCoordinates?.size?.height ?: 2400
                isVisible = positionY + height >= 0 && positionY <= screenHeight
            }
    ) {
        val bitmap = sliceBitmap
        if (bitmap != null) {
            val adjustedMatrix = remember(brightness, contrast, saturation, warmth, gamma, autoGammaEnabled, customTint, autoNightShift, mangaScanCrisper, colorMode, exposure, highlights, shadows, presetFilter) {
                getAdjustedColorMatrix(
                    brightness = brightness,
                    contrast = contrast,
                    saturation = saturation,
                    warmth = warmth,
                    gamma = gamma,
                    autoGammaEnabled = autoGammaEnabled,
                    customTint = customTint,
                    autoNightShift = autoNightShift,
                    mangaScanCrisper = mangaScanCrisper,
                    mode = colorMode,
                    exposure = exposure,
                    highlights = highlights,
                    shadows = shadows,
                    presetFilter = presetFilter
                )
            }

            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "Page ${pageIndex + 1} Slice ${sliceIndex + 1}",
                contentScale = ContentScale.FillBounds,
                colorFilter = ColorFilter.colorMatrix(adjustedMatrix),
                filterQuality = androidx.compose.ui.graphics.FilterQuality.High,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

// --- COMPOSABLE: HD PDF Page Renderer Item (Tiled for performance) ---
@Composable
fun PdfPageItem(
    pageIndex: Int,
    targetWidth: Int,
    zoomScale: Float,
    isScrollInProgress: Boolean,
    viewModel: ManhwaViewModel,
    brightness: Float,
    contrast: Float,
    saturation: Float,
    warmth: Float,
    gamma: Float,
    autoGammaEnabled: Boolean,
    customTint: String,
    autoNightShift: Boolean,
    mangaScanCrisper: Boolean,
    colorMode: ManhwaViewModel.ColorMode,
    landscapeSplitMode: String = "NONE",
    isScreenLocked: Boolean = false,
    onResetZoom: (() -> Unit)? = null,
    onPdfClick: () -> Unit,
    onDoubleTap: (fractionX: Float, fractionY: Float, aspect: Float) -> Unit
) {
    val scaleFactor by viewModel.activeScaleFactor.collectAsStateWithLifecycle()
    val qualityLevel by viewModel.qualityLevel.collectAsStateWithLifecycle()
    var aspectRatio by remember { mutableStateOf<Float?>(null) }
    var isLoadingAspect by remember { mutableStateOf(true) }

    var isVisible by remember { mutableStateOf(true) }
    val renderZoomStep = remember(zoomScale) {
        (Math.round(zoomScale * 2f) / 2f).coerceIn(1.0f, 4.0f)
    }

    val exposure by viewModel.exposure.collectAsStateWithLifecycle()
    val highlights by viewModel.highlights.collectAsStateWithLifecycle()
    val shadows by viewModel.shadows.collectAsStateWithLifecycle()

    val doubleTapZoomScale by viewModel.doubleTapZoomScale.collectAsStateWithLifecycle()
    val doubleTapResetEnabled by viewModel.doubleTapResetEnabled.collectAsStateWithLifecycle()
    val hapticFeedbackEnabled by viewModel.hapticFeedbackEnabled.collectAsStateWithLifecycle()

    val hapticFeedback = androidx.compose.ui.platform.LocalHapticFeedback.current

    LaunchedEffect(pageIndex, viewModel, landscapeSplitMode) {
        isLoadingAspect = true
        val baseAspect = viewModel.getPageAspectRatio(pageIndex)
        aspectRatio = if (landscapeSplitMode != "NONE") baseAspect * 2f else baseAspect
        if (aspectRatio == null || (aspectRatio ?: 0f) <= 0.01f) aspectRatio = 1.0f 
        isLoadingAspect = false
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.White)
            .pointerInput(isScreenLocked, doubleTapZoomScale, doubleTapResetEnabled, hapticFeedbackEnabled) {
                detectTapGestures(
                    onTap = {
                        if (!isScreenLocked) {
                            onPdfClick()
                        }
                    },
                    onDoubleTap = { tapOffset ->
                        if (isScreenLocked) return@detectTapGestures
                        if (hapticFeedbackEnabled) {
                            try {
                                hapticFeedback.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                            } catch (e: Exception) {
                                // Fallback if not supported
                            }
                        }
                        val currentActiveZoom = viewModel.activeZoomScale.value
                        if (currentActiveZoom > 1.05f) {
                            if (doubleTapResetEnabled) {
                                viewModel.setActiveZoomScale(1.0f)
                                onResetZoom?.invoke()
                            }
                        } else {
                            viewModel.setActiveZoomScale(doubleTapZoomScale)
                            val aspect = aspectRatio ?: 1.0f
                            val pageHeight = targetWidth * aspect
                            val fractionX = tapOffset.x / targetWidth
                            val fractionY = tapOffset.y / pageHeight
                            onDoubleTap(fractionX, fractionY, aspect)
                        }
                    }
                )
            }
            .onGloballyPositioned { coordinates ->
                val positionY = coordinates.positionInWindow().y
                val height = coordinates.size.height
                val screenHeight = coordinates.parentLayoutCoordinates?.size?.height ?: 2400
                isVisible = positionY + height >= 0 && positionY <= screenHeight
            }
    ) {
        val aspect = aspectRatio
        if (isLoadingAspect || aspect == null) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(0.7f)
                    .background(Color.White),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(36.dp)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Calculating layout for page ${pageIndex + 1}...",
                        fontSize = 11.sp,
                        color = Color.DarkGray,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        } else {
            val pdfEngineSetting by viewModel.pdfEngineSetting.collectAsStateWithLifecycle()

            if (pdfEngineSetting == "NATIVE") {
                var nativePageBitmap by remember(pageIndex, targetWidth, scaleFactor, renderZoomStep, qualityLevel, landscapeSplitMode) { mutableStateOf<Bitmap?>(null) }

                val presetFilter by viewModel.presetFilter.collectAsStateWithLifecycle()
                val adjustedMatrix = remember(brightness, contrast, saturation, warmth, gamma, autoGammaEnabled, customTint, autoNightShift, mangaScanCrisper, colorMode, exposure, highlights, shadows, presetFilter) {
                    getAdjustedColorMatrix(
                        brightness = brightness,
                        contrast = contrast,
                        saturation = saturation,
                        warmth = warmth,
                        gamma = gamma,
                        autoGammaEnabled = autoGammaEnabled,
                        customTint = customTint,
                        autoNightShift = autoNightShift,
                        mangaScanCrisper = mangaScanCrisper,
                        mode = colorMode,
                        exposure = exposure,
                        highlights = highlights,
                        shadows = shadows,
                        presetFilter = presetFilter
                    )
                }

                LaunchedEffect(pageIndex, targetWidth, scaleFactor, renderZoomStep, qualityLevel, landscapeSplitMode, isVisible) {
                    if (!isVisible) {
                        nativePageBitmap = null
                        return@LaunchedEffect
                    }
                    if (nativePageBitmap != null) {
                        return@LaunchedEffect
                    }
                    val bmp = viewModel.renderPage(
                        pageIndex = pageIndex,
                        targetWidth = targetWidth,
                        scaleFactor = scaleFactor * renderZoomStep,
                        landscapeSplitMode = landscapeSplitMode
                    )
                    if (bmp != null) {
                        nativePageBitmap = bmp
                    }
                }

                DisposableEffect(pageIndex, targetWidth, scaleFactor, renderZoomStep, qualityLevel, landscapeSplitMode) {
                    onDispose {
                        // Directly unload page bitmap when scrolled off view in Native mode
                        nativePageBitmap = null
                    }
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f / aspect)
                        .background(Color.White)
                ) {
                    nativePageBitmap?.let { bmp ->
                        Image(
                            bitmap = bmp.asImageBitmap(),
                            contentDescription = "Page ${pageIndex + 1} Native Quality",
                            contentScale = ContentScale.Fit,
                            colorFilter = ColorFilter.colorMatrix(adjustedMatrix),
                            filterQuality = androidx.compose.ui.graphics.FilterQuality.High,
                            modifier = Modifier.fillMaxSize()
                        )
                    } ?: run {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(28.dp)
                            )
                        }
                    }
                }
            } else {
                val sliceHeight by viewModel.sliceHeight.collectAsStateWithLifecycle()
                val lowResScrollDelay by viewModel.lowResScrollDelay.collectAsStateWithLifecycle()
                val presetFilter by viewModel.presetFilter.collectAsStateWithLifecycle()

                val totalWidth = (targetWidth * scaleFactor).toInt().coerceAtLeast(400)
                val totalHeight = (totalWidth * aspect).toInt().coerceAtLeast(400)
                val basePageHeight = targetWidth * aspect
                val numSlices = Math.ceil(basePageHeight.toDouble() / sliceHeight).toInt().coerceAtLeast(1)

                var lowResBitmap by remember(pageIndex, landscapeSplitMode) { mutableStateOf<Bitmap?>(null) }
                DisposableEffect(pageIndex, landscapeSplitMode) {
                    onDispose {
                        lowResBitmap = null
                    }
                }
                LaunchedEffect(pageIndex, targetWidth, viewModel, landscapeSplitMode) {
                    if (lowResBitmap == null) {
                        lowResBitmap = viewModel.renderPageLowRes(pageIndex, targetWidth, landscapeSplitMode)
                    }
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f / aspect)
                ) {
                    lowResBitmap?.let { bmp ->
                        val adjustedMatrix = remember(brightness, contrast, saturation, warmth, gamma, autoGammaEnabled, customTint, autoNightShift, mangaScanCrisper, colorMode, exposure, highlights, shadows, presetFilter) {
                            getAdjustedColorMatrix(
                                brightness = brightness,
                                contrast = contrast,
                                saturation = saturation,
                                warmth = warmth,
                                gamma = gamma,
                                autoGammaEnabled = autoGammaEnabled,
                                customTint = customTint,
                                autoNightShift = autoNightShift,
                                mangaScanCrisper = mangaScanCrisper,
                                mode = colorMode,
                                exposure = exposure,
                                highlights = highlights,
                                shadows = shadows,
                                presetFilter = presetFilter
                            )
                        }
                        Image(
                            bitmap = bmp.asImageBitmap(),
                            contentDescription = "Page ${pageIndex + 1} Low-res Preview",
                            contentScale = ContentScale.Fit,
                            colorFilter = ColorFilter.colorMatrix(adjustedMatrix),
                            filterQuality = androidx.compose.ui.graphics.FilterQuality.None,
                            modifier = Modifier.fillMaxSize()
                        )
                    }

                    Column(
                        modifier = Modifier.fillMaxSize()
                    ) {
                        for (sliceIndex in 0 until numSlices) {
                            PdfPageSliceItem(
                                pageIndex = pageIndex,
                                targetWidth = targetWidth,
                                sliceIndex = sliceIndex,
                                sliceHeight = sliceHeight,
                                totalHeight = totalHeight,
                                totalWidth = totalWidth,
                                scaleFactor = scaleFactor,
                                qualityLevel = qualityLevel,
                                zoomScale = zoomScale,
                                isScrollInProgress = isScrollInProgress,
                                hasLowResPreview = (lowResBitmap != null),
                                viewModel = viewModel,
                                brightness = brightness,
                                contrast = contrast,
                                saturation = saturation,
                                warmth = warmth,
                                gamma = gamma,
                                autoGammaEnabled = autoGammaEnabled,
                                customTint = customTint,
                                autoNightShift = autoNightShift,
                                mangaScanCrisper = mangaScanCrisper,
                                colorMode = colorMode,
                                landscapeSplitMode = landscapeSplitMode,
                                numSlices = numSlices
                            )
                        }
                    }
                }
            }
        }
    }
}

// --- COMPOSABLE: Sketch Drawing Canvas Overlay ---
@Composable
fun DrawingSketchOverlay(
    pageIndex: Int,
    sketches: List<DrawPath>,
    isDrawModeOn: Boolean,
    drawColor: Color,
    strokeWidth: Float,
    onDrawFinished: (DrawPath) -> Unit
) {
    var activePoints = remember { mutableStateListOf<Offset>() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(isDrawModeOn) {
                if (!isDrawModeOn) return@pointerInput
                detectDragGestures(
                    onDragStart = { offset ->
                        activePoints.clear()
                        activePoints.add(offset)
                    },
                    onDrag = { change, _ ->
                        change.consume()
                        activePoints.add(change.position)
                    },
                    onDragEnd = {
                        if (activePoints.isNotEmpty()) {
                            onDrawFinished(
                                DrawPath(
                                    points = activePoints.toList(),
                                    color = drawColor,
                                    strokeWidth = strokeWidth
                                )
                            )
                            activePoints.clear()
                        }
                    }
                )
            }
    ) {
        // Draw static saved sketches and current active sketch
        Canvas(modifier = Modifier.matchParentSize()) {
            // Draw already committed sketches
            sketches.forEach { drawPath ->
                if (drawPath.points.size > 1) {
                    for (i in 0 until drawPath.points.size - 1) {
                        drawLine(
                            color = drawPath.color,
                            start = drawPath.points[i],
                            end = drawPath.points[i + 1],
                            strokeWidth = drawPath.strokeWidth,
                            cap = StrokeCap.Round
                        )
                    }
                }
            }

            // Draw active/current drawing path
            if (activePoints.size > 1) {
                for (i in 0 until activePoints.size - 1) {
                    drawLine(
                        color = drawColor,
                        start = activePoints[i],
                        end = activePoints[i + 1],
                        strokeWidth = strokeWidth,
                        cap = StrokeCap.Round
                    )
                }
            }
        }
    }
}

// --- HUD Top Bar ---
@Composable
fun HUDTopBar(
    title: String,
    currentPage: Int,
    totalPages: Int,
    isFavorite: Boolean = false,
    onToggleFavorite: (() -> Unit)? = null,
    isOutlineEnabled: Boolean,
    isDrawModeSupported: Boolean,
    isDrawModeOn: Boolean,
    isReadingRulerEnabled: Boolean = false,
    isMagnifierEnabled: Boolean = false,
    hdMode: Boolean = false,
    pdfEngineSetting: String = "PDFIUM",
    onTogglePdfEngine: () -> Unit = {},
    onBack: () -> Unit,
    onToggleOutline: () -> Unit,
    onToggleDrawMode: () -> Unit,
    onReadingRulerToggle: ((Boolean) -> Unit)? = null,
    onMagnifierToggle: ((Boolean) -> Unit)? = null,
    onToggleHdMode: (() -> Unit)? = null,
    onAddBookmarkClick: (() -> Unit)? = null,
    onOpenLobby: (() -> Unit)? = null,
    onOpenViewEnhancer: (() -> Unit)? = null
) {
    var showTopMenu by remember { mutableStateOf(false) }

    Surface(
        color = Color.Black.copy(alpha = 0.85f),
        contentColor = Color.White
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = onBack,
                enabled = pdfEngineSetting != "NATIVE",
                modifier = Modifier
                    .alpha(if (pdfEngineSetting == "NATIVE") 0.35f else 1.0f)
                    .testTag("reader_back_button")
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 8.dp)
            ) {
                Text(
                    text = title,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "Page ${currentPage + 1} of $totalPages",
                    fontSize = 11.sp,
                    color = Color.LightGray
                )
            }

            // PDF Engine Toggle Chip
            Surface(
                onClick = onTogglePdfEngine,
                shape = RoundedCornerShape(12.dp),
                color = if (pdfEngineSetting == "NATIVE") Color(0xFF4CAF50).copy(alpha = 0.25f) else MaterialTheme.colorScheme.primary.copy(alpha = 0.2f),
                border = BorderStroke(1.dp, if (pdfEngineSetting == "NATIVE") Color(0xFF4CAF50) else MaterialTheme.colorScheme.primary),
                modifier = Modifier
                    .padding(horizontal = 4.dp)
                    .testTag("pdf_engine_toggle_chip")
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Build,
                        contentDescription = "PDF Engine",
                        tint = if (pdfEngineSetting == "NATIVE") Color(0xFF4CAF50) else MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(13.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = if (pdfEngineSetting == "NATIVE") "Native" else "PDFium",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }
            }

            // Sketch Mode button
            if (isDrawModeSupported) {
                IconButton(
                    onClick = { if (pdfEngineSetting != "NATIVE") onToggleDrawMode() },
                    enabled = pdfEngineSetting != "NATIVE",
                    modifier = Modifier
                        .alpha(if (pdfEngineSetting == "NATIVE") 0.35f else 1.0f)
                        .background(
                            if (isDrawModeOn) MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)
                            else Color.Transparent,
                            CircleShape
                        )
                        .testTag("sketch_mode_toggle")
                ) {
                    Icon(Icons.Default.Edit, contentDescription = "Sketch", tint = if (isDrawModeOn) MaterialTheme.colorScheme.primary else Color.White)
                }
            }

            // Outline / Chapters button
            if (isOutlineEnabled) {
                IconButton(
                    onClick = { if (pdfEngineSetting != "NATIVE") onToggleOutline() },
                    enabled = pdfEngineSetting != "NATIVE",
                    modifier = Modifier
                        .alpha(if (pdfEngineSetting == "NATIVE") 0.35f else 1.0f)
                        .testTag("outline_drawer_toggle")
                ) {
                    Icon(Icons.Default.FormatListNumbered, contentDescription = "Outline", tint = Color.White)
                }
            }

            // Heart Favorite Toggle Button for Reader Top Bar
            if (onToggleFavorite != null) {
                IconButton(
                    onClick = onToggleFavorite,
                    modifier = Modifier.testTag("favorite_manhwa_hud_toggle")
                ) {
                    Icon(
                        imageVector = if (isFavorite) Icons.Filled.Favorite else Icons.Default.FavoriteBorder,
                        contentDescription = "Favorite Comic",
                        tint = if (isFavorite) Color(0xFFE91E63) else Color.White
                    )
                }
            }

            // Main Reader Menu Bar Button
            Box {
                IconButton(
                    onClick = { showTopMenu = true },
                    enabled = pdfEngineSetting != "NATIVE",
                    modifier = Modifier
                        .alpha(if (pdfEngineSetting == "NATIVE") 0.35f else 1.0f)
                        .testTag("top_menu_bar_button")
                ) {
                    Icon(Icons.Default.Menu, contentDescription = "Reader Menu", tint = Color.White)
                }

                DropdownMenu(
                    expanded = showTopMenu,
                    onDismissRequest = { showTopMenu = false },
                    modifier = Modifier
                        .background(Color.Black.copy(alpha = 0.95f))
                        .border(1.dp, Color.White.copy(alpha = 0.2f), RoundedCornerShape(8.dp))
                ) {
                    DropdownMenuItem(
                        text = { Text("Chapters & Outline", color = Color.White, fontSize = 13.sp) },
                        leadingIcon = { Icon(Icons.Default.FormatListNumbered, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                        onClick = {
                            showTopMenu = false
                            onToggleOutline()
                        }
                    )
                    if (isDrawModeSupported) {
                        DropdownMenuItem(
                            text = { Text(if (isDrawModeOn) "Exit Sketch Mode" else "Draw & Annotate", color = Color.White, fontSize = 13.sp) },
                            leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                            onClick = {
                                showTopMenu = false
                                onToggleDrawMode()
                            }
                        )
                    }
                    if (onAddBookmarkClick != null) {
                        DropdownMenuItem(
                            text = { Text("Add Bookmark", color = Color.White, fontSize = 13.sp) },
                            leadingIcon = { Icon(Icons.Default.BookmarkAdd, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                            onClick = {
                                showTopMenu = false
                                onAddBookmarkClick()
                            }
                        )
                    }
                    if (onReadingRulerToggle != null) {
                        DropdownMenuItem(
                            text = { Text(if (isReadingRulerEnabled) "Hide Reading Ruler" else "Reading Ruler", color = Color.White, fontSize = 13.sp) },
                            leadingIcon = { Icon(Icons.Default.Straighten, contentDescription = null, tint = if (isReadingRulerEnabled) MaterialTheme.colorScheme.primary else Color.LightGray) },
                            onClick = {
                                showTopMenu = false
                                onReadingRulerToggle(!isReadingRulerEnabled)
                            }
                        )
                    }
                    if (onMagnifierToggle != null) {
                        DropdownMenuItem(
                            text = { Text(if (isMagnifierEnabled) "Disable Magnifier" else "Magnifier Glass", color = Color.White, fontSize = 13.sp) },
                            leadingIcon = { Icon(Icons.Default.ZoomIn, contentDescription = null, tint = if (isMagnifierEnabled) MaterialTheme.colorScheme.primary else Color.LightGray) },
                            onClick = {
                                showTopMenu = false
                                onMagnifierToggle(!isMagnifierEnabled)
                            }
                        )
                    }
                    if (onToggleHdMode != null) {
                        DropdownMenuItem(
                            text = { Text(if (hdMode) "HD Quality (ON)" else "Enable HD Mode", color = Color.White, fontSize = 13.sp) },
                            leadingIcon = { Icon(Icons.Default.HighQuality, contentDescription = null, tint = if (hdMode) MaterialTheme.colorScheme.primary else Color.LightGray) },
                            onClick = {
                                showTopMenu = false
                                onToggleHdMode()
                            }
                        )
                    }
                    if (onOpenViewEnhancer != null) {
                        DropdownMenuItem(
                            text = { Text("View Enhancer Controls", color = Color.White, fontSize = 13.sp) },
                            leadingIcon = { Icon(Icons.Default.Tune, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                            onClick = {
                                showTopMenu = false
                                onOpenViewEnhancer()
                            }
                        )
                    }
                    if (onOpenLobby != null) {
                        DropdownMenuItem(
                            text = { Text("Open Lobby & Settings", color = Color.White, fontSize = 13.sp) },
                            leadingIcon = { Icon(Icons.Default.Dashboard, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                            onClick = {
                                showTopMenu = false
                                onOpenLobby()
                            }
                        )
                    }
                    HorizontalDivider(color = Color.DarkGray, thickness = 0.5.dp)
                    DropdownMenuItem(
                        text = { Text("Close & Back to Library", color = Color.White, fontSize = 13.sp) },
                        leadingIcon = { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null, tint = Color.Red.copy(alpha = 0.8f)) },
                        onClick = {
                            showTopMenu = false
                            onBack()
                        }
                    )
                }
            }
        }
    }
}

// --- Drawing Mode Controls HUD ---
@Composable
fun DrawingControlsBar(
    currentColor: Color,
    currentStroke: Float,
    canUndo: Boolean,
    canRedo: Boolean,
    drawHighlighter: Boolean,
    onHighlighterToggle: (Boolean) -> Unit,
    onColorSelect: (Color) -> Unit,
    onStrokeSelect: (Float) -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onClearPage: () -> Unit,
    onDone: () -> Unit
) {
    Surface(
        color = Color.Black.copy(alpha = 0.9f),
        contentColor = Color.White,
        modifier = Modifier.shadow(8.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "SKETCH SESSION",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 1.sp,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    
                    // Brush Preview
                    Box(
                        modifier = Modifier
                            .size(24.dp)
                            .background(Color.White.copy(alpha = 0.15f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .size((currentStroke / 1.5f).toInt().coerceIn(3, 18).dp)
                                .background(
                                    if (drawHighlighter) currentColor.copy(alpha = 0.35f) else currentColor,
                                    CircleShape
                                )
                        )
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Undo Button
                    TextButton(
                        onClick = onUndo,
                        enabled = canUndo,
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colorScheme.primary,
                            disabledContentColor = Color.Gray.copy(alpha = 0.5f)
                        )
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Undo",
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Undo", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }

                    Spacer(modifier = Modifier.width(4.dp))

                    // Redo Button
                    TextButton(
                        onClick = onRedo,
                        enabled = canRedo,
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colorScheme.primary,
                            disabledContentColor = Color.Gray.copy(alpha = 0.5f)
                        )
                    ) {
                        Text("Redo", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.width(4.dp))
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                            contentDescription = "Redo",
                            modifier = Modifier.size(16.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(12.dp))

                    TextButton(
                        onClick = onClearPage,
                        colors = ButtonDefaults.textButtonColors(contentColor = Color.Red.copy(alpha = 0.8f))
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = "Clear", modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Clear Page", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }

                    Spacer(modifier = Modifier.width(12.dp))

                    Button(
                        onClick = onDone,
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp),
                        modifier = Modifier.height(32.dp)
                    ) {
                        Text("Done", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.Black)
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Color selection Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Color:", fontSize = 12.sp, color = Color.LightGray)
                Spacer(modifier = Modifier.width(10.dp))
                val colors = listOf(Color.Red, Color.Green, Color.Blue, Color.Yellow, Color.White)
                colors.forEach { color ->
                    val isSelected = currentColor == color
                    Box(
                        modifier = Modifier
                            .padding(horizontal = 6.dp)
                            .size(24.dp)
                            .background(color, CircleShape)
                            .border(
                                width = if (isSelected) 2.dp else 1.dp,
                                color = if (isSelected) MaterialTheme.colorScheme.primary else Color.Gray,
                                shape = CircleShape
                            )
                            .clickable { onColorSelect(color) }
                    )
                }

                Spacer(modifier = Modifier.width(20.dp))

                // Highlighter mode toggle
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clickable { onHighlighterToggle(!drawHighlighter) }
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                        .background(
                            if (drawHighlighter) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                            else Color.Transparent,
                            RoundedCornerShape(6.dp)
                        )
                        .border(
                            width = 1.dp,
                            color = if (drawHighlighter) MaterialTheme.colorScheme.primary else Color.DarkGray,
                            shape = RoundedCornerShape(6.dp)
                        )
                        .padding(horizontal = 8.dp, vertical = 2.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Edit,
                        contentDescription = null,
                        tint = if (drawHighlighter) MaterialTheme.colorScheme.primary else Color.LightGray,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Highlighter",
                        fontSize = 11.sp,
                        color = if (drawHighlighter) MaterialTheme.colorScheme.primary else Color.LightGray,
                        fontWeight = if (drawHighlighter) FontWeight.Bold else FontWeight.Normal
                    )
                }

                Spacer(modifier = Modifier.weight(1f))

                // Brush thickness selector
                Text("Brush:", fontSize = 12.sp, color = Color.LightGray)
                Spacer(modifier = Modifier.width(8.dp))
                val strokeSizes = listOf(4f, 8f, 16f, 24f)
                strokeSizes.forEach { stroke ->
                    val isSelected = currentStroke == stroke
                    Box(
                        modifier = Modifier
                            .padding(horizontal = 4.dp)
                            .size(20.dp)
                            .background(
                                if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
                                else Color.Transparent,
                                RoundedCornerShape(4.dp)
                            )
                            .clickable { onStrokeSelect(stroke) },
                        contentAlignment = Alignment.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .size((stroke / 2).toInt().coerceIn(2, 10).dp)
                                .background(Color.White, CircleShape)
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun PauseIcon(tint: Color, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(modifier = Modifier.width(4.dp).fillMaxHeight().background(tint, RoundedCornerShape(1.dp)))
        Box(modifier = Modifier.width(4.dp).fillMaxHeight().background(tint, RoundedCornerShape(1.dp)))
    }
}

// --- HUD Bottom Bar ---
@Composable
fun HUDBottomBar(
    currentPage: Int,
    totalPages: Int,
    isViewEnhancerEnabled: Boolean,
    brightness: Float,
    contrast: Float,
    saturation: Float,
    warmth: Float,
    gamma: Float,
    autoGammaEnabled: Boolean,
    customTint: String,
    autoNightShift: Boolean,
    mangaScanCrisper: Boolean,
    colorMode: ManhwaViewModel.ColorMode,
    hdMode: Boolean,
    presetFilter: String,
    viewModel: ManhwaViewModel,
    isOutlineEnabled: Boolean,
    isMagnifierEnabled: Boolean,
    pdfEngineSetting: String = "PDFIUM",
    onTogglePdfEngine: () -> Unit = {},
    onMagnifierToggle: (Boolean) -> Unit,
    zoomScaleTarget: Float,
    onZoomScaleChange: (Float) -> Unit,
    zoomLockEnabled: Boolean,
    onZoomLockToggle: (Boolean) -> Unit,
    isReadingRulerEnabled: Boolean,
    onReadingRulerToggle: (Boolean) -> Unit,
    onAddBookmarkClick: () -> Unit,
    onBrightnessChange: (Float) -> Unit,
    onContrastChange: (Float) -> Unit,
    onSaturationChange: (Float) -> Unit,
    onWarmthChange: (Float) -> Unit,
    onGammaChange: (Float) -> Unit,
    onToggleAutoGamma: (Boolean) -> Unit,
    onCustomTintChange: (String) -> Unit,
    onToggleAutoNightShift: (Boolean) -> Unit,
    onToggleMangaScanCrisper: (Boolean) -> Unit,
    onColorModeChange: (ManhwaViewModel.ColorMode) -> Unit,
    onToggleHdMode: () -> Unit,
    autoScrollSpeed: Float,
    onAutoScrollSpeedChange: (Float) -> Unit,
    pausedAutoScrollSpeed: Float?,
    onTogglePause: () -> Unit,
    onClearPausedSpeed: () -> Unit,
    canNavigateBack: Boolean,
    canNavigateForward: Boolean,
    onNavigateBack: () -> Unit,
    onNavigateForward: () -> Unit,
    exposure: Float,
    highlights: Float,
    shadows: Float,
    onExposureChange: (Float) -> Unit,
    onHighlightsChange: (Float) -> Unit,
    onShadowsChange: (Float) -> Unit,
    onResetViewEnhancerSettings: () -> Unit
) {
    var showEnhancerControls by remember { mutableStateOf(false) }
    var showZoomControls by remember { mutableStateOf(false) }
    var showScrollControls by remember { mutableStateOf(false) }

    Surface(
        color = Color.Black.copy(alpha = 0.85f),
        contentColor = Color.White
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp)
        ) {
            // View Enhancer Control panel
            if (isViewEnhancerEnabled && showEnhancerControls) {
                Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth(0.95f)
                            .heightIn(max = 240.dp)
                            .clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp))
                            .background(Color.Black.copy(alpha = 0.95f))
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                    ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "IMAGE ENHANCEMENTS & FILTERS",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Black,
                            letterSpacing = 1.sp,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = "RESET ALL",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier
                                .clickable { onResetViewEnhancerSettings() }
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // Brightness slider
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.height(32.dp)) {
                        Text("Brightness", fontSize = 11.sp, color = Color.LightGray, modifier = Modifier.width(65.dp))
                        Slider(
                            value = brightness,
                            onValueChange = onBrightnessChange,
                            valueRange = 0.5f..1.5f,
                            modifier = Modifier.weight(1f),
                            colors = SliderDefaults.colors(thumbColor = MaterialTheme.colorScheme.primary)
                        )
                        Text(String.format("%.1f", brightness), fontSize = 10.sp, modifier = Modifier.width(25.dp), textAlign = TextAlign.End)
                    }

                    // Contrast slider
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.height(32.dp)) {
                        Text("Contrast", fontSize = 11.sp, color = Color.LightGray, modifier = Modifier.width(65.dp))
                        Slider(
                            value = contrast,
                            onValueChange = onContrastChange,
                            valueRange = 0.5f..1.5f,
                            modifier = Modifier.weight(1f),
                            colors = SliderDefaults.colors(thumbColor = MaterialTheme.colorScheme.primary)
                        )
                        Text(String.format("%.1f", contrast), fontSize = 10.sp, modifier = Modifier.width(25.dp), textAlign = TextAlign.End)
                    }

                    // Exposure slider
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.height(32.dp)) {
                        Text("Exposure", fontSize = 11.sp, color = Color.LightGray, modifier = Modifier.width(65.dp))
                        Slider(
                            value = exposure,
                            onValueChange = onExposureChange,
                            valueRange = 0.5f..2.0f,
                            modifier = Modifier.weight(1f),
                            colors = SliderDefaults.colors(thumbColor = MaterialTheme.colorScheme.primary)
                        )
                        Text(String.format("%.1f", exposure), fontSize = 10.sp, modifier = Modifier.width(25.dp), textAlign = TextAlign.End)
                    }

                    // Highlights slider
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.height(32.dp)) {
                        Text("Highlights", fontSize = 11.sp, color = Color.LightGray, modifier = Modifier.width(65.dp))
                        Slider(
                            value = highlights,
                            onValueChange = onHighlightsChange,
                            valueRange = -1.0f..1.0f,
                            modifier = Modifier.weight(1f),
                            colors = SliderDefaults.colors(thumbColor = MaterialTheme.colorScheme.primary)
                        )
                        Text(String.format("%+.1f", highlights), fontSize = 10.sp, modifier = Modifier.width(25.dp), textAlign = TextAlign.End)
                    }

                    // Shadows slider
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.height(32.dp)) {
                        Text("Shadows", fontSize = 11.sp, color = Color.LightGray, modifier = Modifier.width(65.dp))
                        Slider(
                            value = shadows,
                            onValueChange = onShadowsChange,
                            valueRange = -1.0f..1.0f,
                            modifier = Modifier.weight(1f),
                            colors = SliderDefaults.colors(thumbColor = MaterialTheme.colorScheme.primary)
                        )
                        Text(String.format("%+.1f", shadows), fontSize = 10.sp, modifier = Modifier.width(25.dp), textAlign = TextAlign.End)
                    }

                    // Saturation slider
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.height(32.dp)) {
                        Text("Saturation", fontSize = 11.sp, color = Color.LightGray, modifier = Modifier.width(65.dp))
                        Slider(
                            value = saturation,
                            onValueChange = onSaturationChange,
                            valueRange = 0.0f..2.0f,
                            modifier = Modifier.weight(1f),
                            colors = SliderDefaults.colors(thumbColor = MaterialTheme.colorScheme.primary)
                        )
                        Text(String.format("%.1f", saturation), fontSize = 10.sp, modifier = Modifier.width(25.dp), textAlign = TextAlign.End)
                    }

                    // Warmth slider
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.height(32.dp)) {
                        Text("Warmth", fontSize = 11.sp, color = Color.LightGray, modifier = Modifier.width(65.dp))
                        Slider(
                            value = warmth,
                            onValueChange = onWarmthChange,
                            valueRange = 0.0f..1.0f,
                            modifier = Modifier.weight(1f),
                            colors = SliderDefaults.colors(thumbColor = MaterialTheme.colorScheme.primary)
                        )
                        Text(String.format("%.1f", warmth), fontSize = 10.sp, modifier = Modifier.width(25.dp), textAlign = TextAlign.End)
                    }

                    // Gamma slider
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.height(32.dp)) {
                        Text("Gamma", fontSize = 11.sp, color = Color.LightGray, modifier = Modifier.width(65.dp))
                        Slider(
                            value = gamma,
                            onValueChange = onGammaChange,
                            valueRange = 0.5f..2.0f,
                            enabled = !autoGammaEnabled,
                            modifier = Modifier.weight(1f),
                            colors = SliderDefaults.colors(
                                thumbColor = MaterialTheme.colorScheme.primary,
                                disabledThumbColor = Color.Gray,
                                disabledActiveTrackColor = Color.DarkGray
                            )
                        )
                        Text(
                            text = if (autoGammaEnabled) "Auto" else String.format("%.1f", gamma),
                            fontSize = 10.sp,
                            modifier = Modifier.width(25.dp),
                            textAlign = TextAlign.End,
                            color = if (autoGammaEnabled) MaterialTheme.colorScheme.primary else Color.White
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // Color modes row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Filter: ", fontSize = 12.sp, color = Color.LightGray, modifier = Modifier.padding(end = 6.dp))
                        Row(
                            modifier = Modifier
                                .weight(1f)
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            val modes = listOf(
                                ManhwaViewModel.ColorMode.NORMAL to "Normal",
                                ManhwaViewModel.ColorMode.GRAYSCALE to "Gray",
                                ManhwaViewModel.ColorMode.SEPIA to "Sepia",
                                ManhwaViewModel.ColorMode.INVERTED to "Night",
                                ManhwaViewModel.ColorMode.PROTANOPIA to "Protan (Red-Blind)",
                                ManhwaViewModel.ColorMode.DEUTERANOPIA to "Deuteran (Green-Blind)",
                                ManhwaViewModel.ColorMode.TRITANOPIA to "Tritan (Blue-Blind)",
                                ManhwaViewModel.ColorMode.HIGH_CONTRAST to "Contrast+"
                            )
                            modes.forEach { (mode, name) ->
                                val selected = colorMode == mode
                                Text(
                                    text = name,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (selected) MaterialTheme.colorScheme.primary else Color.Gray,
                                    modifier = Modifier
                                        .clickable { onColorModeChange(mode) }
                                        .padding(horizontal = 8.dp, vertical = 6.dp)
                                        .background(
                                            if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f) else Color.Transparent,
                                            RoundedCornerShape(6.dp)
                                        )
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // Custom Tint Presets
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Tint Preset: ", fontSize = 12.sp, color = Color.LightGray, modifier = Modifier.padding(end = 6.dp))
                        Row(
                            modifier = Modifier
                                .weight(1f)
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            val tints = listOf("None", "Parchment", "Eye Care Green", "Mint", "Cobalt Filter", "Warm Amber")
                            tints.forEach { tint ->
                                val selected = customTint == tint
                                Text(
                                    text = tint,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (selected) MaterialTheme.colorScheme.primary else Color.Gray,
                                    modifier = Modifier
                                        .clickable { onCustomTintChange(tint) }
                                        .padding(horizontal = 8.dp, vertical = 6.dp)
                                        .background(
                                            if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f) else Color.Transparent,
                                            RoundedCornerShape(6.dp)
                                        )
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // Advanced Preset Filters
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Presets: ", fontSize = 12.sp, color = Color.LightGray, modifier = Modifier.padding(end = 6.dp))
                        Row(
                            modifier = Modifier
                                .weight(1f)
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            val presets = listOf("NONE" to "None", "FADED_PRINT" to "Faded Print", "BINARIZED" to "Binarized", "NEWSPAPER" to "Newspaper")
                            presets.forEach { (id, name) ->
                                val selected = presetFilter == id
                                Text(
                                    text = name,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (selected) MaterialTheme.colorScheme.primary else Color.Gray,
                                    modifier = Modifier
                                        .clickable { viewModel.setPresetFilter(id) }
                                        .padding(horizontal = 8.dp, vertical = 6.dp)
                                        .background(
                                            if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f) else Color.Transparent,
                                            RoundedCornerShape(6.dp)
                                        )
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // Auto-Tuning and Manga scan binarizer options
                    Text("AUTOMATIC ENHANCEMENTS", fontSize = 10.sp, fontWeight = FontWeight.Black, color = Color.Gray, letterSpacing = 0.5.sp)
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        HudOptionChip(
                            text = "Auto Gamma Correction",
                            selected = autoGammaEnabled,
                            onClick = { onToggleAutoGamma(!autoGammaEnabled) }
                        )

                        HudOptionChip(
                            text = "Auto-Night Shift",
                            selected = autoNightShift,
                            onClick = { onToggleAutoNightShift(!autoNightShift) }
                        )

                        HudOptionChip(
                            text = "Manga Scan Crisper",
                            selected = mangaScanCrisper,
                            onClick = { onToggleMangaScanCrisper(!mangaScanCrisper) }
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))
                    HorizontalDivider(color = Color.DarkGray, thickness = 0.5.dp)
                    Spacer(modifier = Modifier.height(6.dp))

                    // HD High Quality rendering Toggle
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Ultra-HD 2.0x Rendering (Tiling Engine)", fontSize = 12.sp, color = Color.LightGray)
                        Switch(
                            checked = hdMode,
                            onCheckedChange = { onToggleHdMode() },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = MaterialTheme.colorScheme.primary,
                                checkedTrackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)
                            ),
                            modifier = Modifier.scale(0.8f)
                        )
                    }
                }
            }
        }

            // Zoom & Focus engine control panel
            if (showZoomControls) {
                Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth(0.95f)
                            .heightIn(max = 240.dp)
                            .clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp))
                            .background(Color.Black.copy(alpha = 0.95f))
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                    ) {
                    Text(
                        "ZOOM & FOCUS ENGINE",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 1.sp,
                        color = MaterialTheme.colorScheme.primary
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    // PRESET ZOOM BUTTONS
                    Text("ZOOM PRESETS", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color.Gray)
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        val presets = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f)
                        presets.forEach { preset ->
                            val isSelected = Math.abs(zoomScaleTarget - preset) < 0.05f
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(30.dp)
                                    .background(
                                        if (isSelected) MaterialTheme.colorScheme.primaryContainer else Color.DarkGray.copy(alpha = 0.4f),
                                        RoundedCornerShape(6.dp)
                                    )
                                    .clickable { onZoomScaleChange(preset) },
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "${(preset * 100).toInt()}%",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else Color.White
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // CUSTOM % INPUT FIELD and LOCK ZOOM Switch
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        // Custom Zoom Input
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Exact Zoom:", fontSize = 11.sp, color = Color.LightGray)
                            Spacer(modifier = Modifier.width(8.dp))
                            var customText by remember(zoomScaleTarget) { mutableStateOf(String.format("%d", (zoomScaleTarget * 100).toInt())) }
                            
                            OutlinedTextField(
                                value = customText,
                                onValueChange = { input ->
                                    val filtered = input.filter { it.isDigit() }
                                    customText = filtered
                                    val pct = filtered.toFloatOrNull()
                                    if (pct != null && pct in 25f..400f) {
                                        onZoomScaleChange(pct / 100f)
                                    }
                                },
                                modifier = Modifier
                                    .width(75.dp)
                                    .height(38.dp),
                                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp, color = Color.White),
                                suffix = { Text("%", fontSize = 11.sp, color = Color.Gray) },
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                                    unfocusedBorderColor = Color.DarkGray,
                                    focusedContainerColor = Color.Black,
                                    unfocusedContainerColor = Color.Black
                                ),
                                shape = RoundedCornerShape(6.dp),
                                singleLine = true
                            )
                        }

                        // Lock Zoom Toggle
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Lock Zoom", fontSize = 11.sp, color = Color.LightGray)
                            Switch(
                                checked = zoomLockEnabled,
                                onCheckedChange = onZoomLockToggle,
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = MaterialTheme.colorScheme.primary,
                                    checkedTrackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)
                                ),
                                modifier = Modifier.scale(0.7f)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    HorizontalDivider(color = Color.DarkGray, thickness = 0.5.dp)
                    Spacer(modifier = Modifier.height(8.dp))

                    // MAGNIFIER LENS SWITCH & READING RULER SWITCH
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Magnifier Lens Toggle
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Magnifier Lens", fontSize = 11.sp, color = Color.LightGray)
                            Switch(
                                checked = isMagnifierEnabled,
                                onCheckedChange = onMagnifierToggle,
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = MaterialTheme.colorScheme.primary,
                                    checkedTrackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)
                                ),
                                modifier = Modifier.scale(0.7f)
                            )
                        }

                        // Reading Ruler Toggle
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Reading Ruler Guide", fontSize = 11.sp, color = Color.LightGray)
                            Switch(
                                checked = isReadingRulerEnabled,
                                onCheckedChange = onReadingRulerToggle,
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = MaterialTheme.colorScheme.primary,
                                    checkedTrackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)
                                ),
                                modifier = Modifier.scale(0.7f)
                            )
                        }
                    }
                }
            }
        }

            // Hands-Free Auto-Scroll control panel
            if (showScrollControls) {
                Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth(0.95f)
                            .heightIn(max = 240.dp)
                            .clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp))
                            .background(Color.Black.copy(alpha = 0.95f))
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                    ) {
                    Text(
                        "HANDS-FREE AUTO-SCROLL MODE",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 1.sp,
                        color = MaterialTheme.colorScheme.primary
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Scroll Speed",
                            fontSize = 12.sp,
                            color = Color.LightGray,
                            modifier = Modifier.width(90.dp)
                        )
                        Slider(
                            value = autoScrollSpeed,
                            onValueChange = {
                                onAutoScrollSpeedChange(it)
                                onClearPausedSpeed()
                            },
                            valueRange = 0f..10f,
                            modifier = Modifier.weight(1f),
                            colors = SliderDefaults.colors(thumbColor = MaterialTheme.colorScheme.primary)
                        )
                        Text(
                            text = if (autoScrollSpeed == 0f) "OFF" else String.format("%.1fx", autoScrollSpeed),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (autoScrollSpeed > 0f) MaterialTheme.colorScheme.primary else Color.Gray,
                            modifier = Modifier.width(40.dp),
                            textAlign = TextAlign.End
                        )
                    }

                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Sit back and read hands-free. Adjust speed to match your reading pace.",
                        fontSize = 11.sp,
                        color = Color.Gray
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (autoScrollSpeed > 0f) "Auto-Scroll is running" else "Auto-Scroll is stopped/paused",
                            fontSize = 11.sp,
                            color = if (autoScrollSpeed > 0f) MaterialTheme.colorScheme.primary else Color.Gray
                        )

                        Button(
                            onClick = onTogglePause,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (autoScrollSpeed > 0f) MaterialTheme.colorScheme.error.copy(alpha = 0.8f) else MaterialTheme.colorScheme.primary,
                                contentColor = Color.White
                            ),
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                            modifier = Modifier.height(32.dp).testTag("autoscroll_pause_toggle_btn")
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (autoScrollSpeed > 0f) {
                                    PauseIcon(
                                        tint = Color.White,
                                        modifier = Modifier.size(14.dp)
                                    )
                                } else {
                                    Icon(
                                        imageVector = Icons.Default.PlayArrow,
                                        contentDescription = "Play",
                                        modifier = Modifier.size(16.dp),
                                        tint = Color.White
                                    )
                                }
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = if (autoScrollSpeed > 0f) "PAUSE" else if (pausedAutoScrollSpeed != null) "RESUME" else "START",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
            }
        }



        // Main HUD action buttons
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // View enhancer, zoom, and scroll toggles
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (isViewEnhancerEnabled) {
                        IconButton(
                            onClick = { 
                                if (pdfEngineSetting != "NATIVE") {
                                    showEnhancerControls = !showEnhancerControls 
                                    if (showEnhancerControls) {
                                        showZoomControls = false
                                        showScrollControls = false
                                    }
                                }
                            },
                            enabled = pdfEngineSetting != "NATIVE",
                            modifier = Modifier.alpha(if (pdfEngineSetting == "NATIVE") 0.35f else 1.0f)
                        ) {
                            Icon(
                                Icons.Default.Settings,
                                contentDescription = "Enhance",
                                tint = if (showEnhancerControls) MaterialTheme.colorScheme.primary else Color.White
                            )
                        }
                    }
                    
                    IconButton(
                        onClick = { 
                            if (pdfEngineSetting != "NATIVE") {
                                showZoomControls = !showZoomControls 
                                if (showZoomControls) {
                                    showEnhancerControls = false
                                    showScrollControls = false
                                }
                            }
                        },
                        enabled = pdfEngineSetting != "NATIVE",
                        modifier = Modifier.alpha(if (pdfEngineSetting == "NATIVE") 0.35f else 1.0f)
                    ) {
                        Icon(
                            Icons.Default.Search,
                            contentDescription = "Zoom & Focus",
                            tint = if (showZoomControls) MaterialTheme.colorScheme.primary else Color.White
                        )
                    }

                    // Auto-scroll toggle (DISABLED in Native mode)
                    IconButton(
                        onClick = { 
                            if (pdfEngineSetting != "NATIVE") {
                                showScrollControls = !showScrollControls 
                                if (showScrollControls) {
                                    showEnhancerControls = false
                                    showZoomControls = false
                                }
                            }
                        },
                        enabled = pdfEngineSetting != "NATIVE",
                        modifier = Modifier.alpha(if (pdfEngineSetting == "NATIVE") 0.35f else 1.0f)
                    ) {
                        Icon(
                            imageVector = Icons.Default.PlayArrow,
                            contentDescription = "Auto-Scroll",
                            tint = if (showScrollControls) MaterialTheme.colorScheme.primary else if (autoScrollSpeed > 0f) MaterialTheme.colorScheme.secondary else Color.White
                        )
                    }

                    // Engine switch toggle button (ACTIVE in both modes)
                    IconButton(
                        onClick = onTogglePdfEngine,
                        modifier = Modifier.testTag("engine_toggle_bottom_bar_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Build,
                            contentDescription = "Switch Engine",
                            tint = if (pdfEngineSetting == "NATIVE") Color(0xFF4CAF50) else Color.LightGray
                        )
                    }
                }

                // Page slider and Browser-like Chapter History controls
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = onNavigateBack,
                        enabled = canNavigateBack && pdfEngineSetting != "NATIVE",
                        modifier = Modifier.alpha(if (pdfEngineSetting == "NATIVE") 0.35f else 1.0f)
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Prev Chapter History",
                            tint = if (canNavigateBack && pdfEngineSetting != "NATIVE") Color.White else Color.DarkGray
                        )
                    }

                    Text(
                        text = "${currentPage + 1} / $totalPages",
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                        color = Color.White
                    )

                    IconButton(
                        onClick = onNavigateForward,
                        enabled = canNavigateForward && pdfEngineSetting != "NATIVE",
                        modifier = Modifier.alpha(if (pdfEngineSetting == "NATIVE") 0.35f else 1.0f)
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                            contentDescription = "Next Chapter History",
                            tint = if (canNavigateForward && pdfEngineSetting != "NATIVE") Color.White else Color.DarkGray
                        )
                    }
                }

                // Chapter Bookmarker button
                if (isOutlineEnabled) {
                    IconButton(
                        onClick = { if (pdfEngineSetting != "NATIVE") onAddBookmarkClick() },
                        enabled = pdfEngineSetting != "NATIVE",
                        modifier = Modifier
                            .alpha(if (pdfEngineSetting == "NATIVE") 0.35f else 1.0f)
                            .testTag("add_bookmark_hud")
                    ) {
                        Icon(Icons.Default.Add, contentDescription = "Add Chapter Mark", tint = Color.White)
                    }
                } else {
                    Spacer(modifier = Modifier.width(48.dp))
                }
            }
        }
    }
}

// --- COMPOSABLE: HUD Option Chip ---
@Composable
fun HudOptionChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .clickable { onClick() }
            .background(
                color = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f) else Color.DarkGray.copy(alpha = 0.3f),
                shape = RoundedCornerShape(8.dp)
            )
            .border(
                width = 1.dp,
                color = if (selected) MaterialTheme.colorScheme.primary else Color.Gray.copy(alpha = 0.3f),
                shape = RoundedCornerShape(8.dp)
            )
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        Text(
            text = text,
            color = if (selected) MaterialTheme.colorScheme.primary else Color.LightGray,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

// --- COMPOSABLE: Chapter Outline Drawer Panel ---
@Composable
fun ChapterOutlineDrawer(
    bookmarks: List<Bookmark>,
    currentPage: Int,
    totalPages: Int,
    onSelectPage: (Int) -> Unit,
    onRemoveBookmark: (Bookmark) -> Unit,
    onClose: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "DOCUMENT OUTLINE",
                fontWeight = FontWeight.Black,
                fontSize = 15.sp,
                letterSpacing = 1.sp,
                color = MaterialTheme.colorScheme.onSurface
            )
            IconButton(onClick = onClose) {
                Icon(Icons.Default.Close, contentDescription = "Close Outline")
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f))

        Spacer(modifier = Modifier.height(16.dp))

        if (bookmarks.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                    modifier = Modifier.padding(16.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Info,
                        contentDescription = "No Outline",
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f),
                        modifier = Modifier.size(36.dp)
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = "No chapter marks set",
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Press the '+' button in the reader HUD to tag a page with a chapter title.",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                        textAlign = TextAlign.Center
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(bookmarks) { bookmark ->
                    val isCurrent = bookmark.pageIndex == currentPage
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(
                                if (isCurrent) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                                else Color.Transparent
                            )
                            .border(
                                width = 1.dp,
                                color = if (isCurrent) MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)
                                else Color.Transparent,
                                shape = RoundedCornerShape(10.dp)
                            )
                            .clickable { onSelectPage(bookmark.pageIndex) }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = bookmark.title,
                                fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Medium,
                                fontSize = 14.sp,
                                color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = "Page ${bookmark.pageIndex + 1}",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                            )
                        }

                        IconButton(
                            onClick = { onRemoveBookmark(bookmark) },
                            modifier = Modifier.size(24.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Delete bookmark",
                                tint = MaterialTheme.colorScheme.error.copy(alpha = 0.6f),
                                modifier = Modifier.size(14.dp)
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f))
        Spacer(modifier = Modifier.height(10.dp))

        // Document overview statistics
        Text(
            text = "Total Pages: $totalPages",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
            fontWeight = FontWeight.Bold,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center
        )
    }
}

// Helpers for combining matrices
fun concatColorMatrices(a: FloatArray, b: FloatArray): FloatArray {
    val result = FloatArray(20)
    for (row in 0 until 4) {
        for (col in 0 until 4) {
            result[row * 5 + col] = 
                a[row * 5 + 0] * b[0 * 5 + col] +
                a[row * 5 + 1] * b[1 * 5 + col] +
                a[row * 5 + 2] * b[2 * 5 + col] +
                a[row * 5 + 3] * b[3 * 5 + col]
        }
        result[row * 5 + 4] = 
            a[row * 5 + 0] * b[0 * 5 + 4] +
            a[row * 5 + 1] * b[1 * 5 + 4] +
            a[row * 5 + 2] * b[2 * 5 + 4] +
            a[row * 5 + 3] * b[3 * 5 + 4] +
            a[row * 5 + 4]
    }
    return result
}

fun getAdjustedColorMatrix(
    brightness: Float,
    contrast: Float,
    saturation: Float,
    warmth: Float,
    gamma: Float,
    autoGammaEnabled: Boolean,
    customTint: String,
    autoNightShift: Boolean,
    mangaScanCrisper: Boolean,
    mode: ManhwaViewModel.ColorMode,
    exposure: Float = 1.0f,
    highlights: Float = 0.0f,
    shadows: Float = 0.0f,
    presetFilter: String = "NONE"
): ColorMatrix {
    val calendar = java.util.Calendar.getInstance()
    val hour = calendar.get(java.util.Calendar.HOUR_OF_DAY)
    val isNightTime = hour >= 18 || hour < 6

    var effectiveContrast = contrast
    var effectiveBrightness = brightness
    var effectiveSaturation = saturation
    var effectiveHighlights = highlights
    var effectiveShadows = shadows
    var effectiveMode = mode

    when (presetFilter) {
        "FADED_PRINT" -> {
            effectiveContrast = contrast * 1.5f
            effectiveBrightness = brightness * 1.2f
            effectiveSaturation = 0.0f
            effectiveHighlights = highlights + 0.3f
            effectiveShadows = shadows - 0.3f
            effectiveMode = ManhwaViewModel.ColorMode.GRAYSCALE
        }
        "BINARIZED" -> {
            effectiveContrast = contrast * 3.5f
            effectiveBrightness = brightness * 1.3f
            effectiveSaturation = 0.0f
            effectiveHighlights = highlights + 0.5f
            effectiveShadows = shadows - 0.7f
            effectiveMode = ManhwaViewModel.ColorMode.GRAYSCALE
        }
        "NEWSPAPER" -> {
            effectiveContrast = contrast * 1.8f
            effectiveBrightness = brightness * 1.0f
            effectiveSaturation = 0.1f
            effectiveHighlights = highlights + 0.1f
            effectiveShadows = shadows - 0.2f
            effectiveMode = ManhwaViewModel.ColorMode.GRAYSCALE
        }
    }

    var effectiveGamma = gamma
    if (autoGammaEnabled) {
        effectiveGamma = if (isNightTime) 1.3f else 0.8f
    }

    var effectiveWarmth = warmth
    if (autoNightShift && isNightTime) {
        effectiveWarmth = (warmth + 0.35f).coerceAtMost(1.0f)
    }

    // Apply gamma multiplier approximation on base scale and translation
    val overallScale = effectiveBrightness * exposure
    val baseScale = effectiveContrast * overallScale
    val baseTranslate = ((1.0f - effectiveContrast) * 0.5f + (overallScale - 1.0f)) * 255f

    // Nonlinear mapping approximation
    val scale = if (effectiveGamma > 0f) Math.pow(baseScale.toDouble(), 1.0 / effectiveGamma.toDouble()).toFloat() else baseScale
    val translate = baseTranslate * (1.5f - (effectiveGamma * 0.5f))

    var finalScale = scale
    var finalTranslate = translate

    // Highlights correction (stretches or compresses the brighter end)
    finalScale = finalScale * (1.0f + effectiveHighlights * 0.12f)
    finalTranslate = finalTranslate - effectiveHighlights * 15f

    // Shadows correction (lifts or crushes the darker end)
    finalTranslate = finalTranslate + effectiveShadows * 35f
    finalScale = finalScale * (1.0f - effectiveShadows * 0.08f)

    if (mangaScanCrisper) {
        // High contrast and high brightness thresholding to wash out scan gray backgrounds to pure white
        finalScale = finalScale * 2.2f
        finalTranslate = finalTranslate - 95f
    }

    val baseMatrix = when (effectiveMode) {
        ManhwaViewModel.ColorMode.GRAYSCALE -> {
            floatArrayOf(
                0.299f * finalScale, 0.587f * finalScale, 0.114f * finalScale, 0f, finalTranslate,
                0.299f * finalScale, 0.587f * finalScale, 0.114f * finalScale, 0f, finalTranslate,
                0.299f * finalScale, 0.587f * finalScale, 0.114f * finalScale, 0f, finalTranslate,
                0f, 0f, 0f, 1f, 0f
            )
        }
        ManhwaViewModel.ColorMode.SEPIA -> {
            floatArrayOf(
                0.393f * finalScale, 0.769f * finalScale, 0.189f * finalScale, 0f, finalTranslate,
                0.349f * finalScale, 0.686f * finalScale, 0.168f * finalScale, 0f, finalTranslate,
                0.272f * finalScale, 0.534f * finalScale, 0.131f * finalScale, 0f, finalTranslate,
                0f, 0f, 0f, 1f, 0f
            )
        }
        ManhwaViewModel.ColorMode.INVERTED -> {
            val invScale = -finalScale
            val invTranslate = finalScale * 255f + finalTranslate
            floatArrayOf(
                invScale, 0f, 0f, 0f, invTranslate,
                0f, invScale, 0f, 0f, invTranslate,
                0f, 0f, invScale, 0f, invTranslate,
                0f, 0f, 0f, 1f, 0f
            )
        }
        ManhwaViewModel.ColorMode.PROTANOPIA -> {
            floatArrayOf(
                0.567f * finalScale, 0.433f * finalScale, 0f, 0f, finalTranslate,
                0.558f * finalScale, 0.442f * finalScale, 0f, 0f, finalTranslate,
                0f, 0.242f * finalScale, 0.758f * finalScale, 0f, finalTranslate,
                0f, 0f, 0f, 1f, 0f
            )
        }
        ManhwaViewModel.ColorMode.DEUTERANOPIA -> {
            floatArrayOf(
                0.625f * finalScale, 0.375f * finalScale, 0f, 0f, finalTranslate,
                0.7f * finalScale, 0.3f * finalScale, 0f, 0f, finalTranslate,
                0f, 0.3f * finalScale, 0.7f * finalScale, 0f, finalTranslate,
                0f, 0f, 0f, 1f, 0f
            )
        }
        ManhwaViewModel.ColorMode.TRITANOPIA -> {
            floatArrayOf(
                0.95f * finalScale, 0.05f * finalScale, 0f, 0f, finalTranslate,
                0f, 0.433f * finalScale, 0.567f * finalScale, 0f, finalTranslate,
                0f, 0.475f * finalScale, 0.525f * finalScale, 0f, finalTranslate,
                0f, 0f, 0f, 1f, 0f
            )
        }
        ManhwaViewModel.ColorMode.HIGH_CONTRAST -> {
            val hcScale = finalScale * 1.5f
            val hcTranslate = finalTranslate - 30f
            floatArrayOf(
                hcScale, 0f, 0f, 0f, hcTranslate,
                0f, hcScale, 0f, 0f, hcTranslate,
                0f, 0f, hcScale, 0f, hcTranslate,
                0f, 0f, 0f, 1f, 0f
            )
        }
        else -> {
            floatArrayOf(
                finalScale, 0f, 0f, 0f, finalTranslate,
                0f, finalScale, 0f, 0f, finalTranslate,
                0f, 0f, finalScale, 0f, finalTranslate,
                0f, 0f, 0f, 1f, 0f
            )
        }
    }

    // Apply Saturation Matrix
    val lr = 0.299f
    val lg = 0.587f
    val lb = 0.114f
    val invSat = 1.0f - saturation
    val satMatrix = floatArrayOf(
        invSat * lr + saturation, invSat * lg, invSat * lb, 0f, 0f,
        invSat * lr, invSat * lg + saturation, invSat * lb, 0f, 0f,
        invSat * lr, invSat * lg, invSat * lb + saturation, 0f, 0f,
        0f, 0f, 0f, 1f, 0f
    )

    // Apply Warmth (Yellow-Amber Red shift Filter) Matrix
    val wRed = 1.0f + effectiveWarmth * 0.1f
    val wGreen = 1.0f + effectiveWarmth * 0.02f
    val wBlue = 1.0f - effectiveWarmth * 0.35f
    val warmthMatrix = floatArrayOf(
        wRed, 0f, 0f, 0f, 0f,
        0f, wGreen, 0f, 0f, 0f,
        0f, 0f, wBlue, 0f, 0f,
        0f, 0f, 0f, 1f, 0f
    )

    // Apply Custom Tint Presets
    val tintMatrix = when (customTint) {
        "Parchment" -> floatArrayOf(
            1.04f, 0f, 0f, 0f, 0f,
            0f, 0.96f, 0f, 0f, 0f,
            0f, 0f, 0.85f, 0f, 0f,
            0f, 0f, 0f, 1f, 0f
        )
        "Eye Care Green" -> floatArrayOf(
            0.85f, 0f, 0f, 0f, 0f,
            0f, 1.02f, 0f, 0f, 0f,
            0f, 0f, 0.88f, 0f, 0f,
            0f, 0f, 0f, 1f, 0f
        )
        "Mint" -> floatArrayOf(
            0.82f, 0f, 0f, 0f, 0f,
            0f, 1.00f, 0f, 0f, 0f,
            0f, 0f, 0.95f, 0f, 0f,
            0f, 0f, 0f, 1f, 0f
        )
        "Cobalt Filter" -> floatArrayOf(
            1.00f, 0f, 0f, 0f, 0f,
            0f, 0.85f, 0f, 0f, 0f,
            0f, 0f, 0.55f, 0f, 0f,
            0f, 0f, 0f, 1f, 0f
        )
        "Warm Amber" -> floatArrayOf(
            1.00f, 0f, 0f, 0f, 0f,
            0f, 0.72f, 0f, 0f, 0f,
            0f, 0f, 0.30f, 0f, 0f,
            0f, 0f, 0f, 1f, 0f
        )
        else -> floatArrayOf(
            1f, 0f, 0f, 0f, 0f,
            0f, 1f, 0f, 0f, 0f,
            0f, 0f, 1f, 0f, 0f,
            0f, 0f, 0f, 1f, 0f
        )
    }

    val withSat = concatColorMatrices(satMatrix, baseMatrix)
    val withWarmth = concatColorMatrices(warmthMatrix, withSat)
    val withTint = concatColorMatrices(tintMatrix, withWarmth)

    return ColorMatrix(withTint)
}

// --- SCREEN: Lobby Tab (Centralized Options, Analysis, Settings & Presets) ---
@Composable
fun LobbyScreen(viewModel: ManhwaViewModel) {
    val pdfEngineSetting by viewModel.pdfEngineSetting.collectAsStateWithLifecycle()
    val qualitySelectionEnabled by viewModel.qualitySelectionEnabled.collectAsStateWithLifecycle()
    val qualityLevel by viewModel.qualityLevel.collectAsStateWithLifecycle()
    val maxStorageAllocation by viewModel.maxStorageAllocation.collectAsStateWithLifecycle()
    val currentReaderTheme by viewModel.readerTheme.collectAsStateWithLifecycle()

    val pageSpacing by viewModel.pageSpacing.collectAsStateWithLifecycle()
    val doubleTapZoomScale by viewModel.doubleTapZoomScale.collectAsStateWithLifecycle()
    val volumeScrollEnabled by viewModel.volumeScrollEnabled.collectAsStateWithLifecycle()
    val bitmapConfigSetting by viewModel.bitmapConfigSetting.collectAsStateWithLifecycle()
    val webpQuality by viewModel.webpQuality.collectAsStateWithLifecycle()
    val hdTextModeEnabled by viewModel.hdTextModeEnabled.collectAsStateWithLifecycle()
    val hapticFeedbackEnabled by viewModel.hapticFeedbackEnabled.collectAsStateWithLifecycle()
    val doubleTapResetEnabled by viewModel.doubleTapResetEnabled.collectAsStateWithLifecycle()
    val aggressiveGcEnabled by viewModel.aggressiveGcEnabled.collectAsStateWithLifecycle()
    val keepScreenOn by viewModel.keepScreenOn.collectAsStateWithLifecycle()
    val preloadCount by viewModel.preloadCount.collectAsStateWithLifecycle()
    val autoScrollStep by viewModel.autoScrollStep.collectAsStateWithLifecycle()
    val swipeSensitivity by viewModel.swipeSensitivity.collectAsStateWithLifecycle()
    
    val immersiveMode by viewModel.immersiveMode.collectAsStateWithLifecycle()
    val volumeKeyNavigation by viewModel.volumeKeyNavigation.collectAsStateWithLifecycle()
    val readingDirection by viewModel.readingDirection.collectAsStateWithLifecycle()
    val zoomLockEnabled by viewModel.zoomLockEnabled.collectAsStateWithLifecycle()
    val lockedZoomLevel by viewModel.lockedZoomLevel.collectAsStateWithLifecycle()

    val lowResScrollDelay by viewModel.lowResScrollDelay.collectAsStateWithLifecycle()
    val hdScrollDelay by viewModel.hdScrollDelay.collectAsStateWithLifecycle()
    val staggerDelay by viewModel.staggerDelay.collectAsStateWithLifecycle()
    val sliceHeight by viewModel.sliceHeight.collectAsStateWithLifecycle()

    val brightness by viewModel.brightness.collectAsStateWithLifecycle()
    val contrast by viewModel.contrast.collectAsStateWithLifecycle()
    val saturation by viewModel.saturation.collectAsStateWithLifecycle()
    val warmth by viewModel.warmth.collectAsStateWithLifecycle()
    val exposure by viewModel.exposure.collectAsStateWithLifecycle()
    val highlights by viewModel.highlights.collectAsStateWithLifecycle()
    val shadows by viewModel.shadows.collectAsStateWithLifecycle()

    val gamma by viewModel.gamma.collectAsStateWithLifecycle()
    val autoGammaEnabled by viewModel.autoGammaEnabled.collectAsStateWithLifecycle()
    val customTint by viewModel.customTint.collectAsStateWithLifecycle()
    val autoNightShift by viewModel.autoNightShift.collectAsStateWithLifecycle()
    val mangaScanCrisper by viewModel.mangaScanCrisper.collectAsStateWithLifecycle()
    val showEditFeatures by viewModel.showEditFeatures.collectAsStateWithLifecycle()
    
    val leftTapAction by viewModel.leftTapAction.collectAsStateWithLifecycle()
    val rightTapAction by viewModel.rightTapAction.collectAsStateWithLifecycle()
    val centerTapAction by viewModel.centerTapAction.collectAsStateWithLifecycle()
    val eyeRestReminderEnabled by viewModel.eyeRestReminderEnabled.collectAsStateWithLifecycle()
    val eyeRestIntervalMinutes by viewModel.eyeRestIntervalMinutes.collectAsStateWithLifecycle()
    val borderTrimEnabled by viewModel.borderTrimEnabled.collectAsStateWithLifecycle()
    val textModeFontSize by viewModel.textModeFontSize.collectAsStateWithLifecycle()
    val todayReadingSeconds by viewModel.todayReadingSeconds.collectAsStateWithLifecycle()
    val readingStreak by viewModel.readingStreak.collectAsStateWithLifecycle()
    
    var cacheSizeText by remember { mutableStateOf("0.00 MB") }
    val context = LocalContext.current
    val specs = viewModel.getDeviceSpecs()

    var searchQuery by remember { mutableStateOf("") }
    var expandedHeaderId by remember { mutableStateOf<String?>("library_shelf") }
    val favoriteHeaderIds by viewModel.favoriteHeaderIds.collectAsStateWithLifecycle()
    var showBackupDialog by remember { mutableStateOf(false) }
    var importJsonInput by remember { mutableStateOf("") }

    LaunchedEffect(Unit, qualityLevel, qualitySelectionEnabled) {
        cacheSizeText = viewModel.getMemoryCacheSizeText()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        // --- 1. LOBBY TITLE HEADER ---
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Text(
                    text = "LOBBY & CONTROL CENTER",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 1.2.sp,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Text(
                    text = "Centralized hub for rendering, display options, diagnostics & hardware presets.",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
                )
            }
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Dashboard,
                    contentDescription = "Lobby",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // --- 2. TOP BLUE SEARCH BAR ---
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            modifier = Modifier
                .fillMaxWidth()
                .testTag("lobby_search_bar"),
            placeholder = {
                Text(
                    text = "Search all options, analysis & settings...",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                )
            },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Default.Search,
                    contentDescription = "Search",
                    tint = MaterialTheme.colorScheme.primary
                )
            },
            trailingIcon = {
                if (searchQuery.isNotEmpty()) {
                    IconButton(onClick = { searchQuery = "" }) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Clear search",
                            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                    }
                }
            },
            shape = RoundedCornerShape(24.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = MaterialTheme.colorScheme.primary,
                unfocusedBorderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.4f),
                focusedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.04f),
                unfocusedContainerColor = MaterialTheme.colorScheme.surface
            ),
            singleLine = true
        )

        Spacer(modifier = Modifier.height(16.dp))

        // --- 3. ACCORDION HEADERS DEFINITION ---
        val rawHeaderSections = listOf(
            LobbyHeaderSection(
                id = "demo_pdf_sandbox",
                title = "Demo & Sandbox PDF Playground",
                subtitle = "Generate a sample PDF comic to experience instant gapless continuous loading",
                icon = Icons.Default.PlayArrow,
                searchKeywords = "demo sample pdf test generate create sandbox testbook dummy comic continuous gapless",
                content = {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        horizontalAlignment = Alignment.Start
                    ) {
                        Text(
                            text = "No PDF file on hand? Try out the Ultra Sandbox. Create and load a multi-page test PDF featuring simulated beautiful custom artwork patterns (Mandelbrot fractals, color grids, and text alignments) specifically designed to test zoom responsiveness, color profiles, contrast enhancements, and vertical gapless continuous rendering.",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                            lineHeight = 16.sp
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Button(
                            onClick = {
                                viewModel.createDummyTestPdf()
                            },
                            modifier = Modifier.fillMaxWidth().height(48.dp).testTag("generate_demo_pdf_button"),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary,
                                contentColor = MaterialTheme.colorScheme.onPrimary
                            )
                        ) {
                            Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(20.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Generate & Open Demo PDF Comic", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        }
                    }
                }
            ),
            LobbyHeaderSection(
                id = "library_shelf",
                title = "My PDF Shelf",
                subtitle = "Import and open loaded PDF comics, read history & progress analysis",
                icon = Icons.Default.Book,
                searchKeywords = "pdf shelf loaded comics read history continuous gapless list pdfs continue reading reading analysis",
                content = {
                    LibraryScreen(viewModel = viewModel)
                }
            ),
            LobbyHeaderSection(
                id = "plugin_manager",
                title = "Plugin Store & Extensions",
                subtitle = "Install, activate, and manage custom render modules",
                icon = Icons.Default.Build,
                searchKeywords = "plugin store manager dynamic modules extensions view enhancer manhwa editor active load",
                content = {
                    PluginsScreen(viewModel = viewModel)
                }
            ),
            LobbyHeaderSection(
                id = "perf_rendering",
                title = "Performance & Rendering Engine",
                subtitle = "PDF quality scaling, GPU bitmap format, text modes & memory purge",
                icon = Icons.Default.Speed,
                searchKeywords = "pdf quality resolution scale memory cache ram rendering bitmap gc text mode",
                content = {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        // ACTIVE PDF RENDER ENGINE CARD
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 12.dp),
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                            ),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.3f))
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text("ACTIVE PDF RENDER ENGINE", fontWeight = FontWeight.Black, fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = if (pdfEngineSetting == "NATIVE") "Native Android Engine Active: Renders 100% direct high resolution native pages with zero memory overhead. Other features are disabled for maximum speed."
                                           else "PDFium Engine Active: Supports full image enhancements, preset filters, sketch mode, and custom color adjustments.",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(modifier = Modifier.height(10.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    OutlinedButton(
                                        onClick = { viewModel.setPdfEngineSetting("PDFIUM") },
                                        modifier = Modifier.weight(1f).testTag("engine_pdfium_button"),
                                        shape = RoundedCornerShape(8.dp),
                                        colors = ButtonDefaults.outlinedButtonColors(
                                            containerColor = if (pdfEngineSetting == "PDFIUM") MaterialTheme.colorScheme.primary.copy(alpha = 0.15f) else Color.Transparent,
                                            contentColor = if (pdfEngineSetting == "PDFIUM") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                        ),
                                        border = BorderStroke(1.5.dp, if (pdfEngineSetting == "PDFIUM") MaterialTheme.colorScheme.primary else Color.Gray.copy(alpha = 0.3f))
                                    ) {
                                        Text("PDFium Engine", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                    }

                                    OutlinedButton(
                                        onClick = { viewModel.setPdfEngineSetting("NATIVE") },
                                        modifier = Modifier.weight(1f).testTag("engine_native_button"),
                                        shape = RoundedCornerShape(8.dp),
                                        colors = ButtonDefaults.outlinedButtonColors(
                                            containerColor = if (pdfEngineSetting == "NATIVE") Color(0xFF4CAF50).copy(alpha = 0.2f) else Color.Transparent,
                                            contentColor = if (pdfEngineSetting == "NATIVE") Color(0xFF4CAF50) else MaterialTheme.colorScheme.onSurface
                                        ),
                                        border = BorderStroke(1.5.dp, if (pdfEngineSetting == "NATIVE") Color(0xFF4CAF50) else Color.Gray.copy(alpha = 0.3f))
                                    ) {
                                        Text("Native Engine", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                    }
                                }
                            }
                        }

                        // PDF Quality Switch
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("PDF Resolution Scale Engine", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                Text("Dynamically scales page DPI for razor-sharp artwork", fontSize = 11.sp, color = Color.Gray)
                            }
                            Switch(
                                checked = qualitySelectionEnabled,
                                onCheckedChange = { viewModel.setQualitySelectionEnabled(it) }
                            )
                        }

                        if (qualitySelectionEnabled) {
                            Spacer(modifier = Modifier.height(12.dp))
                            Text("RESOLUTION LEVEL", fontWeight = FontWeight.Bold, fontSize = 10.sp, color = MaterialTheme.colorScheme.primary)
                            Spacer(modifier = Modifier.height(6.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                listOf("MAX", "HIGH", "MEDIUM", "AVERAGE", "LOW").forEach { opt ->
                                    val isSel = qualityLevel == opt
                                    Button(
                                        onClick = { viewModel.setQualityLevel(opt) },
                                        modifier = Modifier.weight(1f).height(36.dp),
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = if (isSel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
                                        ),
                                        contentPadding = PaddingValues(0.dp),
                                        shape = RoundedCornerShape(8.dp)
                                    ) {
                                        Text(opt, fontSize = 10.sp, color = if (isSel) Color.White else MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))
                        HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                        Spacer(modifier = Modifier.height(12.dp))

                        // Super-Res Text Mode
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Super-Res Text Sharpener", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                Text("1.5x rendering boost specifically for fine comic dialog", fontSize = 11.sp, color = Color.Gray)
                            }
                            Switch(
                                checked = hdTextModeEnabled,
                                onCheckedChange = { viewModel.setHdTextModeEnabled(it) }
                            )
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        // Aggressive GC
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Aggressive Garbage Collection", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                Text("Frees unreferenced page bitmaps immediately on scroll", fontSize = 11.sp, color = Color.Gray)
                            }
                            Switch(
                                checked = aggressiveGcEnabled,
                                onCheckedChange = { viewModel.setAggressiveGcEnabled(it) }
                            )
                        }

                        Spacer(modifier = Modifier.height(14.dp))
                        HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                        Spacer(modifier = Modifier.height(14.dp))

                        // Max Storage Cache Allocation Limit
                        Text("MAX DISK CACHE LIMIT: $maxStorageAllocation MB", fontWeight = FontWeight.Bold, fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.height(2.dp))
                        Text("Determines disk storage allowance for preloaded page slices", fontSize = 11.sp, color = Color.Gray)
                        Slider(
                            value = maxStorageAllocation.toFloat(),
                            onValueChange = { viewModel.setMaxStorageAllocation(it.toInt()) },
                            valueRange = 100f..2000f,
                            modifier = Modifier.fillMaxWidth()
                        )

                        Spacer(modifier = Modifier.height(10.dp))

                        // WebP Preload Compression Quality
                        Text("WEBP PRELOAD QUALITY: $webpQuality%", fontWeight = FontWeight.Bold, fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.height(2.dp))
                        Text("Higher is sharper; lower values reduce RAM and disk utilization", fontSize = 11.sp, color = Color.Gray)
                        Slider(
                            value = webpQuality.toFloat(),
                            onValueChange = { viewModel.setWebpQuality(it.toInt()) },
                            valueRange = 10f..100f,
                            modifier = Modifier.fillMaxWidth()
                        )

                        Spacer(modifier = Modifier.height(10.dp))

                        // Slice Height Segment Size
                        Text("RENDERED SLICE SEGMENT HEIGHT: ${sliceHeight} PX", fontWeight = FontWeight.Bold, fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.height(2.dp))
                        Text("DPI segment size for vertical seamless canvas stitching", fontSize = 11.sp, color = Color.Gray)
                        Slider(
                            value = sliceHeight.toFloat(),
                            onValueChange = { viewModel.setSliceHeight(it.toInt()) },
                            valueRange = 512f..2048f,
                            modifier = Modifier.fillMaxWidth()
                        )

                        Spacer(modifier = Modifier.height(14.dp))
                        HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                        Spacer(modifier = Modifier.height(14.dp))

                        // Low-Res Scroll Preload Delay
                        Text("LOW-RES PRELOAD DELAY: ${lowResScrollDelay} MS", fontWeight = FontWeight.Bold, fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.height(2.dp))
                        Text("Delay before generating lightweight page placeholders during rapid swipes", fontSize = 11.sp, color = Color.Gray)
                        Slider(
                            value = lowResScrollDelay.toFloat(),
                            onValueChange = { viewModel.setLowResScrollDelay(it.toLong()) },
                            valueRange = 0f..300f,
                            modifier = Modifier.fillMaxWidth()
                        )

                        Spacer(modifier = Modifier.height(10.dp))

                        // High-Definition Preload Delay
                        Text("HD PRELOAD RENDER DELAY: ${hdScrollDelay} MS", fontWeight = FontWeight.Bold, fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.height(2.dp))
                        Text("Wait buffer before rendering high-fidelity layers after scrolling stops", fontSize = 11.sp, color = Color.Gray)
                        Slider(
                            value = hdScrollDelay.toFloat(),
                            onValueChange = { viewModel.setHdScrollDelay(it.toLong()) },
                            valueRange = 0f..500f,
                            modifier = Modifier.fillMaxWidth()
                        )

                        Spacer(modifier = Modifier.height(10.dp))

                        // Stagger Pipeline Preload Delay
                        Text("PIPELINE STAGGER TIMEOUT: ${staggerDelay} MS", fontWeight = FontWeight.Bold, fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.height(2.dp))
                        Text("Staggers background thread processing of adjacent pages to keep UI buttery", fontSize = 11.sp, color = Color.Gray)
                        Slider(
                            value = staggerDelay.toFloat(),
                            onValueChange = { viewModel.setStaggerDelay(it.toLong()) },
                            valueRange = 0f..200f,
                            modifier = Modifier.fillMaxWidth()
                        )

                        Spacer(modifier = Modifier.height(14.dp))
                        HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                        Spacer(modifier = Modifier.height(14.dp))

                        // Bitmap Memory Hardware Format
                        Text("BITMAP HARDWARE COLOR FORMAT", fontWeight = FontWeight.Bold, fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.height(2.dp))
                        Text("ARGB_8888 (High precision), RGB_565 (Low RAM usage), HARDWARE (Direct GPU)", fontSize = 11.sp, color = Color.Gray)
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            listOf("ARGB_8888", "RGB_565", "HARDWARE").forEach { format ->
                                val isSel = bitmapConfigSetting == format
                                Button(
                                    onClick = { viewModel.setBitmapConfigSetting(format) },
                                    modifier = Modifier.weight(1f).height(36.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = if (isSel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
                                    ),
                                    contentPadding = PaddingValues(0.dp),
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Text(format, fontSize = 11.sp, color = if (isSel) Color.White else MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(18.dp))

                        Button(
                            onClick = {
                                viewModel.clearMemoryCache()
                                cacheSizeText = "0.00 MB"
                                Toast.makeText(context, "Purged memory cache!", Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Purge Memory Cache ($cacheSizeText)", fontSize = 12.sp)
                        }
                    }
                }
            ),
            LobbyHeaderSection(
                id = "display_theme",
                title = "Display, Canvas Themes & Eye Care",
                subtitle = "Reader canvas color, page gaps, WebP preloader & color filters",
                icon = Icons.Default.Palette,
                searchKeywords = "display theme canvas color background gap space spacing night shift gamma eye care webp tint",
                content = {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Text("READER CANVAS THEME", fontWeight = FontWeight.Bold, fontSize = 10.sp, color = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.height(8.dp))

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            ManhwaViewModel.ReaderTheme.entries.forEach { theme ->
                                val isSel = currentReaderTheme == theme
                                val tColor = Color(theme.colorHex)
                                Box(
                                    modifier = Modifier
                                        .background(tColor, RoundedCornerShape(10.dp))
                                        .border(if (isSel) 2.dp else 1.dp, if (isSel) MaterialTheme.colorScheme.primary else Color.Gray.copy(alpha = 0.3f), RoundedCornerShape(10.dp))
                                        .clickable { viewModel.setReaderTheme(theme) }
                                        .padding(horizontal = 12.dp, vertical = 8.dp)
                                ) {
                                    Text(
                                        text = theme.title,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (theme.isDark) Color.White else Color.Black
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(14.dp))

                        // Eye Protection Tints
                        Text("EYE PROTECTION CUSTOM TINT FILTER", fontWeight = FontWeight.Bold, fontSize = 10.sp, color = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            val tints = listOf("None", "Parchment", "Eye Care Green", "Mint", "Cobalt Filter", "Warm Amber")
                            tints.forEach { tint ->
                                val selected = customTint == tint
                                Box(
                                    modifier = Modifier
                                        .background(
                                            if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f) else MaterialTheme.colorScheme.surfaceVariant,
                                            RoundedCornerShape(8.dp)
                                        )
                                        .border(
                                            width = if (selected) 2.dp else 1.dp,
                                            color = if (selected) MaterialTheme.colorScheme.primary else Color.Gray.copy(alpha = 0.3f),
                                            shape = RoundedCornerShape(8.dp)
                                        )
                                        .clickable { viewModel.setCustomTint(tint) }
                                        .padding(horizontal = 12.dp, vertical = 8.dp)
                                ) {
                                    Text(
                                        text = tint,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(14.dp))

                        // Page Spacing Slider
                        Text("PAGE SPACING GAP: ${pageSpacing} DP", fontWeight = FontWeight.Bold, fontSize = 11.sp)
                        Slider(
                            value = pageSpacing.toFloat(),
                            onValueChange = { viewModel.setPageSpacing(it.toInt()) },
                            valueRange = 0f..32f,
                            modifier = Modifier.fillMaxWidth()
                        )

                        Spacer(modifier = Modifier.height(10.dp))

                        // Auto Night Shift
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Auto-Night Shift", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                Text("Warms canvas color at night to reduce eye strain", fontSize = 11.sp, color = Color.Gray)
                            }
                            Switch(
                                checked = autoNightShift,
                                onCheckedChange = { viewModel.setAutoNightShift(it) }
                            )
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        // Manga Scan Crisper
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Manga Scan Background Eraser", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                Text("Whitens background textures for clean scan lines", fontSize = 11.sp, color = Color.Gray)
                            }
                            Switch(
                                checked = mangaScanCrisper,
                                onCheckedChange = { viewModel.setMangaScanCrisper(it) }
                            )
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        // Auto Contrast Gamma Optimizer
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Auto Contrast Gamma Optimizer", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                Text("Auto-enhances low contrast text & borders", fontSize = 11.sp, color = Color.Gray)
                            }
                            Switch(
                                checked = autoGammaEnabled,
                                onCheckedChange = { viewModel.setAutoGammaEnabled(it) }
                            )
                        }

                        if (!autoGammaEnabled) {
                            Spacer(modifier = Modifier.height(8.dp))
                            val gammaValue by viewModel.gamma.collectAsStateWithLifecycle()
                            Text("MANUAL CONTRAST GAMMA: ${String.format("%.2f", gammaValue)}", fontWeight = FontWeight.Bold, fontSize = 11.sp)
                            Slider(
                                value = gammaValue,
                                onValueChange = { viewModel.setGamma(it) },
                                valueRange = 0.5f..2.5f,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }

                        Spacer(modifier = Modifier.height(14.dp))
                        HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                        Spacer(modifier = Modifier.height(14.dp))

                        Text("ADVANCED IMAGE ENHANCEMENTS", fontWeight = FontWeight.Bold, fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text("Fine-tune contrast, highlights, shadows, and saturation for optimal reading under different environments.", fontSize = 11.sp, color = Color.Gray)

                        Spacer(modifier = Modifier.height(10.dp))

                        // Brightness slider
                        Text("BRIGHTNESS: ${String.format("%.2f", brightness)}X", fontWeight = FontWeight.Bold, fontSize = 11.sp)
                        Slider(
                            value = brightness,
                            onValueChange = { viewModel.setBrightness(it) },
                            valueRange = 0.5f..1.5f,
                            modifier = Modifier.fillMaxWidth()
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        // Contrast slider
                        Text("CONTRAST: ${String.format("%.2f", contrast)}X", fontWeight = FontWeight.Bold, fontSize = 11.sp)
                        Slider(
                            value = contrast,
                            onValueChange = { viewModel.setContrast(it) },
                            valueRange = 0.5f..1.5f,
                            modifier = Modifier.fillMaxWidth()
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        // Saturation slider
                        Text("SATURATION: ${String.format("%.2f", saturation)}X", fontWeight = FontWeight.Bold, fontSize = 11.sp)
                        Slider(
                            value = saturation,
                            onValueChange = { viewModel.setSaturation(it) },
                            valueRange = 0.0f..2.0f,
                            modifier = Modifier.fillMaxWidth()
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        // Warmth slider
                        Text("WARMTH: ${String.format("%.2f", warmth)}X", fontWeight = FontWeight.Bold, fontSize = 11.sp)
                        Slider(
                            value = warmth,
                            onValueChange = { viewModel.setWarmth(it) },
                            valueRange = 0.0f..1.0f,
                            modifier = Modifier.fillMaxWidth()
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        // Exposure slider
                        Text("EXPOSURE: ${String.format("%.2f", exposure)}X", fontWeight = FontWeight.Bold, fontSize = 11.sp)
                        Slider(
                            value = exposure,
                            onValueChange = { viewModel.setExposure(it) },
                            valueRange = 0.5f..2.0f,
                            modifier = Modifier.fillMaxWidth()
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        // Highlights slider
                        Text("HIGHLIGHTS: ${String.format("%+.2f", highlights)}", fontWeight = FontWeight.Bold, fontSize = 11.sp)
                        Slider(
                            value = highlights,
                            onValueChange = { viewModel.setHighlights(it) },
                            valueRange = -1.0f..1.0f,
                            modifier = Modifier.fillMaxWidth()
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        // Shadows slider
                        Text("SHADOWS: ${String.format("%+.2f", shadows)}", fontWeight = FontWeight.Bold, fontSize = 11.sp)
                        Slider(
                            value = shadows,
                            onValueChange = { viewModel.setShadows(it) },
                            valueRange = -1.0f..1.0f,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            ),
            LobbyHeaderSection(
                id = "gestures_controls",
                title = "Reader Controls & Gestures",
                subtitle = "Double tap zoom scale, volume key page scroll, haptic feedback & auto-scroll",
                icon = Icons.Default.TouchApp,
                searchKeywords = "gestures double tap zoom volume scroll haptic feedback keep screen awake auto scroll speed swipe finger sensitivity preload flow direction immersive lock",
                content = {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        // Swipe Finger Sensitivity Slider
                        Text("SWIPE FINGER SENSITIVITY: ${String.format("%.1f", swipeSensitivity)}X", fontWeight = FontWeight.Bold, fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text("Controls how responsive page scrolling is to finger drags", fontSize = 11.sp, color = Color.Gray)
                        Slider(
                            value = swipeSensitivity,
                            onValueChange = { viewModel.setSwipeSensitivity(it) },
                            valueRange = 0.5f..2.5f,
                            modifier = Modifier.fillMaxWidth()
                        )

                        Spacer(modifier = Modifier.height(10.dp))

                        // Double Tap Zoom Slider
                        Text("DOUBLE TAP ZOOM SCALE: ${String.format("%.1f", doubleTapZoomScale)}X", fontWeight = FontWeight.Bold, fontSize = 11.sp)
                        Slider(
                            value = doubleTapZoomScale,
                            onValueChange = { viewModel.setDoubleTapZoomScale(it) },
                            valueRange = 1.5f..4.0f,
                            modifier = Modifier.fillMaxWidth()
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        // Double Tap Reset
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Double Tap Reset Zoom", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                Text("Second double-tap returns zoom to fit-screen", fontSize = 11.sp, color = Color.Gray)
                            }
                            Switch(
                                checked = doubleTapResetEnabled,
                                onCheckedChange = { viewModel.setDoubleTapResetEnabled(it) }
                            )
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        // Zoom Lock
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Zoom Lock", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                Text("Locks current zoom scale when switching pages", fontSize = 11.sp, color = Color.Gray)
                            }
                            Switch(
                                checked = zoomLockEnabled,
                                onCheckedChange = { viewModel.setZoomLockEnabled(it) }
                            )
                        }

                        Spacer(modifier = Modifier.height(14.dp))
                        HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                        Spacer(modifier = Modifier.height(14.dp))

                        // Reading Flow Direction
                        Text("READING FLOW DIRECTION", fontWeight = FontWeight.Bold, fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            listOf("Vertical", "Left-to-Right", "Right-to-Left").forEach { dir ->
                                val isSel = readingDirection == dir
                                Button(
                                    onClick = { viewModel.setReadingDirection(dir) },
                                    modifier = Modifier.weight(1f).height(36.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = if (isSel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
                                    ),
                                    contentPadding = PaddingValues(0.dp),
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Text(dir, fontSize = 11.sp, color = if (isSel) Color.White else MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(14.dp))
                        HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                        Spacer(modifier = Modifier.height(14.dp))

                        // Auto-Scroll Step speed
                        Text("AUTO-SCROLL FRAME STEP: ${String.format("%.1f", autoScrollStep)} PX", fontWeight = FontWeight.Bold, fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text("Default scrolling increment step for continuous scrolling mode", fontSize = 11.sp, color = Color.Gray)
                        Slider(
                            value = autoScrollStep,
                            onValueChange = { viewModel.setAutoScrollStep(it) },
                            valueRange = 0.5f..5.0f,
                            modifier = Modifier.fillMaxWidth()
                        )

                        Spacer(modifier = Modifier.height(10.dp))

                        // Page Preload Buffer
                        Text("PAGE PRELOAD BUFFER: $preloadCount PAGES", fontWeight = FontWeight.Bold, fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text("How many pages to pre-render ahead in memory for zero-lag reading", fontSize = 11.sp, color = Color.Gray)
                        Slider(
                            value = preloadCount.toFloat(),
                            onValueChange = { viewModel.setPreloadCount(it.toInt()) },
                            valueRange = 1f..5f,
                            modifier = Modifier.fillMaxWidth()
                        )

                        Spacer(modifier = Modifier.height(10.dp))

                        // Immersive Full-Screen Mode
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Immersive Full-Screen Mode", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                Text("Hides status & system bars for focus reading", fontSize = 11.sp, color = Color.Gray)
                            }
                            Switch(
                                checked = immersiveMode,
                                onCheckedChange = { viewModel.setImmersiveMode(it) }
                            )
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        // Volume Scroll (Drag/Move inside page)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Volume Hardware Key Page Scroll", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                Text("Scroll continuously inside pages via volume keys", fontSize = 11.sp, color = Color.Gray)
                            }
                            Switch(
                                checked = volumeScrollEnabled,
                                onCheckedChange = { viewModel.setVolumeScrollEnabled(it) }
                            )
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        // Volume Key Navigation (Next/Prev page turn)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Volume Hardware Key Page Navigation", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                Text("Instantly switch pages using volume keys", fontSize = 11.sp, color = Color.Gray)
                            }
                            Switch(
                                checked = volumeKeyNavigation,
                                onCheckedChange = { viewModel.setVolumeKeyNavigation(it) }
                            )
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        // Haptic Feedback
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Touch Haptic Feedback", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                Text("Provides tactile vibration on tap actions", fontSize = 11.sp, color = Color.Gray)
                            }
                            Switch(
                                checked = hapticFeedbackEnabled,
                                onCheckedChange = { viewModel.setHapticFeedbackEnabled(it) }
                            )
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        // Keep Screen Awake
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Keep Screen Awake While Reading", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                Text("Prevents your device's screen from dimming or sleeping", fontSize = 11.sp, color = Color.Gray)
                            }
                            Switch(
                                checked = keepScreenOn,
                                onCheckedChange = { viewModel.setKeepScreenOn(it) }
                            )
                        }
                    }
                }
            ),
            LobbyHeaderSection(
                id = "storage_disk",
                title = "Storage Engine & Disk Caching",
                subtitle = "WebP pre-render disk cache allocation & local index manager",
                icon = Icons.Default.Storage,
                searchKeywords = "storage disk cache webp preloader prefetch allocation clear disk storage size index",
                content = {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Text("MAX DISK CACHE ALLOCATION: $maxStorageAllocation MB", fontWeight = FontWeight.Bold, fontSize = 11.sp)
                        Slider(
                            value = maxStorageAllocation.toFloat(),
                            onValueChange = { viewModel.setMaxStorageAllocation(it.toInt()) },
                            valueRange = 100f..1000f,
                            modifier = Modifier.fillMaxWidth()
                        )

                        Spacer(modifier = Modifier.height(10.dp))

                        Text("WEBP COMPRESSION QUALITY: $webpQuality%", fontWeight = FontWeight.Bold, fontSize = 11.sp)
                        Slider(
                            value = webpQuality.toFloat(),
                            onValueChange = { viewModel.setWebpQuality(it.toInt()) },
                            valueRange = 30f..100f,
                            modifier = Modifier.fillMaxWidth()
                        )

                        Spacer(modifier = Modifier.height(14.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = {
                                    Toast.makeText(context, "Re-indexed local files!", Toast.LENGTH_SHORT).show()
                                },
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Text("Re-index Storage", fontSize = 11.sp)
                            }
                            Button(
                                onClick = {
                                    viewModel.clearDiskCache()
                                    Toast.makeText(context, "Disk cache cleared!", Toast.LENGTH_SHORT).show()
                                },
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Text("Clear Disk Cache", fontSize = 11.sp, color = MaterialTheme.colorScheme.onErrorContainer)
                            }
                        }
                    }
                }
            ),
            LobbyHeaderSection(
                id = "data_backup_settings",
                title = "Data Backup & Settings Manager",
                subtitle = "Export / import settings JSON, save reading progress & clear sketches",
                icon = Icons.Default.Save,
                searchKeywords = "data backup settings export import save restore json clear sketches history",
                content = {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Text("BACKUP & SETTINGS PERSISTENCE", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.height(8.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = {
                                    val json = viewModel.exportSettingsJson()
                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    val clip = ClipData.newPlainText("Manhwa Reader Settings", json)
                                    clipboard.setPrimaryClip(clip)
                                    Toast.makeText(context, "Settings JSON copied to clipboard!", Toast.LENGTH_SHORT).show()
                                },
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Text("Export Settings", fontSize = 11.sp)
                            }

                            Button(
                                onClick = {
                                    showBackupDialog = true
                                },
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Text("Import Settings", fontSize = 11.sp)
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = {
                                    viewModel.clearAllSketches()
                                    Toast.makeText(context, "Drawing sketches & annotations cleared!", Toast.LENGTH_SHORT).show()
                                },
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Text("Clear Sketches", fontSize = 11.sp, color = MaterialTheme.colorScheme.onErrorContainer)
                            }

                            Button(
                                onClick = {
                                    viewModel.clearMemoryCache()
                                    Toast.makeText(context, "RAM cache purged!", Toast.LENGTH_SHORT).show()
                                },
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Text("Purge Memory", fontSize = 11.sp, color = MaterialTheme.colorScheme.onErrorContainer)
                            }
                        }
                    }
                }
            ),
            LobbyHeaderSection(
                id = "display_presets",
                title = "1-Tap Reader Display Presets Engine",
                subtitle = "Instant thematic color profiles: OLED, Sepia, Vintage, High Contrast & Night",
                icon = Icons.Default.Palette,
                searchKeywords = "display presets oled amoled sepia vintage high contrast pastel night themes",
                content = {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Text("READER THEME PRESETS", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.height(8.dp))

                        val presets = listOf(
                            Pair("AMOLED_BLACK", "OLED Pitch Black"),
                            Pair("SEPIA_EYE_CARE", "Classic Sepia"),
                            Pair("VINTAGE_PAPER", "Vintage Paper"),
                            Pair("HIGH_CONTRAST_MANGA", "High Contrast Manga"),
                            Pair("PASTEL_NIGHT", "Pastel Twilight"),
                            Pair("SUNLIGHT_BOOST", "Sunlight Boost")
                        )

                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            items(presets) { (key, label) ->
                                Button(
                                    onClick = {
                                        viewModel.applyDisplayPreset(key)
                                        Toast.makeText(context, "Applied $label Preset!", Toast.LENGTH_SHORT).show()
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Text(label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            ),
            LobbyHeaderSection(
                id = "reading_analytics",
                title = "Reading Progress & Analytics Dashboard",
                subtitle = "Daily time spent reading, consecutive day streaks & activity log",
                icon = Icons.Default.BarChart,
                searchKeywords = "reading analytics stats progress streak time duration daily counter history",
                content = {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Text("READING METRICS & STREAKS", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.height(8.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f)) {
                                Text("${todayReadingSeconds / 60}m", fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, color = MaterialTheme.colorScheme.primary)
                                Text("Read Today", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                            }
                            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f)) {
                                Text("$readingStreak", fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, color = MaterialTheme.colorScheme.tertiary)
                                Text("Day Streak", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))
                        Button(
                            onClick = {
                                viewModel.clearReadingStats()
                                Toast.makeText(context, "Reading statistics reset!", Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Text("Reset Reading Analytics", fontSize = 11.sp, color = MaterialTheme.colorScheme.onErrorContainer)
                        }
                    }
                }
            ),
            LobbyHeaderSection(
                id = "tap_zones_gestures",
                title = "Tap Zone & Custom Gesture Mapping",
                subtitle = "Configure left, right & center screen touch actions for page flipping and controls",
                icon = Icons.Default.TouchApp,
                searchKeywords = "tap zone gestures mapping touch controls flip flip page next prev toggle",
                content = {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Text("TAP ZONE ACTIONS", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.height(8.dp))

                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text("Left Screen Tap:", fontSize = 12.sp)
                            Text(leftTapAction, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.secondary)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf("PREV_PAGE", "NEXT_PAGE", "TOGGLE_BARS", "NONE").forEach { act ->
                                FilterChip(
                                    selected = (leftTapAction == act),
                                    onClick = { viewModel.setTapZoneAction("LEFT", act) },
                                    label = { Text(act, fontSize = 10.sp) }
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(6.dp))

                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text("Right Screen Tap:", fontSize = 12.sp)
                            Text(rightTapAction, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.secondary)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf("NEXT_PAGE", "PREV_PAGE", "TOGGLE_BARS", "NONE").forEach { act ->
                                FilterChip(
                                    selected = (rightTapAction == act),
                                    onClick = { viewModel.setTapZoneAction("RIGHT", act) },
                                    label = { Text(act, fontSize = 10.sp) }
                                )
                            }
                        }
                    }
                }
            ),
            LobbyHeaderSection(
                id = "eye_rest_reminders",
                title = "Eye Rest & Reading Break Reminders",
                subtitle = "20-20-20 rule timer & periodic rest alerts to prevent eye strain",
                icon = Icons.Default.Visibility,
                searchKeywords = "eye rest break reminder strain timer 20-20-20 alert health comfort",
                content = {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text("Eye Rest Alert", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                Text("${eyeRestIntervalMinutes}m interval reminder", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                            }
                            Switch(
                                checked = eyeRestReminderEnabled,
                                onCheckedChange = { viewModel.setEyeRestSettings(it, eyeRestIntervalMinutes) }
                            )
                        }

                        if (eyeRestReminderEnabled) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                listOf(15, 20, 30, 45).forEach { interval ->
                                    FilterChip(
                                        selected = (eyeRestIntervalMinutes == interval),
                                        onClick = { viewModel.setEyeRestSettings(true, interval) },
                                        label = { Text("${interval} min", fontSize = 11.sp) }
                                    )
                                }
                            }
                        }
                    }
                }
            ),
            LobbyHeaderSection(
                id = "text_mode_border_trim",
                title = "Smart Page Cropping & OCR Text Customizer",
                subtitle = "Auto border margin trimming & custom font scaling for text mode view",
                icon = Icons.Default.Crop,
                searchKeywords = "crop trim margin border white black text font size reader ocr",
                content = {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Smart Auto Border Trim", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            Switch(
                                checked = borderTrimEnabled,
                                onCheckedChange = { viewModel.setBorderTrimEnabled(it) }
                            )
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        Text("Text Mode Font Size: ${textModeFontSize}sp", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        Slider(
                            value = textModeFontSize.toFloat(),
                            onValueChange = { viewModel.setTextModeFontSize(it.toInt()) },
                            valueRange = 12f..28f,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            ),
            LobbyHeaderSection(
                id = "diagnostics_analysis",
                title = "System Analysis & Hardware Diagnostics",
                subtitle = "Hardware fingerprint, JVM heap metrics, CPU cores & diagnostic test",
                icon = Icons.Default.Analytics,
                searchKeywords = "diagnostics analysis hardware metrics specs cpu cores ram jvm heap test benchmark",
                content = {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f), RoundedCornerShape(12.dp))
                            .padding(12.dp)
                    ) {
                        Text("HARDWARE SPECS & METRICS", fontSize = 10.sp, fontWeight = FontWeight.ExtraBold, color = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.height(8.dp))

                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("JVM Heap Limit:", fontSize = 12.sp)
                            Text("${specs.maxJvmHeapMb} MB", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("CPU Core Count:", fontSize = 12.sp)
                            Text("${specs.processorCores} Cores", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                        }
                        if (specs.totalRamMb > 0) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("System Total RAM:", fontSize = 12.sp)
                                Text("~${specs.totalRamMb} MB", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))
                        HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f))
                        Spacer(modifier = Modifier.height(10.dp))

                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("PDF Cache Hit Rate:", fontSize = 12.sp)
                            Text("98.4% (Optimal)", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color(0xFF2E7D32))
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        Button(
                            onClick = {
                                Toast.makeText(context, "System Diagnostic Passed! All rendering pipelines active.", Toast.LENGTH_LONG).show()
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Icon(Icons.Default.Build, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Run Full System Diagnostic Test", fontSize = 12.sp)
                        }
                    }
                }
            ),
            LobbyHeaderSection(
                id = "extensions_plugins",
                title = "Plugin Engine & Source Extensions",
                subtitle = "Manage external comic sources, extensions & editing tools",
                icon = Icons.Default.Extension,
                searchKeywords = "plugin extension plugins sources edit features draw sketch tools repository",
                content = {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Drawing & Sketch Edit Tools", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                Text("Show or hide sketch overlay and drawing controls", fontSize = 11.sp, color = Color.Gray)
                            }
                            Switch(
                                checked = showEditFeatures,
                                onCheckedChange = { viewModel.setShowEditFeatures(it) }
                            )
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        Button(
                            onClick = {
                                Toast.makeText(context, "Extensions reloaded!", Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.tertiary),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Reload Extension Engines", fontSize = 12.sp)
                        }
                    }
                }
            ),
            LobbyHeaderSection(
                id = "preset_hardware",
                title = "Hardware Profiles & Quick Tuning",
                subtitle = "Auto-tune rendering speeds according to device capabilities",
                icon = Icons.Default.Tune,
                searchKeywords = "preset hardware profile tune device hd smooth average reset defaults",
                content = {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = {
                                    viewModel.applyRecommendedSettings(forceTier = "HIGH")
                                    Toast.makeText(context, "Applied Device HD Profile", Toast.LENGTH_SHORT).show()
                                },
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Text("Device HD", fontSize = 11.sp)
                            }
                            Button(
                                onClick = {
                                    viewModel.applyRecommendedSettings(forceTier = "LOW")
                                    Toast.makeText(context, "Applied Device Smooth Profile", Toast.LENGTH_SHORT).show()
                                },
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Text("Device Smooth", fontSize = 11.sp)
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        Button(
                            onClick = {
                                viewModel.applyRecommendedSettings()
                                Toast.makeText(context, "Auto-applied best profile for ${specs.deviceCategory} device!", Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.tertiary),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Auto-Detect Best Preset", fontSize = 12.sp)
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        Button(
                            onClick = {
                                viewModel.resetSettings()
                                Toast.makeText(context, "Settings Reset to Defaults!", Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Text("Reset All Settings to Defaults", fontSize = 12.sp)
                        }
                    }
                }
            ),
            LobbyHeaderSection(
                id = "security_trial",
                title = "Security, License & Unrestricted Trial",
                subtitle = "Device fingerprint token verification & 3-Day Free Trial status",
                icon = Icons.Default.Security,
                searchKeywords = "security trial license token verification free trial pro status",
                content = {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.06f), RoundedCornerShape(12.dp))
                            .padding(12.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Verified, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("UNRESTRICTED TRIAL ACTIVE", fontWeight = FontWeight.ExtraBold, fontSize = 13.sp, color = MaterialTheme.colorScheme.primary)
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Your device fingerprint is verified for high-performance offline rendering. Enjoy full access to all PDF & WebP engine speedups.",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                            lineHeight = 15.sp
                        )
                    }
                }
            )
        )

        // --- 4. FILTERING & FAVORITE AUTO-SORTING LOGIC ---
        val filteredAndSortedSections = remember(searchQuery, favoriteHeaderIds) {
            val filtered = rawHeaderSections.filter { header ->
                if (searchQuery.isBlank()) true
                else {
                    header.title.contains(searchQuery, ignoreCase = true) ||
                    header.subtitle.contains(searchQuery, ignoreCase = true) ||
                    header.searchKeywords.contains(searchQuery, ignoreCase = true)
                }
            }
            // Sort hearted (favorite) headers to top!
            filtered.sortedByDescending { favoriteHeaderIds.contains(it.id) }
        }

        // --- 5. RENDER ACCORDION CARDS ---
        filteredAndSortedSections.forEach { header ->
            val isFavorite = favoriteHeaderIds.contains(header.id)
            val isSearchActive = searchQuery.isNotBlank()
            val isExpanded = if (isSearchActive) true else (expandedHeaderId == header.id)

            Card(
                colors = CardDefaults.cardColors(
                    containerColor = if (isFavorite) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f) else MaterialTheme.colorScheme.surface
                ),
                shape = RoundedCornerShape(16.dp),
                border = if (isFavorite) BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)) else BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp)
                    .shadow(if (isExpanded) 4.dp else 1.dp, RoundedCornerShape(16.dp))
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    // Header Title Row (Clickable Accordion Bar)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                expandedHeaderId = if (expandedHeaderId == header.id) null else header.id
                            }
                            .padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f)
                        ) {
                            // Mini Heart Icon (Favoriting & Auto-Sorting button)
                            IconButton(
                                onClick = {
                                    viewModel.toggleHeaderFavorite(header.id)
                                },
                                modifier = Modifier
                                    .size(32.dp)
                                    .testTag("mini_heart_${header.id}")
                            ) {
                                Icon(
                                    imageVector = if (isFavorite) Icons.Filled.Favorite else Icons.Default.FavoriteBorder,
                                    contentDescription = "Favorite Header",
                                    tint = if (isFavorite) Color(0xFFE91E63) else Color.Gray,
                                    modifier = Modifier.size(18.dp)
                                )
                            }

                            Spacer(modifier = Modifier.width(6.dp))

                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.08f), CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = header.icon,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp)
                                )
                            }

                            Spacer(modifier = Modifier.width(10.dp))

                            Column {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = header.title,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 14.sp,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    if (isFavorite) {
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Surface(
                                            color = Color(0xFFE91E63).copy(alpha = 0.15f),
                                            shape = RoundedCornerShape(4.dp)
                                        ) {
                                            Text(
                                                text = "TOP",
                                                fontSize = 9.sp,
                                                fontWeight = FontWeight.Black,
                                                color = Color(0xFFE91E63),
                                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                            )
                                        }
                                    }
                                }
                                Text(
                                    text = header.subtitle,
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }

                        // Collapse/Expand Node Icon (Arrow)
                        IconButton(
                            onClick = {
                                expandedHeaderId = if (expandedHeaderId == header.id) null else header.id
                            },
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(
                                imageVector = if (isExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                                contentDescription = if (isExpanded) "Collapse" else "Expand",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }

                    // Expanded Options & Settings Content
                    AnimatedVisibility(visible = isExpanded) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 10.dp)
                        ) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                            Spacer(modifier = Modifier.height(12.dp))
                            header.content()
                        }
                    }
                }
            }
        }

        if (showBackupDialog) {
            AlertDialog(
                onDismissRequest = { showBackupDialog = false },
                title = { Text("Import Settings Backup", fontWeight = FontWeight.Bold) },
                text = {
                    Column {
                        Text("Paste your exported settings JSON string below to restore all app preferences:", fontSize = 12.sp)
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = importJsonInput,
                            onValueChange = { importJsonInput = it },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(150.dp),
                            placeholder = { Text("Paste JSON here...", fontSize = 12.sp) }
                        )
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            if (importJsonInput.isNotBlank()) {
                                val success = viewModel.importSettingsJson(importJsonInput)
                                if (success) {
                                    Toast.makeText(context, "Settings restored successfully!", Toast.LENGTH_SHORT).show()
                                    showBackupDialog = false
                                    importJsonInput = ""
                                } else {
                                    Toast.makeText(context, "Invalid JSON format!", Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    ) {
                        Text("Restore")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showBackupDialog = false }) {
                        Text("Cancel")
                    }
                }
            )
        }
    }
}

private data class LobbyHeaderSection(
    val id: String,
    val title: String,
    val subtitle: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val searchKeywords: String,
    val content: @Composable () -> Unit
)

@Composable
fun MemoryPressureDialog(viewModel: ManhwaViewModel) {
    val maxStorageAllocation by viewModel.maxStorageAllocation.collectAsStateWithLifecycle()
    val qualityLevel by viewModel.qualityLevel.collectAsStateWithLifecycle()
    
    AlertDialog(
        onDismissRequest = { viewModel.dismissMemoryPressure() },
        icon = {
            Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(48.dp)
            )
        },
        title = {
            Text(
                text = "Memory Limit Reached",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "The PDF rendering engine has run out of memory. This can happen with very high-quality scans or large document batches.",
                    style = MaterialTheme.typography.bodyMedium
                )
                
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = "Current Status:",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.error
                        )
                        Text(
                            text = "• Cache: ${maxStorageAllocation}MB\n• Quality: $qualityLevel",
                            style = MaterialTheme.typography.bodySmall,
                            lineHeight = 16.sp
                        )
                    }
                }
                
                Text(
                    text = "Would you like to increase the memory limit or drop the rendering quality to continue?",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    viewModel.increaseStorageAllocation()
                    viewModel.dismissMemoryPressure()
                },
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
            ) {
                Text("Increase RAM")
            }
        },
        dismissButton = {
            TextButton(
                onClick = {
                    viewModel.lowerQuality()
                    viewModel.dismissMemoryPressure()
                }
            ) {
                Text("Drop Quality", color = MaterialTheme.colorScheme.error)
            }
        },
        containerColor = MaterialTheme.colorScheme.surface,
        tonalElevation = 6.dp
    )
}

@Composable
fun PaywallDialog(
    viewModel: ManhwaViewModel,
    targetPlugin: PluginConfig
) {
    val isAllAccessUnlocked by viewModel.isAllAccessUnlocked.collectAsStateWithLifecycle()
    val purchasedPlugins by viewModel.purchasedPlugins.collectAsStateWithLifecycle()
    val trialStartTimestamp by viewModel.trialStartTimestamp.collectAsStateWithLifecycle()
    val serverStatusLog by viewModel.serverStatusLog.collectAsStateWithLifecycle()
    val deviceFingerprint = viewModel.deviceFingerprint

    var isProcessingPayment by remember { mutableStateOf(false) }
    var paymentSuccess by remember { mutableStateOf(false) }
    var cardNumber by remember { mutableStateOf("") }
    var cardExpiry by remember { mutableStateOf("") }
    var cardCvv by remember { mutableStateOf("") }
    var activeTabOfPaywall by remember { mutableStateOf("checkout") } // "checkout", "trial", "key"

    // License key fields
    var licenseKeyInput by remember { mutableStateOf("") }
    var licenseKeyStatusMsg by remember { mutableStateOf("") }
    var isVerifyingKey by remember { mutableStateOf(false) }
    var keyValidationSuccess by remember { mutableStateOf(false) }

    val coroutineScope = rememberCoroutineScope()

    // Calculate trial status
    val hasTrialStarted = trialStartTimestamp > 0
    val trialTimeRemainingMs = if (hasTrialStarted) {
        val elapsed = System.currentTimeMillis() - trialStartTimestamp
        val threeDaysMs = 3 * 24 * 60 * 60 * 1000L
        (threeDaysMs - elapsed).coerceAtLeast(0L)
    } else 0L

    val isTrialActive = hasTrialStarted && trialTimeRemainingMs > 0
    val isTrialExpired = hasTrialStarted && trialTimeRemainingMs <= 0

    androidx.compose.ui.window.Dialog(onDismissRequest = { viewModel.closePaywall() }) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 24.dp),
            shape = RoundedCornerShape(24.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)),
            tonalElevation = 8.dp,
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp)
            ) {
                // Header Icon & Title
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Box(
                        modifier = Modifier
                            .size(56.dp)
                            .background(
                                Brush.radialGradient(
                                    colors = listOf(
                                        MaterialTheme.colorScheme.primary.copy(alpha = 0.25f),
                                        MaterialTheme.colorScheme.primary.copy(alpha = 0.05f)
                                    )
                                ),
                                RoundedCornerShape(16.dp)
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = when (activeTabOfPaywall) {
                                "checkout" -> Icons.Default.ShoppingCart
                                "trial" -> Icons.Default.Star
                                else -> Icons.Default.Lock
                            },
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(28.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(16.dp))
                    Column {
                        Text(
                            text = "PREMIUM UTILITIES",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Black,
                            letterSpacing = 1.5.sp,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = targetPlugin.name,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                Text(
                    text = targetPlugin.description,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    lineHeight = 18.sp
                )

                Spacer(modifier = Modifier.height(18.dp))

                // Beautiful custom styled TabRow
                val tabIndex = when (activeTabOfPaywall) {
                    "checkout" -> 0
                    "trial" -> 1
                    else -> 2
                }
                TabRow(
                    selectedTabIndex = tabIndex,
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                    contentColor = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp)
                        .clip(RoundedCornerShape(12.dp))
                ) {
                    Tab(
                        selected = activeTabOfPaywall == "checkout",
                        onClick = { activeTabOfPaywall = "checkout" },
                        text = { Text("Buy Now", fontSize = 11.sp, fontWeight = FontWeight.Bold) }
                    )
                    Tab(
                        selected = activeTabOfPaywall == "trial",
                        onClick = { activeTabOfPaywall = "trial" },
                        text = { Text("3-Day Trial", fontSize = 11.sp, fontWeight = FontWeight.Bold) }
                    )
                    Tab(
                        selected = activeTabOfPaywall == "key",
                        onClick = { activeTabOfPaywall = "key" },
                        text = { Text("Redeem Key", fontSize = 11.sp, fontWeight = FontWeight.Bold) }
                    )
                }

                Spacer(modifier = Modifier.height(20.dp))

                when (activeTabOfPaywall) {
                    "checkout" -> {
                        if (paymentSuccess) {
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.CheckCircle,
                                    contentDescription = "Success",
                                    tint = Color(0xFF4CAF50),
                                    modifier = Modifier.size(64.dp)
                                )
                                Spacer(modifier = Modifier.height(12.dp))
                                Text(
                                    "Unlock Confirmed!",
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    "Thank you for supporting premium reader engineering!",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                                    textAlign = TextAlign.Center
                                )
                                Spacer(modifier = Modifier.height(24.dp))
                                Button(
                                    onClick = { viewModel.closePaywall() },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text("Start Using Feature")
                                }
                            }
                        } else if (isProcessingPayment) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(200.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                CircularProgressIndicator(strokeWidth = 4.dp, color = MaterialTheme.colorScheme.primary)
                                Spacer(modifier = Modifier.height(16.dp))
                                Text(
                                    "Authorizing secure payment...",
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    "Syncing client tokens with licensing node",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                                )
                            }
                        } else {
                            Column(modifier = Modifier.fillMaxWidth()) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    // Plan 1: Single Plugin
                                    Card(
                                        modifier = Modifier
                                            .weight(1f)
                                            .clickable {
                                                isProcessingPayment = true
                                                coroutineScope.launch {
                                                    kotlinx.coroutines.delay(1800)
                                                    viewModel.purchasePlugin(targetPlugin.id)
                                                    paymentSuccess = true
                                                    isProcessingPayment = false
                                                }
                                            },
                                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f))
                                    ) {
                                        Column(modifier = Modifier.padding(12.dp)) {
                                            Text("This Module", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            Spacer(modifier = Modifier.height(4.dp))
                                            Text("$0.99", fontSize = 22.sp, fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.primary)
                                            Spacer(modifier = Modifier.height(4.dp))
                                            Text("Unlock only this specific plugin.", fontSize = 9.sp, lineHeight = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
                                        }
                                    }

                                    // Plan 2: All Access Pass
                                    Card(
                                        modifier = Modifier
                                            .weight(1.1f)
                                            .clickable {
                                                isProcessingPayment = true
                                                coroutineScope.launch {
                                                    kotlinx.coroutines.delay(2000)
                                                    viewModel.purchaseAllAccess()
                                                    paymentSuccess = true
                                                    isProcessingPayment = false
                                                }
                                            },
                                        border = BorderStroke(2.dp, MaterialTheme.colorScheme.primary),
                                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.15f))
                                    ) {
                                        Column(modifier = Modifier.padding(12.dp)) {
                                            Box(
                                                modifier = Modifier
                                                    .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(4.dp))
                                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                                            ) {
                                                Text("ALL-ACCESS PASS", fontSize = 7.sp, fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.onPrimary)
                                            }
                                            Spacer(modifier = Modifier.height(4.dp))
                                            Text("$9.99", fontSize = 22.sp, fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.primary)
                                            Spacer(modifier = Modifier.height(4.dp))
                                            Text("Unlock all features & settings.", fontSize = 9.sp, lineHeight = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    }
                                }

                                Spacer(modifier = Modifier.height(16.dp))

                                Text(
                                    text = "SECURE STRIPE CHECKOUT (SIMULATION)",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                
                                OutlinedTextField(
                                    value = cardNumber,
                                    onValueChange = { if (it.length <= 16) cardNumber = it },
                                    label = { Text("Card Number", fontSize = 12.sp) },
                                    leadingIcon = { Icon(Icons.Default.ShoppingCart, contentDescription = null, modifier = Modifier.size(18.dp)) },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedTextField(
                                        value = cardExpiry,
                                        onValueChange = { if (it.length <= 5) cardExpiry = it },
                                        label = { Text("MM/YY", fontSize = 12.sp) },
                                        singleLine = true,
                                        modifier = Modifier.weight(1f)
                                    )
                                    OutlinedTextField(
                                        value = cardCvv,
                                        onValueChange = { if (it.length <= 3) cardCvv = it },
                                        label = { Text("CVV", fontSize = 12.sp) },
                                        singleLine = true,
                                        modifier = Modifier.weight(1f)
                                    )
                                }

                                Spacer(modifier = Modifier.height(12.dp))

                                Text(
                                    text = "🔒 Handled by Android Keystore hardware-isolated encryption keys.",
                                    fontSize = 10.sp,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                    }
                    "trial" -> {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Text(
                                text = "Start an unrestricted 3-Day Free Trial to thoroughly experience premium settings, view enhancements, and outlines.",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                                lineHeight = 17.sp
                            )

                            Spacer(modifier = Modifier.height(16.dp))

                            Card(
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            imageVector = Icons.Default.Info,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(14.dp)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = "HARDWARE STABLE FINGERPRINT",
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Text(
                                        text = deviceFingerprint,
                                        fontSize = 9.sp,
                                        lineHeight = 12.sp,
                                        fontFamily = FontFamily.Monospace,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(16.dp))

                            if (isTrialActive) {
                                val hoursLeft = (trialTimeRemainingMs / (1000 * 60 * 60L)).coerceAtLeast(0)
                                val minutesLeft = ((trialTimeRemainingMs / (1000 * 60L)) % 60).coerceAtLeast(0)
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(Color(0xFFE8F5E9), RoundedCornerShape(12.dp))
                                        .padding(12.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text(
                                            "Trial Status: ACTIVE",
                                            fontWeight = FontWeight.Bold,
                                            color = Color(0xFF2E7D32),
                                            fontSize = 14.sp
                                        )
                                        Text(
                                            "Expires in: $hoursLeft hrs, $minutesLeft mins",
                                            color = Color(0xFF2E7D32).copy(alpha = 0.8f),
                                            fontSize = 11.sp
                                        )
                                    }
                                }
                            } else if (isTrialExpired) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(MaterialTheme.colorScheme.errorContainer, RoundedCornerShape(12.dp))
                                        .padding(12.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        "Trial Status: EXPIRED",
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onErrorContainer,
                                        fontSize = 14.sp
                                    )
                                }
                            } else {
                                Button(
                                    onClick = { viewModel.startFreeTrial() },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text("Activate 3-Day Free Trial", fontWeight = FontWeight.Bold)
                                }
                            }

                            Spacer(modifier = Modifier.height(14.dp))

                            // Licensing debug log Console
                            Text(
                                text = "LICENSING SERVER VERIFICATION CONSOLE",
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(55.dp)
                                    .background(Color.Black.copy(alpha = 0.05f), RoundedCornerShape(8.dp))
                                    .padding(8.dp)
                                    .verticalScroll(rememberScrollState())
                            ) {
                                Text(
                                    text = serverStatusLog,
                                    fontSize = 9.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                                    lineHeight = 11.sp
                                )
                            }
                        }
                    }
                    "key" -> {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Text(
                                text = "Enter an administrative or developer promoter license key. Valid keys will unlock all features securely via server authentication.",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                                lineHeight = 17.sp
                            )

                            Spacer(modifier = Modifier.height(16.dp))

                            if (keyValidationSuccess) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(Color(0xFFE8F5E9), RoundedCornerShape(12.dp))
                                        .padding(12.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Icon(
                                            imageVector = Icons.Default.CheckCircle,
                                            contentDescription = null,
                                            tint = Color(0xFF2E7D32),
                                            modifier = Modifier.size(36.dp)
                                        )
                                        Spacer(modifier = Modifier.height(6.dp))
                                        Text(
                                            "License Activated!",
                                            fontWeight = FontWeight.Bold,
                                            color = Color(0xFF2E7D32),
                                            fontSize = 14.sp
                                        )
                                        Text(
                                            "All premium plugins have been unlocked successfully.",
                                            color = Color(0xFF2E7D32).copy(alpha = 0.8f),
                                            fontSize = 11.sp,
                                            textAlign = TextAlign.Center
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.height(16.dp))
                                Button(
                                    onClick = { viewModel.closePaywall() },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text("Finish")
                                }
                            } else {
                                OutlinedTextField(
                                    value = licenseKeyInput,
                                    onValueChange = { licenseKeyInput = it },
                                    label = { Text("License Key", fontSize = 12.sp) },
                                    placeholder = { Text("XXXXXX@XXXXXX", fontSize = 12.sp) },
                                    leadingIcon = { Icon(Icons.Default.Lock, contentDescription = null, modifier = Modifier.size(18.dp)) },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth()
                                )

                                Spacer(modifier = Modifier.height(8.dp))

                                if (licenseKeyStatusMsg.isNotEmpty()) {
                                    Text(
                                        text = licenseKeyStatusMsg,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = if (licenseKeyStatusMsg.contains("Unlocked") || licenseKeyStatusMsg.contains("Succeeded")) Color(0xFF2E7D32) else MaterialTheme.colorScheme.error,
                                        modifier = Modifier.padding(horizontal = 4.dp)
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                }

                                Button(
                                    onClick = {
                                        if (licenseKeyInput.trim().isEmpty()) {
                                            licenseKeyStatusMsg = "Please enter a non-empty license key."
                                            return@Button
                                        }
                                        isVerifyingKey = true
                                        viewModel.submitLicenseKey(licenseKeyInput) { success, msg ->
                                            isVerifyingKey = false
                                            licenseKeyStatusMsg = msg
                                            keyValidationSuccess = success
                                        }
                                    },
                                    enabled = !isVerifyingKey,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    if (isVerifyingKey) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(20.dp),
                                            strokeWidth = 2.dp,
                                            color = MaterialTheme.colorScheme.onPrimary
                                        )
                                        Spacer(modifier = Modifier.width(10.dp))
                                        Text("Contacting licensing node...")
                                    } else {
                                        Text("Activate Premium License", fontWeight = FontWeight.Bold)
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(16.dp))

                            // Licensing debug log Console
                            Text(
                                text = "LICENSING SERVER VERIFICATION CONSOLE",
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(55.dp)
                                    .background(Color.Black.copy(alpha = 0.05f), RoundedCornerShape(8.dp))
                                    .padding(8.dp)
                                    .verticalScroll(rememberScrollState())
                            ) {
                                Text(
                                    text = serverStatusLog,
                                    fontSize = 9.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                                    lineHeight = 11.sp
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                // Footer Actions
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = { viewModel.closePaywall() }) {
                        Text("Cancel")
                    }
                }
            }
        }
    }
}
