package com.example.ui

import android.app.Application
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
import com.example.pdf.ManhwaPdfRenderer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
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
    LIBRARY, PLUGINS, READER
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

    // --- State: View Enhancer Plugin Properties ---
    private val _brightness = MutableStateFlow(1.0f)
    val brightness: StateFlow<Float> = _brightness.asStateFlow()

    private val _contrast = MutableStateFlow(1.0f)
    val contrast: StateFlow<Float> = _contrast.asStateFlow()

    private val _colorMode = MutableStateFlow(ColorMode.NORMAL)
    val colorMode: StateFlow<ColorMode> = _colorMode.asStateFlow()

    private val _hdModeEnabled = MutableStateFlow(true)
    val hdModeEnabled: StateFlow<Boolean> = _hdModeEnabled.asStateFlow()

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
        Library, Plugins, Reader
    }

    enum class ColorMode {
        NORMAL, GRAYSCALE, SEPIA, INVERTED
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
            _importingState.value = ImportState.Idle

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
        val currentId = _activeTabId.value
        val existingList = _tabs.value.map { tab ->
            if (tab.id == currentId) {
                val updatedTab = tab.copy(currentPage = pageIndex)
                tab.manhwa?.let { manhwa ->
                    viewModelScope.launch {
                        repository.updateManhwa(manhwa.copy(lastReadPage = pageIndex))
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
                repository.importPdf(uri)
                _importingState.value = ImportState.Success("Successfully imported!")
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
            } catch (e: Exception) {
                null
            }
        }
        return renderer?.pageCount ?: 1
    }

    fun setCurrentPage(pageIndex: Int) {
        val pageCount = getPageCountForActiveManhwa()
        if (pageIndex >= 0 && pageIndex < pageCount) {
            updateActiveTabCurrentPage(pageIndex)
        }
    }

    suspend fun getPageAspectRatio(pageIndex: Int): Float {
        val tab = _tabs.value.find { it.id == _activeTabId.value } ?: return 1.414f
        val manhwa = tab.manhwa ?: return 1.414f
        val file = File(manhwa.filePath)
        if (!file.exists()) return 1.414f

        val renderer = synchronized(renderers) {
            renderers.getOrPut(manhwa.id) {
                ManhwaPdfRenderer(application, file)
            }
        }
        return renderer.getPageAspectRatio(pageIndex)
    }

    suspend fun renderPageSlice(pageIndex: Int, targetWidth: Int, sliceIndex: Int, sliceHeight: Int): Bitmap? {
        val tab = _tabs.value.find { it.id == _activeTabId.value } ?: return null
        val manhwa = tab.manhwa ?: return null
        val file = File(manhwa.filePath)
        if (!file.exists()) return null

        val renderer = synchronized(renderers) {
            renderers.getOrPut(manhwa.id) {
                ManhwaPdfRenderer(application, file)
            }
        }
        val scale = if (_hdModeEnabled.value) 2.0f else 1.2f

        // Prefetch adjacent slices / pages asynchronously
        viewModelScope.launch(Dispatchers.IO) {
            val totalWidth = (targetWidth * scale).toInt().coerceAtLeast(400)
            val aspect = renderer.getPageAspectRatio(pageIndex)
            val totalHeight = (totalWidth * aspect).toInt().coerceAtLeast(400)
            val maxSlices = Math.ceil(totalHeight.toDouble() / sliceHeight).toInt()

            if (sliceIndex + 1 < maxSlices) {
                renderer.renderPageSlice(pageIndex, targetWidth, sliceIndex + 1, sliceHeight, scale)
            }
            if (sliceIndex - 1 >= 0) {
                renderer.renderPageSlice(pageIndex, targetWidth, sliceIndex - 1, sliceHeight, scale)
            }
        }

        return renderer.renderPageSlice(pageIndex, targetWidth, sliceIndex, sliceHeight, scale)
    }

    suspend fun renderPage(pageIndex: Int, targetWidth: Int): Bitmap? {
        val tab = _tabs.value.find { it.id == _activeTabId.value } ?: return null
        val manhwa = tab.manhwa ?: return null
        val file = File(manhwa.filePath)
        if (!file.exists()) return null

        val renderer = synchronized(renderers) {
            renderers.getOrPut(manhwa.id) {
                ManhwaPdfRenderer(application, file)
            }
        }
        val scale = if (_hdModeEnabled.value) 2.0f else 1.2f

        // Prefetch adjacent pages asynchronously to enable high-performance seamless vertical scrolling
        viewModelScope.launch(Dispatchers.IO) {
            val totalPages = renderer.pageCount
            if (pageIndex + 1 < totalPages) {
                renderer.renderPage(pageIndex + 1, targetWidth, scale)
            }
            if (pageIndex - 1 >= 0) {
                renderer.renderPage(pageIndex - 1, targetWidth, scale)
            }
            if (pageIndex + 2 < totalPages) {
                renderer.renderPage(pageIndex + 2, targetWidth, scale)
            }
        }

        return renderer.renderPage(pageIndex, targetWidth, scale)
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
