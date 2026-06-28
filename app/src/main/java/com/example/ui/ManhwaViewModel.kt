package com.example.ui

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.Bookmark
import com.example.data.Manhwa
import com.example.data.ManhwaRepository
import com.example.data.PluginConfig
import com.example.data.SeriesParser
import com.example.pdf.ManhwaPdfRenderer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class DrawPath(
    val points: List<Offset>,
    val color: Color,
    val strokeWidth: Float
)

data class UltraTab(
    val id: String,
    val title: String,
    val type: TabType,
    val manhwa: Manhwa? = null,
    val currentPage: Int = 0
)

enum class TabType {
    LIBRARY, PLUGINS, READER, SETTINGS
}

class ManhwaViewModel(private val application: Application, private val repository: ManhwaRepository) : ViewModel() {

    // --- State: Database Flows ---
    val allManhwas: StateFlow<List<Manhwa>> = repository.allManhwas
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    val allPlugins: StateFlow<List<PluginConfig>> = repository.allPlugins
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    // --- State: Tab-Based Multi-Document System ---
    private val _tabs = MutableStateFlow<List<UltraTab>>(listOf(
        UltraTab(id = "library", title = "Library", type = TabType.LIBRARY)
    ))
    val tabs: StateFlow<List<UltraTab>> = _tabs.asStateFlow()

    private val _activeTabId = MutableStateFlow<String>("library")
    val activeTabId: StateFlow<String> = _activeTabId.asStateFlow()

    val activeTab: StateFlow<UltraTab?> = _activeTabId
        .flatMapLatest { id ->
            val tab = _tabs.value.find { it.id == id }
            flowOf(tab)
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = UltraTab(id = "library", title = "Library", type = TabType.LIBRARY)
        )

    private val renderers = mutableMapOf<Long, ManhwaPdfRenderer>()
    private var dbUpdateJob: kotlinx.coroutines.Job? = null
    private var warmCacheJob: kotlinx.coroutines.Job? = null

    // --- State: Reader UI Compatibility Flows ---
    val activeManhwa: StateFlow<Manhwa?> = activeTab
        .flatMapLatest { tab ->
            flowOf(tab?.manhwa)
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = null
        )

    val currentPage: StateFlow<Int> = activeTab
        .flatMapLatest { tab ->
            flowOf(tab?.currentPage ?: 0)
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = 0
        )

    // Bookmarks for the open Manhwa
    val activeBookmarks: StateFlow<List<Bookmark>> = activeManhwa
        .flatMapLatest { manhwa ->
            if (manhwa != null) {
                repository.getBookmarksForManhwa(manhwa.id)
            } else {
                flowOf(emptyList())
            }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    // UI Panel States
    private val _isOutlineDrawerOpen = MutableStateFlow(false)
    val isOutlineDrawerOpen: StateFlow<Boolean> = _isOutlineDrawerOpen.asStateFlow()

    private val _importingState = MutableStateFlow<ImportState>(ImportState.Idle)
    val importingState: StateFlow<ImportState> = _importingState.asStateFlow()

    private val _selectedTab = MutableStateFlow(ReaderTab.Library)
    val selectedTab: StateFlow<ReaderTab> = _selectedTab.asStateFlow()

    // --- State: Chapter Sorting & History ---
    enum class SortMode {
        RECENT, NATURAL
    }
    
    private val _sortMode = MutableStateFlow(SortMode.RECENT)
    val sortMode: StateFlow<SortMode> = _sortMode.asStateFlow()

    private val _autoScrollSpeed = MutableStateFlow(0f) // 0 means stopped, 1-10 means scroll speed
    val autoScrollSpeed: StateFlow<Float> = _autoScrollSpeed.asStateFlow()

    private val _chapterHistory = MutableStateFlow<List<Long>>(emptyList())
    val chapterHistory: StateFlow<List<Long>> = _chapterHistory.asStateFlow()

    private val _historyIndex = MutableStateFlow(-1)
    val historyIndex: StateFlow<Int> = _historyIndex.asStateFlow()

    // --- State: View Enhancer Plugin Properties ---
    private val _brightness = MutableStateFlow(1.0f)
    val brightness: StateFlow<Float> = _brightness.asStateFlow()

    private val _contrast = MutableStateFlow(1.0f)
    val contrast: StateFlow<Float> = _contrast.asStateFlow()

    private val _colorMode = MutableStateFlow(ColorMode.NORMAL)
    val colorMode: StateFlow<ColorMode> = _colorMode.asStateFlow()

    private val _hdModeEnabled = MutableStateFlow(true)
    val hdModeEnabled: StateFlow<Boolean> = _hdModeEnabled.asStateFlow()

    // --- Core Fast-Render & WebP Caching Settings ---
    private val sharedPrefs = application.getSharedPreferences("manhwa_settings", Context.MODE_PRIVATE)

    private val _qualitySelectionEnabled = MutableStateFlow(sharedPrefs.getBoolean("quality_selection_enabled", true))
    val qualitySelectionEnabled: StateFlow<Boolean> = _qualitySelectionEnabled.asStateFlow()

    private val _qualityLevel = MutableStateFlow(sharedPrefs.getString("quality_level", "HIGH") ?: "HIGH")
    val qualityLevel: StateFlow<String> = _qualityLevel.asStateFlow()

    private val _maxStorageAllocation = MutableStateFlow(sharedPrefs.getInt("max_storage_allocation", 500)) // in MB
    val maxStorageAllocation: StateFlow<Int> = _maxStorageAllocation.asStateFlow()

    // --- State: Advanced Zoom & Magnifier settings ---
    private val _zoomLockEnabled = MutableStateFlow(sharedPrefs.getBoolean("zoom_lock_enabled", false))
    val zoomLockEnabled: StateFlow<Boolean> = _zoomLockEnabled.asStateFlow()

    private val _lockedZoomLevel = MutableStateFlow(sharedPrefs.getFloat("locked_zoom_level", 1.0f))
    val lockedZoomLevel: StateFlow<Float> = _lockedZoomLevel.asStateFlow()

    private val _activeZoomScale = MutableStateFlow(1.0f)
    val activeZoomScale: StateFlow<Float> = _activeZoomScale.asStateFlow()

    private val _isMagnifierEnabled = MutableStateFlow(false)
    val isMagnifierEnabled: StateFlow<Boolean> = _isMagnifierEnabled.asStateFlow()

    // --- State: Manhwa Sketch Editor Plugin Properties ---
    private val _activeDrawColor = MutableStateFlow(Color.Red)
    val activeDrawColor: StateFlow<Color> = _activeDrawColor.asStateFlow()

    private val _activeStrokeWidth = MutableStateFlow(8f)
    val activeStrokeWidth: StateFlow<Float> = _activeStrokeWidth.asStateFlow()

    // Page-indexed map of drawing sketches
    private val _sketches = MutableStateFlow<Map<Int, List<DrawPath>>>(emptyMap())
    val sketches: StateFlow<Map<Int, List<DrawPath>>> = _sketches.asStateFlow()

    // --- Helper Enums / Sealed Classes ---
    sealed class ImportState {
        object Idle : ImportState()
        object Loading : ImportState()
        data class Success(val title: String) : ImportState()
        data class Error(val message: String) : ImportState()
    }

    enum class ReaderTab {
        Library, Plugins, Reader, Settings
    }

    enum class ColorMode {
        NORMAL, GRAYSCALE, SEPIA, INVERTED, PROTANOPIA, DEUTERANOPIA, TRITANOPIA, HIGH_CONTRAST
    }

    // --- Tab management operations ---
    fun selectTabId(tabId: String) {
        _activeTabId.value = tabId
        val tab = _tabs.value.find { it.id == tabId }
        if (tab != null) {
            when (tab.type) {
                TabType.LIBRARY -> _selectedTab.value = ReaderTab.Library
                TabType.PLUGINS -> _selectedTab.value = ReaderTab.Plugins
                TabType.READER -> _selectedTab.value = ReaderTab.Reader
                TabType.SETTINGS -> _selectedTab.value = ReaderTab.Settings
            }
        }
    }

    fun openManhwaInTab(manhwa: Manhwa) {
        viewModelScope.launch {
            _importingState.value = ImportState.Loading
            val file = File(manhwa.filePath)
            if (!file.exists()) {
                _importingState.value = ImportState.Error("Local file does not exist")
                return@launch
            }

            val tabId = "reader_${manhwa.id}"
            val existingList = _tabs.value.toMutableList()

            // Check if tab is already open
            val existingTab = existingList.find { it.id == tabId }
            if (existingTab != null) {
                selectTabId(tabId)
                _importingState.value = ImportState.Idle
                return@launch
            }

            // If we have 3 tabs, replace the active one (if reader or plugins) or the oldest reader to respect 3-tab limit
            if (existingList.size >= 3) {
                val activeTabObj = existingList.find { it.id == _activeTabId.value }
                if (activeTabObj != null && activeTabObj.type != TabType.LIBRARY) {
                    activeTabObj.manhwa?.let { oldM ->
                        synchronized(renderers) {
                            renderers.remove(oldM.id)?.close()
                        }
                    }
                    existingList.remove(activeTabObj)
                } else {
                    // Fallback: find any reader tab to remove
                    val anyReader = existingList.find { it.type == TabType.READER }
                    if (anyReader != null) {
                        anyReader.manhwa?.let { oldM ->
                            synchronized(renderers) {
                                renderers.remove(oldM.id)?.close()
                            }
                        }
                        existingList.remove(anyReader)
                    } else {
                        // Fallback: remove last tab
                        val lastTab = existingList.last()
                        if (lastTab.type != TabType.LIBRARY) {
                            existingList.remove(lastTab)
                        }
                    }
                }
            }

            // Create new reader tab
            val newTab = UltraTab(
                id = tabId,
                title = manhwa.title,
                type = TabType.READER,
                manhwa = manhwa,
                currentPage = manhwa.lastReadPage
            )
            existingList.add(newTab)
            _tabs.value = existingList
            selectTabId(tabId)
            _activeZoomScale.value = if (_zoomLockEnabled.value) _lockedZoomLevel.value else 1.0f
            _importingState.value = ImportState.Idle

            // Pre-warm the renderer on a background thread so there's absolutely 0ms lag when the reader opens
            withContext(Dispatchers.IO) {
                try {
                    val r = synchronized(renderers) {
                        renderers.getOrPut(manhwa.id) {
                            ManhwaPdfRenderer(application, file)
                        }
                    }
                    // Prefetch aspect ratios for the first few pages to make page layout calculation instant
                    val pageCount = r.pageCount
                    val startPage = manhwa.lastReadPage
                    for (i in startPage until (startPage + 5).coerceAtMost(pageCount)) {
                        r.getPageAspectRatio(i)
                    }
                } catch (e: Throwable) {
                    e.printStackTrace()
                }
            }

            // Save last opened
            repository.updateManhwa(manhwa.copy(lastOpened = System.currentTimeMillis()))
        }
    }

    fun closeTab(tabId: String) {
        viewModelScope.launch {
            val existingList = _tabs.value.toMutableList()
            val tabToClose = existingList.find { it.id == tabId } ?: return@launch

            if (tabToClose.type == TabType.LIBRARY) {
                // Cannot close library tab to ensure there is always a fallback
                return@launch
            }

            if (tabToClose.type == TabType.READER && tabToClose.manhwa != null) {
                repository.updateManhwa(
                    tabToClose.manhwa.copy(
                        lastReadPage = tabToClose.currentPage,
                        lastOpened = System.currentTimeMillis()
                    )
                )
                synchronized(renderers) {
                    renderers.remove(tabToClose.manhwa.id)?.close()
                }
            }

            existingList.remove(tabToClose)
            _tabs.value = existingList

            if (_activeTabId.value == tabId) {
                selectTabId(existingList.first().id)
            }
        }
    }

    fun openPluginsTab() {
        val existingList = _tabs.value.toMutableList()
        val pluginsTabId = "plugins"
        val existingTab = existingList.find { it.id == pluginsTabId }
        
        if (existingTab == null) {
            if (existingList.size >= 3) {
                // Remove active tab (if it's not library)
                val activeTabObj = existingList.find { it.id == _activeTabId.value }
                if (activeTabObj != null && activeTabObj.type != TabType.LIBRARY) {
                    activeTabObj.manhwa?.let { oldM ->
                        synchronized(renderers) {
                            renderers.remove(oldM.id)?.close()
                        }
                    }
                    existingList.remove(activeTabObj)
                } else {
                    val anyReader = existingList.find { it.type == TabType.READER }
                    if (anyReader != null) {
                        anyReader.manhwa?.let { oldM ->
                            synchronized(renderers) {
                                renderers.remove(oldM.id)?.close()
                            }
                        }
                        existingList.remove(anyReader)
                    }
                }
            }
            existingList.add(UltraTab(id = pluginsTabId, title = "Plugins", type = TabType.PLUGINS))
            _tabs.value = existingList
        }
        selectTabId(pluginsTabId)
    }

    fun updateActiveTabCurrentPage(pageIndex: Int) {
        updateActiveTabCurrentPageAndOffset(pageIndex, 0)
    }

    fun updateActiveTabCurrentPageAndOffset(pageIndex: Int, offset: Int) {
        val currentId = _activeTabId.value
        val existingList = _tabs.value.map { tab ->
            if (tab.id == currentId) {
                val updatedTab = tab.copy(currentPage = pageIndex)
                tab.manhwa?.let { manhwa ->
                    dbUpdateJob?.cancel()
                    dbUpdateJob = viewModelScope.launch {
                        kotlinx.coroutines.delay(1000)
                        withContext(Dispatchers.IO) {
                            repository.updateManhwa(manhwa.copy(lastReadPage = pageIndex, scrollOffset = offset))
                        }
                    }
                }
                updatedTab
            } else {
                tab
            }
        }
        _tabs.value = existingList
    }

    // --- Operations ---
    fun selectTab(tab: ReaderTab) {
        _selectedTab.value = tab
        when (tab) {
            ReaderTab.Library -> selectTabId("library")
            ReaderTab.Plugins -> openPluginsTab()
            ReaderTab.Reader -> {
                // select active reader if any is open
                val activeReaderTab = _tabs.value.find { it.type == TabType.READER }
                if (activeReaderTab != null) {
                    selectTabId(activeReaderTab.id)
                }
            }
            ReaderTab.Settings -> openSettingsTab()
        }
    }

    fun toggleOutlineDrawer() {
        _isOutlineDrawerOpen.value = !_isOutlineDrawerOpen.value
    }

    fun setOutlineDrawerOpen(open: Boolean) {
        _isOutlineDrawerOpen.value = open
    }

    fun importPdfFile(uri: Uri) {
        viewModelScope.launch {
            _importingState.value = ImportState.Loading
            try {
                val id = repository.importPdf(uri)
                _importingState.value = ImportState.Success("Successfully imported!")
                if (!_qualitySelectionEnabled.value) {
                    val manhwa = repository.getManhwaById(id)
                    if (manhwa != null) {
                        openManhwaInTab(manhwa)
                    }
                }
            } catch (e: Exception) {
                _importingState.value = ImportState.Error(e.localizedMessage ?: "Failed to import PDF")
            }
        }
    }

    fun resetImportState() {
        _importingState.value = ImportState.Idle
    }

    fun openManhwa(manhwa: Manhwa) {
        openManhwaInTab(manhwa)
    }

    fun closeManhwa() {
        closeTab(_activeTabId.value)
    }

    fun deleteManhwa(manhwa: Manhwa) {
        viewModelScope.launch {
            val tabId = "reader_${manhwa.id}"
            closeTab(tabId)
            repository.deleteManhwa(manhwa)
        }
    }

    fun getPageCountForActiveManhwa(): Int {
        val tab = _tabs.value.find { it.id == _activeTabId.value } ?: return 1
        val manhwa = tab.manhwa ?: return 1
        val renderer = synchronized(renderers) {
            renderers[manhwa.id] ?: try {
                val file = File(manhwa.filePath)
                if (file.exists()) {
                    val r = ManhwaPdfRenderer(application, file)
                    renderers[manhwa.id] = r
                    r
                } else null
            } catch (e: Throwable) {
                null
            }
        }
        return renderer?.pageCount ?: 1
    }

    fun setCurrentPage(pageIndex: Int) {
        setCurrentPageAndOffset(pageIndex, 0)
    }

    fun setCurrentPageAndOffset(pageIndex: Int, offset: Int) {
        val pageCount = getPageCountForActiveManhwa()
        if (pageIndex >= 0 && pageIndex < pageCount) {
            updateActiveTabCurrentPageAndOffset(pageIndex, offset)
        }
    }

    suspend fun getPageAspectRatio(pageIndex: Int): Float = withContext(Dispatchers.IO) {
        val tab = _tabs.value.find { it.id == _activeTabId.value } ?: return@withContext 1.414f
        val manhwa = tab.manhwa ?: return@withContext 1.414f
        val file = File(manhwa.filePath)
        if (!file.exists()) return@withContext 1.414f

        val renderer = try {
            synchronized(renderers) {
                renderers.getOrPut(manhwa.id) {
                    ManhwaPdfRenderer(application, file)
                }
            }
        } catch (e: Throwable) {
            e.printStackTrace()
            null
        } ?: return@withContext 1.414f
        renderer.getPageAspectRatio(pageIndex)
    }

    suspend fun renderPageSlice(pageIndex: Int, targetWidth: Int, sliceIndex: Int, sliceHeight: Int): Bitmap? = withContext(Dispatchers.IO) {
        val tab = _tabs.value.find { it.id == _activeTabId.value } ?: return@withContext null
        val manhwa = tab.manhwa ?: return@withContext null
        val file = File(manhwa.filePath)
        if (!file.exists()) return@withContext null

        val renderer = try {
            synchronized(renderers) {
                renderers.getOrPut(manhwa.id) {
                    ManhwaPdfRenderer(application, file)
                }
            }
        } catch (e: Throwable) {
            e.printStackTrace()
            null
        } ?: return@withContext null

        val isCacheEnabled = _qualitySelectionEnabled.value
        val zoomScaleVal = _activeZoomScale.value
        val zoomFactor = if (zoomScaleVal > 1.0f) zoomScaleVal * 2.0f else 1.0f
        val baseScale = if (isCacheEnabled) {
            getQualityScaleFactor(_qualityLevel.value)
        } else {
            if (_hdModeEnabled.value) 2.0f else 1.2f
        }
        val scale = (baseScale * zoomFactor).coerceAtMost(4.5f)
        val qualityCompression = getQualityCompression(_qualityLevel.value)
        val maxStorage = _maxStorageAllocation.value

        renderer.renderPageSlice(
            pageIndex, targetWidth, sliceIndex, sliceHeight, scale,
            isCacheEnabled, _qualityLevel.value, qualityCompression, maxStorage
        )
    }

    suspend fun renderPage(pageIndex: Int, targetWidth: Int): Bitmap? = withContext(Dispatchers.IO) {
        val tab = _tabs.value.find { it.id == _activeTabId.value } ?: return@withContext null
        val manhwa = tab.manhwa ?: return@withContext null
        val file = File(manhwa.filePath)
        if (!file.exists()) return@withContext null

        val renderer = try {
            synchronized(renderers) {
                renderers.getOrPut(manhwa.id) {
                    ManhwaPdfRenderer(application, file)
                }
            }
        } catch (e: Throwable) {
            e.printStackTrace()
            null
        } ?: return@withContext null

        val isCacheEnabled = _qualitySelectionEnabled.value
        val scale = if (isCacheEnabled) {
            getQualityScaleFactor(_qualityLevel.value)
        } else {
            if (_hdModeEnabled.value) 2.0f else 1.2f
        }
        val qualityCompression = getQualityCompression(_qualityLevel.value)
        val maxStorage = _maxStorageAllocation.value

        renderer.renderPage(
            pageIndex, targetWidth, scale,
            isCacheEnabled, _qualityLevel.value, qualityCompression, maxStorage
        )
    }

    // --- Bookmarking & Outlining ---
    fun addBookmarkForCurrentPage(title: String) {
        val openBook = activeManhwa.value ?: return
        val pageIdx = currentPage.value
        viewModelScope.launch {
            val bookmark = Bookmark(
                manhwaId = openBook.id,
                pageIndex = pageIdx,
                title = title
            )
            repository.addBookmark(bookmark)
        }
    }

    fun removeBookmark(bookmark: Bookmark) {
        viewModelScope.launch {
            repository.removeBookmark(bookmark)
        }
    }

    // --- Plugins Settings ---
    fun togglePlugin(plugin: PluginConfig) {
        viewModelScope.launch {
            val updated = plugin.copy(enabled = !plugin.enabled)
            repository.updatePlugin(updated)
        }
    }

    // --- View Enhancer Controls ---
    fun setBrightness(value: Float) {
        _brightness.value = value
    }

    fun setContrast(value: Float) {
        _contrast.value = value
    }

    fun setColorMode(mode: ColorMode) {
        _colorMode.value = mode
    }

    fun toggleHdMode() {
        _hdModeEnabled.value = !_hdModeEnabled.value
    }

    // --- Core Fast-Render & WebP Cache Controls ---
    fun setQualitySelectionEnabled(enabled: Boolean) {
        _qualitySelectionEnabled.value = enabled
        sharedPrefs.edit().putBoolean("quality_selection_enabled", enabled).apply()
    }

    fun setQualityLevel(level: String) {
        _qualityLevel.value = level
        sharedPrefs.edit().putString("quality_level", level).apply()
    }

    fun setMaxStorageAllocation(megabytes: Int) {
        _maxStorageAllocation.value = megabytes
        sharedPrefs.edit().putInt("max_storage_allocation", megabytes).apply()
    }

    // --- Advanced Zoom & Magnifier Setters ---
    fun setZoomLockEnabled(enabled: Boolean) {
        _zoomLockEnabled.value = enabled
        sharedPrefs.edit().putBoolean("zoom_lock_enabled", enabled).apply()
        if (enabled) {
            sharedPrefs.edit().putFloat("locked_zoom_level", _activeZoomScale.value).apply()
            _lockedZoomLevel.value = _activeZoomScale.value
        }
    }

    fun setLockedZoomLevel(level: Float) {
        _lockedZoomLevel.value = level
        sharedPrefs.edit().putFloat("locked_zoom_level", level).apply()
    }

    fun setActiveZoomScale(scale: Float) {
        _activeZoomScale.value = scale
        if (_zoomLockEnabled.value) {
            setLockedZoomLevel(scale)
        }
    }

    fun setMagnifierEnabled(enabled: Boolean) {
        _isMagnifierEnabled.value = enabled
    }

    fun getQualityScaleFactor(level: String): Float {
        return when (level) {
            "MAX" -> 2.0f
            "HIGH" -> 1.6f
            "MEDIUM" -> 1.3f
            "AVERAGE" -> 1.0f
            "LOW" -> 0.7f
            else -> 1.6f
        }
    }

    fun getQualityCompression(level: String): Int {
        return when (level) {
            "MAX" -> 100
            "HIGH" -> 90
            "MEDIUM" -> 80
            "AVERAGE" -> 70
            "LOW" -> 50
            else -> 90
        }
    }

    fun openSettingsTab() {
        val existingList = _tabs.value.toMutableList()
        val settingsTabId = "settings"
        val existingTab = existingList.find { it.id == settingsTabId }
        
        if (existingTab == null) {
            if (existingList.size >= 3) {
                // Remove active tab (if it's not library)
                val activeTabObj = existingList.find { it.id == _activeTabId.value }
                if (activeTabObj != null && activeTabObj.type != TabType.LIBRARY) {
                    activeTabObj.manhwa?.let { oldM ->
                        synchronized(renderers) {
                            renderers.remove(oldM.id)?.close()
                        }
                    }
                    existingList.remove(activeTabObj)
                } else {
                    val anyReader = existingList.find { it.type == TabType.READER }
                    if (anyReader != null) {
                        anyReader.manhwa?.let { oldM ->
                            synchronized(renderers) {
                                renderers.remove(oldM.id)?.close()
                            }
                        }
                        existingList.remove(anyReader)
                    }
                }
            }
            existingList.add(UltraTab(id = settingsTabId, title = "Settings", type = TabType.SETTINGS))
            _tabs.value = existingList
        }
        selectTabId(settingsTabId)
    }

    // --- Sketch Editor Controls ---
    fun setDrawColor(color: Color) {
        _activeDrawColor.value = color
    }

    fun setStrokeWidth(width: Float) {
        _activeStrokeWidth.value = width
    }

    fun addDrawPath(pageIndex: Int, path: DrawPath) {
        val currentSketches = _sketches.value.toMutableMap()
        val paths = (currentSketches[pageIndex] ?: emptyList()).toMutableList()
        paths.add(path)
        currentSketches[pageIndex] = paths
        _sketches.value = currentSketches
    }

    fun clearDrawPaths(pageIndex: Int) {
        val currentSketches = _sketches.value.toMutableMap()
        currentSketches.remove(pageIndex)
        _sketches.value = currentSketches
    }

    // --- WebP Disk Cache Monitoring & Clearing Utilities ---
    fun getWebpCacheSize(): String {
        val webpParentDir = File(application.cacheDir, "webp_cache")
        if (!webpParentDir.exists()) return "0.00 MB"
        val totalBytes = webpParentDir.walkTopDown().filter { it.isFile && it.extension == "webp" }.sumOf { it.length() }
        val mb = totalBytes.toDouble() / (1024 * 1024)
        return String.format("%.2f MB", mb)
    }

    fun clearAllWebpCache() {
        viewModelScope.launch(Dispatchers.IO) {
            val webpParentDir = File(application.cacheDir, "webp_cache")
            if (webpParentDir.exists()) {
                webpParentDir.deleteRecursively()
            }
            // Clear any active in-memory cache slices as well
            synchronized(renderers) {
                renderers.values.forEach { it.clearCache() }
            }
        }
    }

    // --- Auto-Scroll Control ---
    fun setAutoScrollSpeed(speed: Float) {
        _autoScrollSpeed.value = speed
    }

    // --- Sort Mode Control ---
    fun setSortMode(mode: SortMode) {
        _sortMode.value = mode
    }

    // --- Series Parsing & Helpers ---
    fun getSeriesName(manhwa: Manhwa): String = SeriesParser.parse(manhwa.title).seriesName
    fun getChapterNumber(manhwa: Manhwa): Float = SeriesParser.parse(manhwa.title).chapterNumber

    fun getNextChapter(manhwa: Manhwa): Manhwa? {
        val all = allManhwas.value
        val currentInfo = SeriesParser.parse(manhwa.title)
        return all.filter { getSeriesName(it).equals(currentInfo.seriesName, ignoreCase = true) }
            .filter { SeriesParser.parse(it.title).chapterNumber > currentInfo.chapterNumber }
            .minByOrNull { SeriesParser.parse(it.title).chapterNumber }
    }

    fun getPreviousChapter(manhwa: Manhwa): Manhwa? {
        val all = allManhwas.value
        val currentInfo = SeriesParser.parse(manhwa.title)
        return all.filter { getSeriesName(it).equals(currentInfo.seriesName, ignoreCase = true) }
            .filter { SeriesParser.parse(it.title).chapterNumber < currentInfo.chapterNumber }
            .maxByOrNull { SeriesParser.parse(it.title).chapterNumber }
    }

    // --- Browser-Like Chapter History & Navigation ---
    fun navigateToChapter(manhwa: Manhwa) {
        val currentHist = _chapterHistory.value.toMutableList()
        val currIdx = _historyIndex.value

        val newHistory = if (currIdx >= 0 && currIdx < currentHist.size) {
            currentHist.subList(0, currIdx + 1).toMutableList()
        } else {
            currentHist
        }
        
        newHistory.add(manhwa.id)
        _chapterHistory.value = newHistory
        _historyIndex.value = newHistory.size - 1

        openManhwaInTab(manhwa)
    }

    fun canNavigateBack(): Boolean {
        return _historyIndex.value > 0
    }

    fun canNavigateForward(): Boolean {
        return _historyIndex.value < _chapterHistory.value.size - 1
    }

    fun navigateBack() {
        if (canNavigateBack()) {
            val nextIdx = _historyIndex.value - 1
            _historyIndex.value = nextIdx
            val targetId = _chapterHistory.value[nextIdx]
            viewModelScope.launch {
                repository.getManhwaById(targetId)?.let { manhwa ->
                    openManhwaInTab(manhwa)
                }
            }
        }
    }

    fun navigateForward() {
        if (canNavigateForward()) {
            val nextIdx = _historyIndex.value + 1
            _historyIndex.value = nextIdx
            val targetId = _chapterHistory.value[nextIdx]
            viewModelScope.launch {
                repository.getManhwaById(targetId)?.let { manhwa ->
                    openManhwaInTab(manhwa)
                }
            }
        }
    }

    // --- Reading Velocity Cache Warming ---
    fun warmCacheForVelocity(currentPage: Int, targetWidth: Int, velocity: Float) {
        warmCacheJob?.cancel()
        warmCacheJob = viewModelScope.launch(Dispatchers.IO) {
            val manhwa = activeManhwa.value ?: return@launch
            val renderer = synchronized(renderers) {
                renderers[manhwa.id]
            } ?: return@launch
            val totalPages = renderer.pageCount

            // Normal velocity: warm 1-2 pages ahead
            // High velocity: warm up to 4-5 pages ahead to ensure seamless zero-lag reading strip
            val pagesToWarm = if (velocity > 1.5f) {
                listOf(currentPage + 1, currentPage + 2, currentPage + 3, currentPage + 4, currentPage + 5)
            } else if (velocity > 0.5f) {
                listOf(currentPage + 1, currentPage + 2, currentPage + 3)
            } else {
                listOf(currentPage + 1, currentPage + 2)
            }

            for (pageIdx in pagesToWarm) {
                ensureActive()
                if (pageIdx in 0 until totalPages) {
                    val isCacheEnabled = _qualitySelectionEnabled.value
                    val scale = if (isCacheEnabled) {
                        getQualityScaleFactor(_qualityLevel.value)
                    } else {
                        if (_hdModeEnabled.value) 2.0f else 1.2f
                    }
                    val qualityCompression = getQualityCompression(_qualityLevel.value)
                    val maxStorage = _maxStorageAllocation.value
                    
                    renderer.renderPage(
                        pageIdx, targetWidth, scale,
                        isCacheEnabled, _qualityLevel.value, qualityCompression, maxStorage
                    )
                    // Cooperatively sleep between pages to allow high-priority rendering to immediately acquire the lock
                    kotlinx.coroutines.delay(50)
                }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        synchronized(renderers) {
            renderers.values.forEach { it.close() }
            renderers.clear()
        }
    }
}

class ManhwaViewModelFactory(private val application: Application, private val repository: ManhwaRepository) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(ManhwaViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return ManhwaViewModel(application, repository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
