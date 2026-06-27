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
import androidx.compose.foundation.gestures.detectDragGestures
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
                            items(tabs) { tab ->
                                val isActive = tab.id == activeTabId
                                Surface(
                                    modifier = Modifier
                                        .height(36.dp)
                                        .clickable { viewModel.selectTabId(tab.id) }
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
                                        if (tab.id != "library") {
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Icon(
                                                imageVector = Icons.Default.Close,
                                                contentDescription = "Close tab",
                                                tint = if (isActive) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f) else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                                modifier = Modifier
                                                    .size(16.dp)
                                                    .clickable { viewModel.closeTab(tab.id) }
                                            )
                                        }
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
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { viewModel.importPdfFile(it) }
    }
    var showDeleteConfirmDialog by remember { mutableStateOf<Manhwa?>(null) }

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
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Offline Comics (${manhwas.size})",
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

                items(manhwas, key = { it.id }) { manhwa ->
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

    val lazyListState = rememberLazyListState(initialFirstVisibleItemIndex = activeManhwa?.lastReadPage ?: 0)
    val coroutineScope = rememberCoroutineScope()
    var componentWidth by remember { mutableStateOf(1080) }

    // Dynamic scroll tracking to update reading progress
    LaunchedEffect(lazyListState.firstVisibleItemIndex) {
        viewModel.setCurrentPage(lazyListState.firstVisibleItemIndex)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black) // Perfect backdrop for comics
            .onGloballyPositioned { componentWidth = it.size.width }
    ) {
        // --- 1. CONTINUOUS VERTICAL STRIP OF MANHWA PAGES ---
        LazyColumn(
            state = lazyListState,
            userScrollEnabled = !isDrawModeOn, // LOCK scrolling during drawing sessions!
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(0.dp) // GAPLESS reading (Mandatory for Manhwa!)
        ) {
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
                        viewModel = viewModel,
                        brightness = brightness,
                        contrast = contrast,
                        colorMode = colorMode
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
        }

        // --- 2. CONTROL OVERLAYS & HUD (Heads-Up Display) ---
        // Top HUD Bar
        AnimatedVisibility(
            visible = !isDrawModeOn,
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
            visible = !isDrawModeOn,
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
                onAddBookmarkClick = {
                    bookmarkTitleInput = "Chapter Mark"
                    showAddBookmarkDialog = true
                },
                onBrightnessChange = { viewModel.setBrightness(it) },
                onContrastChange = { viewModel.setContrast(it) },
                onColorModeChange = { viewModel.setColorMode(it) },
                onToggleHdMode = { viewModel.toggleHdMode() }
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
    var isVisible by remember { mutableStateOf(false) }

    val context = LocalContext.current
    val screenHeightPx = remember { context.resources.displayMetrics.heightPixels }

    LaunchedEffect(isVisible, pageIndex, targetWidth, sliceIndex, sliceHeight, viewModel) {
        if (isVisible) {
            isRendering = true
            val bitmap = viewModel.renderPageSlice(pageIndex, targetWidth, sliceIndex, sliceHeight)
            sliceBitmap = bitmap
            isRendering = false
        }
    }

    val sliceY = sliceIndex * sliceHeight
    val actualSliceHeight = (totalHeight - sliceY).coerceAtMost(sliceHeight)
    val sliceWidthToHeightRatio = totalWidth.toFloat() / actualSliceHeight.toFloat()

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(sliceWidthToHeightRatio)
            .background(Color.White)
            .onGloballyPositioned { coordinates ->
                if (!isVisible) {
                    val position = coordinates.positionInWindow()
                    val top = position.y
                    val bottom = position.y + coordinates.size.height
                    // Trigger load when the slice is within 1 screen height buffer of the visible area
                    val buffer = screenHeightPx
                    if (top < screenHeightPx + buffer && bottom > -buffer) {
                        isVisible = true
                    }
                }
            }
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
    viewModel: ManhwaViewModel,
    brightness: Float,
    contrast: Float,
    colorMode: ManhwaViewModel.ColorMode
) {
    val hdMode by viewModel.hdModeEnabled.collectAsStateWithLifecycle()
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
            val scale = if (hdMode) 2.0f else 1.2f
            val totalWidth = (targetWidth * scale).toInt().coerceAtLeast(400)
            val totalHeight = (totalWidth * aspect).toInt().coerceAtLeast(400)
            val sliceHeight = 1536
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
    onAddBookmarkClick: () -> Unit,
    onBrightnessChange: (Float) -> Unit,
    onContrastChange: (Float) -> Unit,
    onColorModeChange: (ManhwaViewModel.ColorMode) -> Unit,
    onToggleHdMode: () -> Unit
) {
    var showEnhancerControls by remember { mutableStateOf(false) }

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
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Color Filter:", fontSize = 12.sp, color = Color.LightGray)
                        Row {
                            val modes = listOf(
                                ManhwaViewModel.ColorMode.NORMAL to "Normal",
                                ManhwaViewModel.ColorMode.GRAYSCALE to "Gray",
                                ManhwaViewModel.ColorMode.SEPIA to "Sepia",
                                ManhwaViewModel.ColorMode.INVERTED to "Night"
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
                                        .padding(horizontal = 8.dp, vertical = 4.dp)
                                        .background(
                                            if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.1f) else Color.Transparent,
                                            RoundedCornerShape(4.dp)
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

            // Main HUD action buttons
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // View enhancer toggle button
                if (isViewEnhancerEnabled) {
                    IconButton(onClick = { showEnhancerControls = !showEnhancerControls }) {
                        Icon(
                            Icons.Default.Settings,
                            contentDescription = "Enhance",
                            tint = if (showEnhancerControls) MaterialTheme.colorScheme.primary else Color.White
                        )
                    }
                } else {
                    Spacer(modifier = Modifier.width(48.dp))
                }

                // Page slider navigation
                Text(
                    text = "${currentPage + 1} / $totalPages",
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                )

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
