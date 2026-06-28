package com.example.ui

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
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.Bookmark
import com.example.data.Manhwa
import com.example.data.PluginConfig
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // PU Logo Button with Dropdown
                        Box {
                            Button(
                                onClick = { isLogoDropdownExpanded = true },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.primary,
                                    contentColor = MaterialTheme.colorScheme.onPrimary
                                ),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                modifier = Modifier
                                    .height(36.dp)
                                    .testTag("pu_logo_button"),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text(
                                    text = "PU",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 16.sp,
                                    letterSpacing = 1.sp
                                )
                            }

                            DropdownMenu(
                                expanded = isLogoDropdownExpanded,
                                onDismissRequest = { isLogoDropdownExpanded = false }
                            ) {
                                DropdownMenuItem(
                                    text = { Text("Open PDF") },
                                    onClick = {
                                        isLogoDropdownExpanded = false
                                        filePickerLauncher.launch(arrayOf("application/pdf"))
                                    },
                                    leadingIcon = { Icon(Icons.Default.Add, contentDescription = null) }
                                )
                                DropdownMenuItem(
                                    text = { Text("View Library") },
                                    onClick = {
                                        isLogoDropdownExpanded = false
                                        viewModel.selectTabId("library")
                                    },
                                    leadingIcon = { Icon(Icons.Default.Home, contentDescription = null) }
                                )
                                DropdownMenuItem(
                                    text = { Text("Plugins Manager") },
                                    onClick = {
                                        isLogoDropdownExpanded = false
                                        viewModel.openPluginsTab()
                                    },
                                    leadingIcon = { Icon(Icons.Default.Build, contentDescription = null) }
                                )
                                DropdownMenuItem(
                                    text = { Text("Settings") },
                                    onClick = {
                                        isLogoDropdownExpanded = false
                                        viewModel.openSettingsTab()
                                    },
                                    leadingIcon = { Icon(Icons.Default.Settings, contentDescription = null) }
                                )
                                HorizontalDivider()
                                DropdownMenuItem(
                                    text = { Text("About PDF ULTRA") },
                                    onClick = {
                                        isLogoDropdownExpanded = false
                                        showAboutDialog = true
                                    },
                                    leadingIcon = { Icon(Icons.Default.Info, contentDescription = null) }
                                )
                            }
                        }

                        Spacer(modifier = Modifier.width(12.dp))

                        // Scrollable row of open tabs (Up to 3 open tabs max)
                        LazyRow(
                            modifier = Modifier.weight(1f),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            @OptIn(ExperimentalFoundationApi::class)
                            items(tabs) { tab ->
                                val isActive = tab.id == activeTabId
                                Surface(
                                    modifier = Modifier
                                        .height(36.dp)
                                        .combinedClickable(
                                            onClick = { viewModel.selectTabId(tab.id) },
                                            onDoubleClick = {
                                                if (tab.id != "library") {
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
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                ),
                modifier = Modifier.shadow(4.dp)
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when {
                activeTab?.type == TabType.READER && activeTab?.manhwa != null -> {
                    ComicReaderScreen(viewModel = viewModel)
                }
                activeTab?.type == TabType.LIBRARY -> {
                    LibraryScreen(viewModel = viewModel)
                }
                activeTab?.type == TabType.PLUGINS -> {
                    PluginsScreen(viewModel = viewModel)
                }
                activeTab?.type == TabType.SETTINGS -> {
                    SettingsScreen(viewModel = viewModel)
                }
                else -> {
                    LibraryScreen(viewModel = viewModel)
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

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { viewModel.importPdfFile(it) }
    }
    var showDeleteConfirmDialog by remember { mutableStateOf<Manhwa?>(null) }

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
                    Button(
                        onClick = { filePickerLauncher.launch(arrayOf("application/pdf")) },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Default.Add, contentDescription = "Import PDF")
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Import PDF", fontWeight = FontWeight.Bold)
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
                            text = "Offline Chapters (${sortedManhwas.size})",
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

                items(sortedManhwas, key = { it.id }) { manhwa ->
                    ManhwaCardItem(
                        manhwa = manhwa,
                        onOpen = { viewModel.openManhwa(manhwa) },
                        onDelete = { showDeleteConfirmDialog = manhwa }
                    )
                }
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
}

@Composable
fun ManhwaCardItem(
    manhwa: Manhwa,
    onOpen: () -> Unit,
    onDelete: () -> Unit
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
                        "metadata_bookmark" -> Icons.Default.List
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
fun ComicReaderScreen(viewModel: ManhwaViewModel) {
    val activeManhwa by viewModel.activeManhwa.collectAsStateWithLifecycle()
    val plugins by viewModel.allPlugins.collectAsStateWithLifecycle()
    val activeBookmarks by viewModel.activeBookmarks.collectAsStateWithLifecycle()
    val currentPage by viewModel.currentPage.collectAsStateWithLifecycle()

    val isOutlineOpen by viewModel.isOutlineDrawerOpen.collectAsStateWithLifecycle()

    // Color/view enhancements
    val brightness by viewModel.brightness.collectAsStateWithLifecycle()
    val contrast by viewModel.contrast.collectAsStateWithLifecycle()
    val colorMode by viewModel.colorMode.collectAsStateWithLifecycle()
    val hdMode by viewModel.hdModeEnabled.collectAsStateWithLifecycle()

    // Sketch editor
    val drawColor by viewModel.activeDrawColor.collectAsStateWithLifecycle()
    val strokeWidth by viewModel.activeStrokeWidth.collectAsStateWithLifecycle()
    val sketches by viewModel.sketches.collectAsStateWithLifecycle()

    // Verify which plugins are currently enabled
    val isViewEnhancerEnabled = remember(plugins) { plugins.find { it.id == "view_enhancer" }?.enabled == true }
    val isSketchEditorEnabled = remember(plugins) { plugins.find { it.id == "manhwa_editor" }?.enabled == true }
    val isOutlineEnabled = remember(plugins) { plugins.find { it.id == "metadata_bookmark" }?.enabled == true }

    var isDrawModeOn by remember { mutableStateOf(false) }
    var showAddBookmarkDialog by remember { mutableStateOf(false) }
    var bookmarkTitleInput by remember { mutableStateOf("") }

    val lazyListState = rememberLazyListState(
        initialFirstVisibleItemIndex = activeManhwa?.lastReadPage ?: 0,
        initialFirstVisibleItemScrollOffset = activeManhwa?.scrollOffset ?: 0
    )
    val coroutineScope = rememberCoroutineScope()
    var componentWidth by remember { mutableStateOf(1080) }
    var areControlsVisible by remember { mutableStateOf(true) }

    // Advanced zoom and magnifier lens state from ViewModel
    val activeZoomScale by viewModel.activeZoomScale.collectAsStateWithLifecycle()
    val isMagnifierEnabled by viewModel.isMagnifierEnabled.collectAsStateWithLifecycle()
    val zoomLockEnabled by viewModel.zoomLockEnabled.collectAsStateWithLifecycle()
    val lockedZoomLevel by viewModel.lockedZoomLevel.collectAsStateWithLifecycle()

    var zoomScaleTarget by remember { mutableStateOf(1.0f) }
    LaunchedEffect(activeZoomScale) {
        zoomScaleTarget = activeZoomScale
    }
    val animatedZoomScale by androidx.compose.animation.core.animateFloatAsState(
        targetValue = zoomScaleTarget,
        animationSpec = androidx.compose.animation.core.tween(durationMillis = 350, easing = androidx.compose.animation.core.FastOutSlowInEasing),
        label = "SmoothZoom"
    )

    var magnifierPosition by remember { mutableStateOf<Offset?>(null) }
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

    val zoomGestureModifier = if (!isMagnifierEnabled && !isDrawModeOn) {
        Modifier.pointerInput(Unit) {
            detectTransformGestures { _, _, zoom, _ ->
                val newZoom = (zoomScaleTarget * zoom).coerceIn(0.5f, 3.0f)
                zoomScaleTarget = newZoom
                viewModel.setActiveZoomScale(newZoom)
            }
        }
    } else Modifier

    // Chapter navigation position memory restorer (Index + Offset)
    val activeManhwaId = activeManhwa?.id ?: 0L
    LaunchedEffect(activeManhwaId) {
        val lastPage = activeManhwa?.lastReadPage ?: 0
        val lastOffset = activeManhwa?.scrollOffset ?: 0
        if (activeManhwaId > 0 && (lazyListState.firstVisibleItemIndex != lastPage || lazyListState.firstVisibleItemScrollOffset != lastOffset)) {
            try {
                lazyListState.scrollToItem(lastPage, lastOffset)
            } catch (e: Exception) {
                // Ignore any instant scroll conflicts
            }
        }
    }

    // Hands-Free Auto-Scroll system
    val autoScrollSpeed by viewModel.autoScrollSpeed.collectAsStateWithLifecycle()
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
        val pixelsScrolled = Math.abs(indexDiff * 3000 + offsetDiff)
        val velocity = pixelsScrolled.toFloat() / timeDelta.toFloat() // pixels per millisecond

        // Update database with index and offset
        viewModel.setCurrentPageAndOffset(
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

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black) // Perfect backdrop for comics
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

        // Chapter reach auto-load checking
        LaunchedEffect(lazyListState.firstVisibleItemIndex, lazyListState.isScrollInProgress) {
            if (!lazyListState.isScrollInProgress) {
                val hasNext = nextChapter != null
                val totalPages = activeManhwa?.totalPages ?: 0
                val totalListItems = totalPages + (if (prevChapter != null) 1 else 0) + (if (hasNext) 1 else 0)
                val lastVisibleIdx = lazyListState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
                if (hasNext && lastVisibleIdx >= totalListItems - 1) {
                    viewModel.navigateToChapter(nextChapter!!)
                }

                val hasPrev = prevChapter != null
                val firstVisibleIdx = lazyListState.layoutInfo.visibleItemsInfo.firstOrNull()?.index ?: 0
                if (hasPrev && firstVisibleIdx == 0) {
                    viewModel.navigateToChapter(prevChapter!!)
                }
            }
        }

        LazyColumn(
            state = lazyListState,
            userScrollEnabled = !isDrawModeOn, // LOCK scrolling during drawing sessions!
            modifier = Modifier
                .width(with(LocalDensity.current) { (componentWidth * animatedZoomScale).toDp() })
                .fillMaxHeight(),
            verticalArrangement = Arrangement.spacedBy(0.dp) // GAPLESS reading (Mandatory for Manhwa!)
        ) {
            if (prevChapter != null) {
                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color.DarkGray.copy(alpha = 0.4f)),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp)
                            .clickable { viewModel.navigateToChapter(prevChapter) }
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

            val totalPages = activeManhwa?.totalPages ?: 0
            items(totalPages) { pageIdx ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .wrapContentHeight()
                ) {
                    PdfPageItem(
                        pageIndex = pageIdx,
                        targetWidth = componentWidth,
                        zoomScale = animatedZoomScale,
                        viewModel = viewModel,
                        brightness = brightness,
                        contrast = contrast,
                        colorMode = colorMode,
                        onPdfClick = {
                            val currentTime = System.currentTimeMillis()
                            if (currentTime - lastClickTime > 100) {
                                    lastClickTime = currentTime
                                    areControlsVisible = !areControlsVisible
                                }
                            }
                        )

                        // Draw drawing sketch overlay on page
                        if (isSketchEditorEnabled) {
                            DrawingSketchOverlay(
                                pageIndex = pageIdx,
                                sketches = sketches[pageIdx] ?: emptyList(),
                                isDrawModeOn = isDrawModeOn,
                                drawColor = drawColor,
                                strokeWidth = strokeWidth,
                                onDrawFinished = { path ->
                                    viewModel.addDrawPath(pageIdx, path)
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
                                .clickable { viewModel.navigateToChapter(nextChapter) }
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
                isOutlineEnabled = isOutlineEnabled,
                isDrawModeSupported = isSketchEditorEnabled,
                isDrawModeOn = isDrawModeOn,
                onBack = { viewModel.closeManhwa() },
                onToggleOutline = { viewModel.toggleOutlineDrawer() },
                onToggleDrawMode = { isDrawModeOn = !isDrawModeOn }
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
            DrawingControlsBar(
                currentColor = drawColor,
                currentStroke = strokeWidth,
                onColorSelect = { viewModel.setDrawColor(it) },
                onStrokeSelect = { viewModel.setStrokeWidth(it) },
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
                colorMode = colorMode,
                hdMode = hdMode,
                isOutlineEnabled = isOutlineEnabled,
                isMagnifierEnabled = isMagnifierEnabled,
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
                onColorModeChange = { viewModel.setColorMode(it) },
                onToggleHdMode = { viewModel.toggleHdMode() },
                autoScrollSpeed = autoScrollSpeed,
                onAutoScrollSpeedChange = { viewModel.setAutoScrollSpeed(it) },
                canNavigateBack = viewModel.canNavigateBack(),
                canNavigateForward = viewModel.canNavigateForward(),
                onNavigateBack = { viewModel.navigateBack() },
                onNavigateForward = { viewModel.navigateForward() }
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
                        coroutineScope.launch {
                            lazyListState.scrollToItem(pageIdx)
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
    viewModel: ManhwaViewModel,
    brightness: Float,
    contrast: Float,
    colorMode: ManhwaViewModel.ColorMode
) {
    var sliceBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var isRendering by remember { mutableStateOf(true) }

    LaunchedEffect(pageIndex, targetWidth, sliceIndex, sliceHeight, viewModel) {
        isRendering = true
        val bitmap = viewModel.renderPageSlice(pageIndex, targetWidth, sliceIndex, sliceHeight)
        sliceBitmap = bitmap
        isRendering = false
    }

    val sliceY = sliceIndex * sliceHeight
    val actualSliceHeight = (totalHeight - sliceY).coerceAtMost(sliceHeight)
    val sliceWidthToHeightRatio = totalWidth.toFloat() / actualSliceHeight.toFloat()

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(sliceWidthToHeightRatio)
            .background(Color.White)
    ) {
        val bitmap = sliceBitmap
        if (isRendering || bitmap == null) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
                    modifier = Modifier.size(24.dp)
                )
            }
        } else {
            val adjustedMatrix = remember(brightness, contrast, colorMode) {
                getAdjustedColorMatrix(brightness, contrast, colorMode)
            }

            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "Page ${pageIndex + 1} Slice ${sliceIndex + 1}",
                contentScale = ContentScale.FillWidth,
                colorFilter = ColorFilter.colorMatrix(adjustedMatrix),
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
    viewModel: ManhwaViewModel,
    brightness: Float,
    contrast: Float,
    colorMode: ManhwaViewModel.ColorMode,
    onPdfClick: () -> Unit
) {
    val scaleFactor by viewModel.activeScaleFactor.collectAsStateWithLifecycle()
    var aspectRatio by remember { mutableStateOf<Float?>(null) }
    var isLoadingAspect by remember { mutableStateOf(true) }

    LaunchedEffect(pageIndex, viewModel) {
        isLoadingAspect = true
        aspectRatio = viewModel.getPageAspectRatio(pageIndex)
        isLoadingAspect = false
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.White)
            .clickable(onClick = onPdfClick)
            .onGloballyPositioned { }
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
            val totalWidth = (targetWidth * scaleFactor).toInt().coerceAtLeast(400)
            val totalHeight = (totalWidth * aspect).toInt().coerceAtLeast(400)
            val sliceHeight = 3072
            val numSlices = Math.ceil(totalHeight.toDouble() / sliceHeight).toInt().coerceAtLeast(1)

            Column(
                modifier = Modifier.fillMaxWidth()
            ) {
                for (sliceIndex in 0 until numSlices) {
                    PdfPageSliceItem(
                        pageIndex = pageIndex,
                        targetWidth = targetWidth,
                        sliceIndex = sliceIndex,
                        sliceHeight = sliceHeight,
                        totalHeight = totalHeight,
                        totalWidth = totalWidth,
                        viewModel = viewModel,
                        brightness = brightness,
                        contrast = contrast,
                        colorMode = colorMode
                    )
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
    isOutlineEnabled: Boolean,
    isDrawModeSupported: Boolean,
    isDrawModeOn: Boolean,
    onBack: () -> Unit,
    onToggleOutline: () -> Unit,
    onToggleDrawMode: () -> Unit
) {
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
            IconButton(onClick = onBack, modifier = Modifier.testTag("reader_back_button")) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Color.White)
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

            // Sketch Mode button
            if (isDrawModeSupported) {
                IconButton(
                    onClick = onToggleDrawMode,
                    modifier = Modifier
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

            // Bookmarks / Outline button
            if (isOutlineEnabled) {
                IconButton(onClick = onToggleOutline, modifier = Modifier.testTag("outline_drawer_toggle")) {
                    Icon(Icons.Default.Menu, contentDescription = "Outline", tint = Color.White)
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
    onColorSelect: (Color) -> Unit,
    onStrokeSelect: (Float) -> Unit,
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
                Text(
                    "SKETCH SESSION",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 1.sp,
                    color = MaterialTheme.colorScheme.primary
                )

                Row {
                    TextButton(
                        onClick = onClearPage,
                        colors = ButtonDefaults.textButtonColors(contentColor = Color.Red.copy(alpha = 0.8f))
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = "Clear", modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Clear Drawing", fontSize = 12.sp, fontWeight = FontWeight.Bold)
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

// --- HUD Bottom Bar ---
@Composable
fun HUDBottomBar(
    currentPage: Int,
    totalPages: Int,
    isViewEnhancerEnabled: Boolean,
    brightness: Float,
    contrast: Float,
    colorMode: ManhwaViewModel.ColorMode,
    hdMode: Boolean,
    isOutlineEnabled: Boolean,
    isMagnifierEnabled: Boolean,
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
    onColorModeChange: (ManhwaViewModel.ColorMode) -> Unit,
    onToggleHdMode: () -> Unit,
    autoScrollSpeed: Float,
    onAutoScrollSpeedChange: (Float) -> Unit,
    canNavigateBack: Boolean,
    canNavigateForward: Boolean,
    onNavigateBack: () -> Unit,
    onNavigateForward: () -> Unit
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
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color.Black.copy(alpha = 0.95f))
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                ) {
                    Text(
                        "IMAGE ENHANCEMENTS",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 1.sp,
                        color = MaterialTheme.colorScheme.primary
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    // Brightness slider
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Brightness", fontSize = 12.sp, color = Color.LightGray, modifier = Modifier.width(70.dp))
                        Slider(
                            value = brightness,
                            onValueChange = onBrightnessChange,
                            valueRange = 0.5f..1.5f,
                            modifier = Modifier.weight(1f),
                            colors = SliderDefaults.colors(thumbColor = MaterialTheme.colorScheme.primary)
                        )
                        Text(String.format("%.1fx", brightness), fontSize = 11.sp, modifier = Modifier.width(30.dp), textAlign = TextAlign.End)
                    }

                    // Contrast slider
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Contrast", fontSize = 12.sp, color = Color.LightGray, modifier = Modifier.width(70.dp))
                        Slider(
                            value = contrast,
                            onValueChange = onContrastChange,
                            valueRange = 0.5f..1.5f,
                            modifier = Modifier.weight(1f),
                            colors = SliderDefaults.colors(thumbColor = MaterialTheme.colorScheme.primary)
                        )
                        Text(String.format("%.1fx", contrast), fontSize = 11.sp, modifier = Modifier.width(30.dp), textAlign = TextAlign.End)
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

                    Spacer(modifier = Modifier.height(6.dp))
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

            // Zoom & Focus engine control panel
            if (showZoomControls) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color.Black.copy(alpha = 0.95f))
                        .padding(horizontal = 16.dp, vertical = 12.dp)
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

            // Hands-Free Auto-Scroll control panel
            if (showScrollControls) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color.Black.copy(alpha = 0.95f))
                        .padding(horizontal = 16.dp, vertical = 12.dp)
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
                            onValueChange = onAutoScrollSpeedChange,
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
                Row {
                    if (isViewEnhancerEnabled) {
                        IconButton(onClick = { 
                            showEnhancerControls = !showEnhancerControls 
                            if (showEnhancerControls) {
                                showZoomControls = false
                                showScrollControls = false
                            }
                        }) {
                            Icon(
                                Icons.Default.Settings,
                                contentDescription = "Enhance",
                                tint = if (showEnhancerControls) MaterialTheme.colorScheme.primary else Color.White
                            )
                        }
                    }
                    
                    IconButton(onClick = { 
                        showZoomControls = !showZoomControls 
                        if (showZoomControls) {
                            showEnhancerControls = false
                            showScrollControls = false
                        }
                    }) {
                        Icon(
                            Icons.Default.Search,
                            contentDescription = "Zoom & Focus",
                            tint = if (showZoomControls) MaterialTheme.colorScheme.primary else Color.White
                        )
                    }

                    IconButton(onClick = { 
                        showScrollControls = !showScrollControls 
                        if (showScrollControls) {
                            showEnhancerControls = false
                            showZoomControls = false
                        }
                    }) {
                        Icon(
                            imageVector = Icons.Default.PlayArrow,
                            contentDescription = "Auto-Scroll",
                            tint = if (showScrollControls) MaterialTheme.colorScheme.primary else if (autoScrollSpeed > 0f) MaterialTheme.colorScheme.secondary else Color.White
                        )
                    }
                }

                // Page slider and Browser-like Chapter History controls
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = onNavigateBack,
                        enabled = canNavigateBack
                    ) {
                        Icon(
                            imageVector = Icons.Default.ArrowBack,
                            contentDescription = "Prev Chapter History",
                            tint = if (canNavigateBack) Color.White else Color.DarkGray
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
                        enabled = canNavigateForward
                    ) {
                        Icon(
                            imageVector = Icons.Default.ArrowForward,
                            contentDescription = "Next Chapter History",
                            tint = if (canNavigateForward) Color.White else Color.DarkGray
                        )
                    }
                }

                // Chapter Bookmarker button
                if (isOutlineEnabled) {
                    IconButton(onClick = onAddBookmarkClick, modifier = Modifier.testTag("add_bookmark_hud")) {
                        Icon(Icons.Default.Add, contentDescription = "Add Chapter Mark", tint = Color.White)
                    }
                } else {
                    Spacer(modifier = Modifier.width(48.dp))
                }
            }
        }
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
fun getAdjustedColorMatrix(brightness: Float, contrast: Float, mode: ManhwaViewModel.ColorMode): ColorMatrix {
    val scale = contrast * brightness
    val translate = ((1.0f - contrast) * 0.5f + (brightness - 1.0f)) * 255f

    return when (mode) {
        ManhwaViewModel.ColorMode.GRAYSCALE -> {
            val grayMatrix = floatArrayOf(
                0.299f * scale, 0.587f * scale, 0.114f * scale, 0f, translate,
                0.299f * scale, 0.587f * scale, 0.114f * scale, 0f, translate,
                0.299f * scale, 0.587f * scale, 0.114f * scale, 0f, translate,
                0f, 0f, 0f, 1f, 0f
            )
            ColorMatrix(grayMatrix)
        }
        ManhwaViewModel.ColorMode.SEPIA -> {
            val sepiaMatrix = floatArrayOf(
                0.393f * scale, 0.769f * scale, 0.189f * scale, 0f, translate,
                0.349f * scale, 0.686f * scale, 0.168f * scale, 0f, translate,
                0.272f * scale, 0.534f * scale, 0.131f * scale, 0f, translate,
                0f, 0f, 0f, 1f, 0f
            )
            ColorMatrix(sepiaMatrix)
        }
        ManhwaViewModel.ColorMode.INVERTED -> {
            val invScale = -scale
            val invTranslate = scale * 255f + translate
            val invertMatrix = floatArrayOf(
                invScale, 0f, 0f, 0f, invTranslate,
                0f, invScale, 0f, 0f, invTranslate,
                0f, 0f, invScale, 0f, invTranslate,
                0f, 0f, 0f, 1f, 0f
            )
            ColorMatrix(invertMatrix)
        }
        ManhwaViewModel.ColorMode.PROTANOPIA -> {
            val protanopiaMatrix = floatArrayOf(
                0.567f * scale, 0.433f * scale, 0f, 0f, translate,
                0.558f * scale, 0.442f * scale, 0f, 0f, translate,
                0f, 0.242f * scale, 0.758f * scale, 0f, translate,
                0f, 0f, 0f, 1f, 0f
            )
            ColorMatrix(protanopiaMatrix)
        }
        ManhwaViewModel.ColorMode.DEUTERANOPIA -> {
            val deuteranopiaMatrix = floatArrayOf(
                0.625f * scale, 0.375f * scale, 0f, 0f, translate,
                0.7f * scale, 0.3f * scale, 0f, 0f, translate,
                0f, 0.3f * scale, 0.7f * scale, 0f, translate,
                0f, 0f, 0f, 1f, 0f
            )
            ColorMatrix(deuteranopiaMatrix)
        }
        ManhwaViewModel.ColorMode.TRITANOPIA -> {
            val tritanopiaMatrix = floatArrayOf(
                0.95f * scale, 0.05f * scale, 0f, 0f, translate,
                0f, 0.433f * scale, 0.567f * scale, 0f, translate,
                0f, 0.475f * scale, 0.525f * scale, 0f, translate,
                0f, 0f, 0f, 1f, 0f
            )
            ColorMatrix(tritanopiaMatrix)
        }
        ManhwaViewModel.ColorMode.HIGH_CONTRAST -> {
            val hcScale = scale * 1.5f
            val hcTranslate = translate - 30f
            val highContrastMatrix = floatArrayOf(
                hcScale, 0f, 0f, 0f, hcTranslate,
                0f, hcScale, 0f, 0f, hcTranslate,
                0f, 0f, hcScale, 0f, hcTranslate,
                0f, 0f, 0f, 1f, 0f
            )
            ColorMatrix(highContrastMatrix)
        }
        else -> {
            val array = floatArrayOf(
                scale, 0f, 0f, 0f, translate,
                0f, scale, 0f, 0f, translate,
                0f, 0f, scale, 0f, translate,
                0f, 0f, 0f, 1f, 0f
            )
            ColorMatrix(array)
        }
    }
}

// --- SCREEN: Settings Manager ---
@Composable
fun SettingsScreen(viewModel: ManhwaViewModel) {
    val qualitySelectionEnabled by viewModel.qualitySelectionEnabled.collectAsStateWithLifecycle()
    val qualityLevel by viewModel.qualityLevel.collectAsStateWithLifecycle()
    val maxStorageAllocation by viewModel.maxStorageAllocation.collectAsStateWithLifecycle()
    
    var cacheSizeText by remember { mutableStateOf("0.00 MB") }
    val context = LocalContext.current
    
    LaunchedEffect(Unit, qualityLevel, qualitySelectionEnabled) {
        cacheSizeText = viewModel.getWebpCacheSize()
    }
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Text(
            text = "SYSTEM SETTINGS",
            fontSize = 20.sp,
            fontWeight = FontWeight.Black,
            letterSpacing = 1.2.sp,
            color = MaterialTheme.colorScheme.onBackground
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = "Configure high-performance cache engines, WebP rendering, and dynamic storage thresholds.",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
            lineHeight = 16.sp
        )

        Spacer(modifier = Modifier.height(20.dp))

        // --- SECTION 1: QUALITY SELECTION ---
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier
                .fillMaxWidth()
                .shadow(4.dp, RoundedCornerShape(16.dp))
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.08f), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Settings,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = "Quality Selection Mode",
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "High-speed WebP caching",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                            )
                        }
                    }
                    Switch(
                        checked = qualitySelectionEnabled,
                        onCheckedChange = { viewModel.setQualitySelectionEnabled(it) }
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = "When enabled, loaded pages are pre-cached as optimized WebP files on local storage. Subsequent reader loads bypass the PDF renderer entirely, providing near 0ms loading speeds and butter-smooth continuous scrolling.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    lineHeight = 16.sp
                )

                if (qualitySelectionEnabled) {
                    Spacer(modifier = Modifier.height(20.dp))
                    Text(
                        text = "WEB-P QUALITY LEVEL",
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 11.sp,
                        letterSpacing = 1.sp,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(10.dp))

                    val qualityOptions = listOf("MAX", "HIGH", "MEDIUM", "AVERAGE", "LOW")
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        qualityOptions.forEach { opt ->
                            val isSelected = qualityLevel == opt
                            Surface(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(38.dp)
                                    .clickable { viewModel.setQualityLevel(opt) },
                                shape = RoundedCornerShape(10.dp),
                                color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                                border = if (isSelected) BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary) else BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f))
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text(
                                        text = opt,
                                        fontSize = 10.sp,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                        color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    val qualityDesc = when (qualityLevel) {
                        "MAX" -> "Uncompromised. 2.0x Ultra HD resolution rendering with 100% lossless WebP compression. Perfect for high-density tablets."
                        "HIGH" -> "Recommended. 1.6x High Definition resolution rendering with 90% WebP quality. Optimal balance of image crispness and small file size."
                        "MEDIUM" -> "Balanced. 1.3x Standard resolution with 80% WebP quality. High performance and moderate storage footprint."
                        "AVERAGE" -> "Lightweight. 1.0x native resolution with 70% WebP quality. Gentle on storage, loads very quickly on older devices."
                        "LOW" -> "Eco. 0.7x reduced resolution with 50% WebP quality. Ultra lightweight, highly compressed, uses absolute minimal storage."
                        else -> ""
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.05f), RoundedCornerShape(8.dp))
                            .padding(10.dp)
                    ) {
                        Text(
                            text = qualityDesc,
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.primary,
                            lineHeight = 15.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // --- SECTION 2: STORAGE ALLOCATION ---
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier
                .fillMaxWidth()
                .shadow(4.dp, RoundedCornerShape(16.dp))
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.08f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Build,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = "Storage Allocation Limit",
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "Auto-delete threshold for cached WebP files",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                Spacer(modifier = Modifier.height(16.dp))

                val storageLimits = listOf(
                    100 to "100 MB",
                    250 to "250 MB",
                    500 to "500 MB",
                    1000 to "1.0 GB",
                    2000 to "2.0 GB"
                )

                Text(
                    text = "MAX CACHE LIMIT",
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 11.sp,
                    letterSpacing = 1.sp,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(10.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    storageLimits.forEach { (mb, label) ->
                        val isSelected = maxStorageAllocation == mb
                        Surface(
                            modifier = Modifier
                                .weight(1f)
                                .height(38.dp)
                                .clickable { viewModel.setMaxStorageAllocation(mb) },
                            shape = RoundedCornerShape(10.dp),
                            color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                            border = if (isSelected) BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary) else BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f))
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(
                                    text = label,
                                    fontSize = 10.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                    color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))
                Text(
                    text = "When total WebP disk storage usage exceeds this limit, the application automatically evicts the oldest (least recently accessed) pages to free up offline space. Your original imported PDF files are never modified or deleted.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    lineHeight = 16.sp
                )

                Spacer(modifier = Modifier.height(16.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                Spacer(modifier = Modifier.height(16.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "Current WebP Cache:",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = cacheSizeText,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Black,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }

                    Button(
                        onClick = {
                            viewModel.clearAllWebpCache()
                            cacheSizeText = "0.00 MB"
                            Toast.makeText(context, "Successfully cleared all cached WebP pages!", Toast.LENGTH_SHORT).show()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = "Clear Cache",
                            tint = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Purge Cache",
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // --- SECTION 3: READER & ZOOM PREFERENCES ---
        val zoomLockEnabled by viewModel.zoomLockEnabled.collectAsStateWithLifecycle()
        val lockedZoomLevel by viewModel.lockedZoomLevel.collectAsStateWithLifecycle()

        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier
                .fillMaxWidth()
                .shadow(4.dp, RoundedCornerShape(16.dp))
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.08f), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Search,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = "Persistent Zoom Lock",
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "Keep custom scale across pages",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                            )
                        }
                    }
                    Switch(
                        checked = zoomLockEnabled,
                        onCheckedChange = { viewModel.setZoomLockEnabled(it) }
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = "When Zoom Lock is enabled, the reader will persist your current pinch-to-zoom level across different pages and sessions. New documents will open automatically at this exact zoom level instead of resetting back to fit width.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    lineHeight = 16.sp
                )

                if (zoomLockEnabled) {
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = String.format("CURRENT LOCKED SCALE: %.2fX", lockedZoomLevel),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = MaterialTheme.colorScheme.primary,
                        letterSpacing = 1.sp
                    )
                }
            }
        }
    }
}
