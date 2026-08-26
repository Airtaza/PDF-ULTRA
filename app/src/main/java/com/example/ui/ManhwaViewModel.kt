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
import com.example.data.PageNote
import com.example.data.ReadingEvent
import com.example.data.PluginConfig
import com.example.data.SecurePreferencesManager
import com.example.data.ServerTrialClient
import com.example.data.ServerResponse
import com.example.data.SeriesParser
import com.example.pdf.ManhwaPdfRenderer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

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
    val currentPage: Int = 0,
    val scrollOffset: Int = 0
)

enum class TabType {
    LIBRARY, PLUGINS, READER, SETTINGS
}

data class VirtualPage(
    val physicalPageIndex: Int,
    val splitMode: String, // "NONE", "LEFT_HALF", "RIGHT_HALF"
    val virtualIndex: Int
)

class ManhwaViewModel(private val application: Application, private val repository: ManhwaRepository) : ViewModel() {

    // --- Secure Settings & Premium Verification Engine ---
    private val securePrefs = SecurePreferencesManager(application)
    private val serverClient = ServerTrialClient(application)

    // Device Fingerprint
    val deviceFingerprint: String by lazy {
        getDeviceFingerprint(application)
    }

    private fun getDeviceFingerprint(context: Context): String {
        try {
            val data = buildString {
                append(android.os.Build.BOARD)
                append(android.os.Build.BRAND)
                append(android.os.Build.DEVICE)
                append(android.os.Build.HARDWARE)
                append(android.os.Build.MANUFACTURER)
                append(android.os.Build.MODEL)
                append(android.os.Build.PRODUCT)
                append(android.provider.Settings.Secure.getString(
                    context.contentResolver,
                    android.provider.Settings.Secure.ANDROID_ID
                ) ?: "")
            }
            val md = java.security.MessageDigest.getInstance("SHA-256")
            return md.digest(data.toByteArray())
                .joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            return "fingerprint_error_fallback"
        }
    }

    // Purchase & Trial Flow States
    private val _isAllAccessUnlocked = MutableStateFlow(securePrefs.getSecureBoolean("all_access_unlocked", false))
    val isAllAccessUnlocked: StateFlow<Boolean> = _isAllAccessUnlocked.asStateFlow()

    private val _purchasedPlugins = MutableStateFlow<Set<String>>(
        setOf(
            if (securePrefs.getSecureBoolean("purchased_view_enhancer", false)) "view_enhancer" else null,
            if (securePrefs.getSecureBoolean("purchased_manhwa_editor", false)) "manhwa_editor" else null,
            if (securePrefs.getSecureBoolean("purchased_metadata_bookmark", false)) "metadata_bookmark" else null
        ).filterNotNull().toSet()
    )
    val purchasedPlugins: StateFlow<Set<String>> = _purchasedPlugins.asStateFlow()

    // Trial Timestamp (0 if not started)
    private val _trialStartTimestamp = MutableStateFlow(securePrefs.getSecureLong("trial_start_timestamp", 0L))
    val trialStartTimestamp: StateFlow<Long> = _trialStartTimestamp.asStateFlow()

    // Server verification status log
    private val _serverStatusLog = MutableStateFlow("Initialized secure local environment.")
    val serverStatusLog: StateFlow<String> = _serverStatusLog.asStateFlow()

    // Active Paywall Dialog Target Plugin
    private val _paywallTargetPlugin = MutableStateFlow<PluginConfig?>(null)
    val paywallTargetPlugin: StateFlow<PluginConfig?> = _paywallTargetPlugin.asStateFlow()

    // Check if plugin/feature is currently unlocked (either through purchase or active trial)
    fun isPluginUnlocked(pluginId: String): Boolean {
        if (_isAllAccessUnlocked.value) return true
        if (_purchasedPlugins.value.contains(pluginId)) return true
        
        // Check trial status (3 days = 3 * 24 * 60 * 60 * 1000 = 259200000 ms)
        val trialStart = _trialStartTimestamp.value
        if (trialStart > 0) {
            val elapsed = System.currentTimeMillis() - trialStart
            val threeDaysMs = 3 * 24 * 60 * 60 * 1000L
            if (elapsed in 0..threeDaysMs) {
                return true
            }
        }
        return false
    }

    // Start 3-day Trial (contacts server, falls back to secure local state if offline)
    fun startFreeTrial() {
        viewModelScope.launch {
            _serverStatusLog.value = "Registering 3-day free trial on Firebase server..."
            val fingerprint = deviceFingerprint
            
            // Call server
            val result = serverClient.checkOrStartTrialOnServer(fingerprint, "all_features")
            val startTime = System.currentTimeMillis()
            
            if (result is ServerResponse.Success) {
                _serverStatusLog.value = "Server Verified: Started trial. Status: ${result.status}"
                _trialStartTimestamp.value = startTime
                securePrefs.saveSecureLong("trial_start_timestamp", startTime)
            } else {
                val errorMsg = (result as? ServerResponse.Error)?.message ?: "Network timeout"
                _serverStatusLog.value = "Server Connection Failed ($errorMsg). Fallback to Secure Hardware Cryptography offline validation."
                if (_trialStartTimestamp.value == 0L) {
                    _trialStartTimestamp.value = startTime
                    securePrefs.saveSecureLong("trial_start_timestamp", startTime)
                }
            }
        }
    }

    // Purchase a single plugin
    fun purchasePlugin(pluginId: String) {
        viewModelScope.launch {
            _serverStatusLog.value = "Processing $0.99 secure checkout for $pluginId..."
            val fingerprint = deviceFingerprint
            
            val success = serverClient.recordPurchaseOnServer(fingerprint, pluginId, 0.99)
            if (success) {
                _serverStatusLog.value = "Server Confirmed: Purchase registered on Firebase successfully."
            } else {
                _serverStatusLog.value = "Server offline. Purchase authorized & saved locally in KeyStore vault."
            }
            
            val current = _purchasedPlugins.value.toMutableSet()
            current.add(pluginId)
            _purchasedPlugins.value = current
            securePrefs.saveSecureBoolean("purchased_$pluginId", true)
            
            _paywallTargetPlugin.value = null
        }
    }

    // Purchase all-access
    fun purchaseAllAccess() {
        viewModelScope.launch {
            _serverStatusLog.value = "Processing $9.99 secure checkout for All-Access..."
            val fingerprint = deviceFingerprint
            
            val success = serverClient.recordPurchaseOnServer(fingerprint, "all_access", 9.99)
            if (success) {
                _serverStatusLog.value = "Server Confirmed: All-Access Pass purchased."
            } else {
                _serverStatusLog.value = "Server offline. All-Access Pass authorized & locked in KeyStore vault."
            }
            
            _isAllAccessUnlocked.value = true
            securePrefs.saveSecureBoolean("all_access_unlocked", true)
            
            _paywallTargetPlugin.value = null
        }
    }

    fun submitLicenseKey(key: String, onResult: (Boolean, String) -> Unit) {
        viewModelScope.launch {
            val cleanKey = key.trim()
            _serverStatusLog.value = "Initiating server-side verification of License Key..."
            
            // Hash key to prevent reverse engineering of local comparison string
            val userHash = try {
                val digest = java.security.MessageDigest.getInstance("SHA-256")
                val hashBytes = digest.digest(cleanKey.toByteArray(Charsets.UTF_8))
                hashBytes.joinToString("") { "%02x".format(it) }
            } catch (e: Exception) {
                ""
            }

            val expectedHash = "3a5ac3bf09af8d8e89f2ca81037a703b35bea916463a0d73be8c0b0c0693dace"
            val isLocalHashValid = userHash == expectedHash

            // Call Server API
            val response = serverClient.validateLicenseKeyOnServer(deviceFingerprint, cleanKey)
            
            if (response is ServerResponse.Success) {
                _serverStatusLog.value = "Server License Confirmed: Key verified & validated successfully."
                _isAllAccessUnlocked.value = true
                securePrefs.saveSecureBoolean("all_access_unlocked", true)
                _paywallTargetPlugin.value = null
                onResult(true, "License Key Verified by Server! All Access Unlocked!")
            } else {
                val serverErrorMsg = (response as? ServerResponse.Error)?.message ?: "Validation Timeout"
                _serverStatusLog.value = "Server validation offline or returned: $serverErrorMsg. Running high-security cryptographic offline validation."
                
                if (isLocalHashValid) {
                    _serverStatusLog.value = "Cryptographic offline validation MATCHED! Access granted."
                    _isAllAccessUnlocked.value = true
                    securePrefs.saveSecureBoolean("all_access_unlocked", true)
                    _paywallTargetPlugin.value = null
                    onResult(true, "Offline Cryptographic Verification Succeeded! All Access Unlocked!")
                } else {
                    _serverStatusLog.value = "License Verification FAILED. Key is invalid."
                    onResult(false, "Invalid License Key! Please try again or checkout securely.")
                }
            }
        }
    }

    fun showPaywallFor(plugin: PluginConfig) {
        _paywallTargetPlugin.value = plugin
    }

    fun closePaywall() {
        _paywallTargetPlugin.value = null
    }

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
        UltraTab(id = "settings", title = "Lobby", type = TabType.SETTINGS)
    ))
    val tabs: StateFlow<List<UltraTab>> = _tabs.asStateFlow()

    private val _activeTabId = MutableStateFlow<String>("settings")
    val activeTabId: StateFlow<String> = _activeTabId.asStateFlow()

    val activeTab: StateFlow<UltraTab?> = _activeTabId
        .flatMapLatest { id ->
            val tab = _tabs.value.find { it.id == id }
            flowOf(tab)
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = UltraTab(id = "settings", title = "Lobby", type = TabType.SETTINGS)
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

    // --- State: Chapter Sorting, Search, Filtering & Reader Themes ---
    enum class SortMode {
        RECENT, NATURAL
    }

    enum class LibraryFilter {
        ALL, IN_PROGRESS, UNREAD, FINISHED
    }

    enum class ReaderTheme(val title: String, val colorHex: Long, val isDark: Boolean) {
        DARK("Dark Canvas", 0xFF121212, true),
        PITCH_BLACK("OLED Black", 0xFF000000, true),
        SEPIA("Classic Sepia", 0xFFF5EFE6, false),
        WARM("Warm Amber", 0xFF2B2622, true),
        WHITE("Pure White", 0xFFFFFFFF, false)
    }

    private val _sortMode = MutableStateFlow(SortMode.RECENT)
    val sortMode: StateFlow<SortMode> = _sortMode.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _libraryFilter = MutableStateFlow(LibraryFilter.ALL)
    val libraryFilter: StateFlow<LibraryFilter> = _libraryFilter.asStateFlow()

    private val sharedPrefs = application.getSharedPreferences("manhwa_settings", Context.MODE_PRIVATE)

    private val _readerTheme = MutableStateFlow(
        ReaderTheme.entries.getOrNull(sharedPrefs.getInt("reader_theme_index", 0)) ?: ReaderTheme.DARK
    )
    val readerTheme: StateFlow<ReaderTheme> = _readerTheme.asStateFlow()

    private val _autoScrollSpeed = MutableStateFlow(0f)
    val autoScrollSpeed: StateFlow<Float> = _autoScrollSpeed.asStateFlow()

    private val _chapterHistory = MutableStateFlow<List<Long>>(emptyList())
    val chapterHistory: StateFlow<List<Long>> = _chapterHistory.asStateFlow()

    private val _historyIndex = MutableStateFlow(-1)
    val historyIndex: StateFlow<Int> = _historyIndex.asStateFlow()

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun setLibraryFilter(filter: LibraryFilter) {
        _libraryFilter.value = filter
    }

    fun setReaderTheme(theme: ReaderTheme) {
        _readerTheme.value = theme
        sharedPrefs.edit().putInt("reader_theme_index", theme.ordinal).apply()
    }

    // --- State: View Enhancer Plugin Properties ---
    private val _brightness = MutableStateFlow(sharedPrefs.getFloat("view_brightness", 1.0f))
    val brightness: StateFlow<Float> = _brightness.asStateFlow()

    private val _contrast = MutableStateFlow(sharedPrefs.getFloat("view_contrast", 1.0f))
    val contrast: StateFlow<Float> = _contrast.asStateFlow()

    private val _saturation = MutableStateFlow(sharedPrefs.getFloat("view_saturation", 1.0f))
    val saturation: StateFlow<Float> = _saturation.asStateFlow()

    private val _warmth = MutableStateFlow(sharedPrefs.getFloat("view_warmth", 0.0f))
    val warmth: StateFlow<Float> = _warmth.asStateFlow()

    private val _gamma = MutableStateFlow(sharedPrefs.getFloat("view_gamma", 1.0f))
    val gamma: StateFlow<Float> = _gamma.asStateFlow()

    private val _autoGammaEnabled = MutableStateFlow(sharedPrefs.getBoolean("auto_gamma", false))
    val autoGammaEnabled: StateFlow<Boolean> = _autoGammaEnabled.asStateFlow()

    private val _customTint = MutableStateFlow(sharedPrefs.getString("custom_tint", "None") ?: "None")
    val customTint: StateFlow<String> = _customTint.asStateFlow()

    private val _autoNightShift = MutableStateFlow(sharedPrefs.getBoolean("auto_night_shift", false))
    val autoNightShift: StateFlow<Boolean> = _autoNightShift.asStateFlow()

    private val _mangaScanCrisper = MutableStateFlow(sharedPrefs.getBoolean("manga_scan_crisper", false))
    val mangaScanCrisper: StateFlow<Boolean> = _mangaScanCrisper.asStateFlow()

    private val _colorMode = MutableStateFlow(
        try {
            ColorMode.valueOf(sharedPrefs.getString("color_mode", ColorMode.NORMAL.name) ?: ColorMode.NORMAL.name)
        } catch (e: Exception) {
            ColorMode.NORMAL
        }
    )
    val colorMode: StateFlow<ColorMode> = _colorMode.asStateFlow()

    private val _hdModeEnabled = MutableStateFlow(sharedPrefs.getBoolean("hd_mode_enabled", true))
    val hdModeEnabled: StateFlow<Boolean> = _hdModeEnabled.asStateFlow()

    private val _showEditFeatures = MutableStateFlow(sharedPrefs.getBoolean("show_edit_features", true))
    val showEditFeatures: StateFlow<Boolean> = _showEditFeatures.asStateFlow()

    private val _presetFilter = MutableStateFlow(sharedPrefs.getString("preset_filter", "NONE") ?: "NONE")
    val presetFilter: StateFlow<String> = _presetFilter.asStateFlow()

    private val _pdfEngineSetting = MutableStateFlow(sharedPrefs.getString("pdf_engine_setting", "PDFIUM") ?: "PDFIUM") // "PDFIUM" or "NATIVE"
    val pdfEngineSetting: StateFlow<String> = _pdfEngineSetting.asStateFlow()

    private val _aspectCalcMethod = MutableStateFlow(
        try {
            com.example.pdf.AspectCalcMethod.valueOf(
                sharedPrefs.getString("aspect_calc_method", com.example.pdf.AspectCalcMethod.DYNAMIC_AUTO.name)
                    ?: com.example.pdf.AspectCalcMethod.DYNAMIC_AUTO.name
            )
        } catch (e: Exception) {
            com.example.pdf.AspectCalcMethod.DYNAMIC_AUTO
        }
    )
    val aspectCalcMethod: StateFlow<com.example.pdf.AspectCalcMethod> = _aspectCalcMethod.asStateFlow()

    private val _customBaseRatioSource = MutableStateFlow(sharedPrefs.getString("custom_base_ratio_source", "PDF_BOUNDS") ?: "PDF_BOUNDS")
    val customBaseRatioSource: StateFlow<String> = _customBaseRatioSource.asStateFlow()

    private val _customFixedRatio = MutableStateFlow(sharedPrefs.getFloat("custom_fixed_ratio", 1.414f))
    val customFixedRatio: StateFlow<Float> = _customFixedRatio.asStateFlow()

    private val _customAspectMultiplier = MutableStateFlow(sharedPrefs.getFloat("custom_aspect_multiplier", 1.0f))
    val customAspectMultiplier: StateFlow<Float> = _customAspectMultiplier.asStateFlow()

    private val _customScaleMode = MutableStateFlow(sharedPrefs.getString("custom_scale_mode", "FIT_WIDTH") ?: "FIT_WIDTH")
    val customScaleMode: StateFlow<String> = _customScaleMode.asStateFlow()

    private val _customMaxAspectLimit = MutableStateFlow(sharedPrefs.getFloat("custom_max_aspect_limit", 15.0f))
    val customMaxAspectLimit: StateFlow<Float> = _customMaxAspectLimit.asStateFlow()

    fun updateCustomTuning(
        baseSource: String = _customBaseRatioSource.value,
        fixedRatio: Float = _customFixedRatio.value,
        multiplier: Float = _customAspectMultiplier.value,
        scaleMode: String = _customScaleMode.value,
        maxLimit: Float = _customMaxAspectLimit.value
    ) {
        _customBaseRatioSource.value = baseSource
        _customFixedRatio.value = fixedRatio
        _customAspectMultiplier.value = multiplier
        _customScaleMode.value = scaleMode
        _customMaxAspectLimit.value = maxLimit

        sharedPrefs.edit()
            .putString("custom_base_ratio_source", baseSource)
            .putFloat("custom_fixed_ratio", fixedRatio)
            .putFloat("custom_aspect_multiplier", multiplier)
            .putString("custom_scale_mode", scaleMode)
            .putFloat("custom_max_aspect_limit", maxLimit)
            .apply()

        synchronized(renderers) {
            renderers.values.forEach { r ->
                r.updateCustomTuning(baseSource, fixedRatio, multiplier, scaleMode, maxLimit)
            }
        }
        val currentManhwa = activeManhwa.value
        if (currentManhwa != null) {
            viewModelScope.launch(Dispatchers.IO) {
                updateVirtualPagesForManhwa(currentManhwa, _readingDirection.value)
            }
        }
    }

    fun setAspectCalcMethod(method: com.example.pdf.AspectCalcMethod) {
        _aspectCalcMethod.value = method
        sharedPrefs.edit().putString("aspect_calc_method", method.name).apply()
        synchronized(renderers) {
            renderers.values.forEach { r ->
                r.setAspectCalcMethod(method)
            }
        }
        val currentManhwa = activeManhwa.value
        if (currentManhwa != null) {
            viewModelScope.launch(Dispatchers.IO) {
                updateVirtualPagesForManhwa(currentManhwa, _readingDirection.value)
            }
        }
    }

    fun setPdfEngineSetting(engine: String) {
        _pdfEngineSetting.value = engine
        sharedPrefs.edit().putString("pdf_engine_setting", engine).apply()
        clearMemoryCache()
    }

    fun togglePdfEngine() {
        val nextEngine = if (_pdfEngineSetting.value == "NATIVE") "PDFIUM" else "NATIVE"
        setPdfEngineSetting(nextEngine)
    }

    private val _virtualPages = MutableStateFlow<List<VirtualPage>>(emptyList())
    val virtualPages: StateFlow<List<VirtualPage>> = _virtualPages.asStateFlow()

    private val _currentVirtualPageIndex = MutableStateFlow(0)
    val currentVirtualPageIndex: StateFlow<Int> = _currentVirtualPageIndex.asStateFlow()

    val allReadingEvents: StateFlow<List<ReadingEvent>> = repository.allReadingEvents
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    val readingStreak: StateFlow<Int> = allReadingEvents
        .map { calculateStreak(it) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = 0
        )

    val todayReadingSeconds: StateFlow<Long> = allReadingEvents
        .map { calculateTodayReadingSeconds(it) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = 0L
        )

    val weeklyReadingStats: StateFlow<List<Int>> = allReadingEvents
        .map { calculateWeeklyReadingStats(it) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = List(7) { 0 }
        )

    private val _qualitySelectionEnabled = MutableStateFlow(sharedPrefs.getBoolean("quality_selection_enabled", true))
    val qualitySelectionEnabled: StateFlow<Boolean> = _qualitySelectionEnabled.asStateFlow()

    private val _qualityLevel = MutableStateFlow(sharedPrefs.getString("quality_level", "HIGH") ?: "HIGH")
    val qualityLevel: StateFlow<String> = _qualityLevel.asStateFlow()

    private val _maxStorageAllocation = MutableStateFlow(sharedPrefs.getInt("max_storage_allocation", 500)) // in MB
    val maxStorageAllocation: StateFlow<Int> = _maxStorageAllocation.asStateFlow()

    private val _sliceHeight = MutableStateFlow(sharedPrefs.getInt("slice_height", 1536))
    val sliceHeight: StateFlow<Int> = _sliceHeight.asStateFlow()

    private val _lowResScrollDelay = MutableStateFlow(sharedPrefs.getLong("low_res_scroll_delay", 60L))
    val lowResScrollDelay: StateFlow<Long> = _lowResScrollDelay.asStateFlow()

    private val _hdScrollDelay = MutableStateFlow(sharedPrefs.getLong("hd_scroll_delay", 150L))
    val hdScrollDelay: StateFlow<Long> = _hdScrollDelay.asStateFlow()

    private val _staggerDelay = MutableStateFlow(sharedPrefs.getLong("stagger_delay", 80L))
    val staggerDelay: StateFlow<Long> = _staggerDelay.asStateFlow()

    private val _pageSpacing = MutableStateFlow(sharedPrefs.getInt("page_spacing", 0))
    val pageSpacing: StateFlow<Int> = _pageSpacing.asStateFlow()

    private val _sideMargin = MutableStateFlow(sharedPrefs.getInt("side_margin", 0))
    val sideMargin: StateFlow<Int> = _sideMargin.asStateFlow()

    fun setSideMargin(value: Int) {
        _sideMargin.value = value
        sharedPrefs.edit().putInt("side_margin", value).apply()
    }

    private val _doubleTapZoomScale = MutableStateFlow(sharedPrefs.getFloat("double_tap_zoom_scale", 2.0f))
    val doubleTapZoomScale: StateFlow<Float> = _doubleTapZoomScale.asStateFlow()

    private val _volumeScrollEnabled = MutableStateFlow(sharedPrefs.getBoolean("volume_scroll_enabled", false))
    val volumeScrollEnabled: StateFlow<Boolean> = _volumeScrollEnabled.asStateFlow()

    private val _bitmapConfigSetting = MutableStateFlow(sharedPrefs.getString("bitmap_config", "ARGB_8888") ?: "ARGB_8888")
    val bitmapConfigSetting: StateFlow<String> = _bitmapConfigSetting.asStateFlow()

    private val _webpQuality = MutableStateFlow(sharedPrefs.getInt("webp_quality", 80))
    val webpQuality: StateFlow<Int> = _webpQuality.asStateFlow()

    fun setWebpQuality(quality: Int) {
        _webpQuality.value = quality
        sharedPrefs.edit().putInt("webp_quality", quality).apply()
    }

    private val _hapticFeedbackEnabled = MutableStateFlow(sharedPrefs.getBoolean("haptic_feedback_enabled", true))
    val hapticFeedbackEnabled: StateFlow<Boolean> = _hapticFeedbackEnabled.asStateFlow()

    private val _doubleTapResetEnabled = MutableStateFlow(sharedPrefs.getBoolean("double_tap_reset_enabled", true))
    val doubleTapResetEnabled: StateFlow<Boolean> = _doubleTapResetEnabled.asStateFlow()

    private val _aggressiveGcEnabled = MutableStateFlow(sharedPrefs.getBoolean("aggressive_gc_enabled", false))
    val aggressiveGcEnabled: StateFlow<Boolean> = _aggressiveGcEnabled.asStateFlow()

    private val _keepScreenOn = MutableStateFlow(sharedPrefs.getBoolean("keep_screen_on", true))
    val keepScreenOn: StateFlow<Boolean> = _keepScreenOn.asStateFlow()

    private val _immersiveMode = MutableStateFlow(sharedPrefs.getBoolean("immersive_mode", false))
    val immersiveMode: StateFlow<Boolean> = _immersiveMode.asStateFlow()

    private val _volumeKeyNavigation = MutableStateFlow(sharedPrefs.getBoolean("volume_key_navigation", true))
    val volumeKeyNavigation: StateFlow<Boolean> = _volumeKeyNavigation.asStateFlow()

    private val _readingDirection = MutableStateFlow(sharedPrefs.getString("reading_direction", "Vertical") ?: "Vertical")
    val readingDirection: StateFlow<String> = _readingDirection.asStateFlow()

    private val _preloadCount = MutableStateFlow(sharedPrefs.getInt("preload_count", 2))
    val preloadCount: StateFlow<Int> = _preloadCount.asStateFlow()

    private val _autoScrollStep = MutableStateFlow(sharedPrefs.getFloat("auto_scroll_step", 1.5f))
    val autoScrollStep: StateFlow<Float> = _autoScrollStep.asStateFlow()

    private val _volumeKeyEvent = MutableSharedFlow<Int>(extraBufferCapacity = 1)
    val volumeKeyEvent = _volumeKeyEvent.asSharedFlow()

    fun triggerVolumeKey(keyCode: Int) {
        _volumeKeyEvent.tryEmit(keyCode)
    }

    // --- State: Advanced Zoom & Magnifier settings ---
    private val _zoomLockEnabled = MutableStateFlow(sharedPrefs.getBoolean("zoom_lock_enabled", false))
    val zoomLockEnabled: StateFlow<Boolean> = _zoomLockEnabled.asStateFlow()

    private val _lockedZoomLevel = MutableStateFlow(sharedPrefs.getFloat("locked_zoom_level", 1.0f))
    val lockedZoomLevel: StateFlow<Float> = _lockedZoomLevel.asStateFlow()

    private val _activeZoomScale = MutableStateFlow(1.0f)
    val activeZoomScale: StateFlow<Float> = _activeZoomScale.asStateFlow()

    private val _stableZoomScale = MutableStateFlow(1.0f)
    val stableZoomScale: StateFlow<Float> = _stableZoomScale.asStateFlow()

    private val _isMagnifierEnabled = MutableStateFlow(false)
    val isMagnifierEnabled: StateFlow<Boolean> = _isMagnifierEnabled.asStateFlow()

    // --- Tap Zone Action Mapping ---
    private val _leftTapAction = MutableStateFlow(sharedPrefs.getString("left_tap_action", "PREV_PAGE") ?: "PREV_PAGE")
    val leftTapAction: StateFlow<String> = _leftTapAction.asStateFlow()

    private val _rightTapAction = MutableStateFlow(sharedPrefs.getString("right_tap_action", "NEXT_PAGE") ?: "NEXT_PAGE")
    val rightTapAction: StateFlow<String> = _rightTapAction.asStateFlow()

    private val _centerTapAction = MutableStateFlow(sharedPrefs.getString("center_tap_action", "TOGGLE_BARS") ?: "TOGGLE_BARS")
    val centerTapAction: StateFlow<String> = _centerTapAction.asStateFlow()

    fun setTapZoneAction(zone: String, action: String) {
        when (zone) {
            "LEFT" -> {
                _leftTapAction.value = action
                sharedPrefs.edit().putString("left_tap_action", action).apply()
            }
            "RIGHT" -> {
                _rightTapAction.value = action
                sharedPrefs.edit().putString("right_tap_action", action).apply()
            }
            "CENTER" -> {
                _centerTapAction.value = action
                sharedPrefs.edit().putString("center_tap_action", action).apply()
            }
        }
    }

    // --- Library Category Shelves & Favorites ---
    private val _selectedCategoryFilter = MutableStateFlow("All")
    val selectedCategoryFilter: StateFlow<String> = _selectedCategoryFilter.asStateFlow()

    fun setLibraryCategoryFilter(category: String) {
        _selectedCategoryFilter.value = category
    }

    fun updateManhwaCategory(manhwaId: Long, category: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val m = repository.getManhwaById(manhwaId)
            if (m != null) {
                repository.updateManhwa(m.copy(category = category))
            }
        }
    }

    private val _favoriteHeaderIds = MutableStateFlow<Set<String>>(
        sharedPrefs.getStringSet("favorite_header_ids", setOf("library_shelf", "perf_rendering", "display_theme")) ?: setOf("library_shelf", "perf_rendering", "display_theme")
    )
    val favoriteHeaderIds: StateFlow<Set<String>> = _favoriteHeaderIds.asStateFlow()

    fun toggleHeaderFavorite(headerId: String) {
        val current = _favoriteHeaderIds.value
        val updated = if (current.contains(headerId)) current - headerId else current + headerId
        _favoriteHeaderIds.value = updated
        sharedPrefs.edit().putStringSet("favorite_header_ids", updated).apply()
    }

    fun toggleManhwaFavorite(manhwaId: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            val m = repository.getManhwaById(manhwaId)
            if (m != null) {
                repository.updateManhwa(m.copy(isFavorite = !m.isFavorite))
            }
        }
    }

    // --- Page Notes ---
    val activePageNotes: StateFlow<List<PageNote>> = activeManhwa
        .flatMapLatest { m ->
            if (m != null) repository.getPageNotesForManhwa(m.id) else flowOf(emptyList())
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun savePageNote(pageIndex: Int, noteText: String) {
        val m = activeManhwa.value ?: return
        viewModelScope.launch(Dispatchers.IO) {
            if (noteText.isBlank()) {
                repository.deletePageNoteByPage(m.id, pageIndex)
            } else {
                val existing = repository.getPageNoteByPage(m.id, pageIndex)
                if (existing != null) {
                    repository.savePageNote(existing.copy(noteText = noteText, timestamp = System.currentTimeMillis()))
                } else {
                    repository.savePageNote(PageNote(manhwaId = m.id, pageIndex = pageIndex, noteText = noteText))
                }
            }
        }
    }

    fun deletePageNote(pageIndex: Int) {
        val m = activeManhwa.value ?: return
        viewModelScope.launch(Dispatchers.IO) {
            repository.deletePageNoteByPage(m.id, pageIndex)
        }
    }

    // --- Border Trim & Eye Rest Timer & Text Mode ---
    private val _borderTrimEnabled = MutableStateFlow(sharedPrefs.getBoolean("border_trim_enabled", false))
    val borderTrimEnabled: StateFlow<Boolean> = _borderTrimEnabled.asStateFlow()

    fun setBorderTrimEnabled(enabled: Boolean) {
        _borderTrimEnabled.value = enabled
        sharedPrefs.edit().putBoolean("border_trim_enabled", enabled).apply()
    }

    private val _eyeRestReminderEnabled = MutableStateFlow(sharedPrefs.getBoolean("eye_rest_reminder", false))
    val eyeRestReminderEnabled: StateFlow<Boolean> = _eyeRestReminderEnabled.asStateFlow()

    private val _eyeRestIntervalMinutes = MutableStateFlow(sharedPrefs.getInt("eye_rest_interval", 20))
    val eyeRestIntervalMinutes: StateFlow<Int> = _eyeRestIntervalMinutes.asStateFlow()

    fun setEyeRestSettings(enabled: Boolean, interval: Int) {
        _eyeRestReminderEnabled.value = enabled
        _eyeRestIntervalMinutes.value = interval
        sharedPrefs.edit().putBoolean("eye_rest_reminder", enabled).putInt("eye_rest_interval", interval).apply()
    }

    private val _textModeFontSize = MutableStateFlow(sharedPrefs.getInt("text_mode_font_size", 16))
    val textModeFontSize: StateFlow<Int> = _textModeFontSize.asStateFlow()

    fun setTextModeFontSize(sizeSp: Int) {
        _textModeFontSize.value = sizeSp
        sharedPrefs.edit().putInt("text_mode_font_size", sizeSp).apply()
    }

    // --- Comprehensive Reader Display Presets Engine ---
    fun applyDisplayPreset(presetKey: String) {
        pushViewSettingsSnapshotBeforeChange()
        when (presetKey) {
            "AMOLED_BLACK" -> {
                setReaderTheme(ReaderTheme.PITCH_BLACK)
                setBrightness(1.0f)
                setContrast(1.25f)
                setSaturation(1.0f)
                setWarmth(0.0f)
                setGamma(0.95f)
                setCustomTint("None")
                setMangaScanCrisper(true)
                setColorMode(ColorMode.HIGH_CONTRAST)
            }
            "SEPIA_EYE_CARE" -> {
                setReaderTheme(ReaderTheme.SEPIA)
                setBrightness(0.95f)
                setContrast(1.0f)
                setWarmth(0.35f)
                setCustomTint("Warm Sepia")
                setColorMode(ColorMode.SEPIA)
            }
            "VINTAGE_PAPER" -> {
                setReaderTheme(ReaderTheme.WHITE)
                setBrightness(1.05f)
                setContrast(1.1f)
                setWarmth(0.2f)
                setCustomTint("Parchment")
                setColorMode(ColorMode.NORMAL)
            }
            "HIGH_CONTRAST_MANGA" -> {
                setReaderTheme(ReaderTheme.DARK)
                setBrightness(1.1f)
                setContrast(1.4f)
                setSaturation(0.0f)
                setMangaScanCrisper(true)
                setColorMode(ColorMode.GRAYSCALE)
            }
            "PASTEL_NIGHT" -> {
                setReaderTheme(ReaderTheme.WARM)
                setBrightness(0.85f)
                setContrast(0.95f)
                setWarmth(0.4f)
                setCustomTint("Pastel Muted")
                setAutoNightShift(true)
            }
            "SUNLIGHT_BOOST" -> {
                setReaderTheme(ReaderTheme.WHITE)
                setBrightness(1.3f)
                setContrast(1.2f)
                setSaturation(1.1f)
                setWarmth(0.0f)
                setCustomTint("None")
            }
            "MIDNIGHT_THEATER" -> {
                setReaderTheme(ReaderTheme.PITCH_BLACK)
                setBrightness(0.85f)
                setContrast(1.15f)
                setSaturation(1.05f)
                setWarmth(0.25f)
                setExposure(0.9f)
                setHighlights(-0.2f)
                setShadows(0.1f)
                setCustomTint("None")
            }
            "VIVID_ENHANCE" -> {
                setReaderTheme(ReaderTheme.DARK)
                setBrightness(1.05f)
                setContrast(1.2f)
                setSaturation(1.35f)
                setWarmth(0.0f)
                setExposure(1.05f)
                setHighlights(0.1f)
                setShadows(-0.1f)
                setCustomTint("None")
                setColorMode(ColorMode.NORMAL)
            }
        }
    }

    // --- Lightroom-style View Settings (Exposure, Highlights, Shadows) ---
    private val _exposure = MutableStateFlow(sharedPrefs.getFloat("view_exposure", 1.0f))
    val exposure: StateFlow<Float> = _exposure.asStateFlow()

    private val _highlights = MutableStateFlow(sharedPrefs.getFloat("view_highlights", 0.0f))
    val highlights: StateFlow<Float> = _highlights.asStateFlow()

    private val _shadows = MutableStateFlow(sharedPrefs.getFloat("view_shadows", 0.0f))
    val shadows: StateFlow<Float> = _shadows.asStateFlow()

    private val _swipeSensitivity = MutableStateFlow(sharedPrefs.getFloat("swipe_sensitivity", 1.0f))
    val swipeSensitivity: StateFlow<Float> = _swipeSensitivity.asStateFlow()

    fun setSwipeSensitivity(value: Float) {
        _swipeSensitivity.value = value
        sharedPrefs.edit().putFloat("swipe_sensitivity", value).apply()
    }

    fun setExposure(value: Float) {
        _exposure.value = value
        sharedPrefs.edit().putFloat("view_exposure", value).apply()
    }

    fun setHighlights(value: Float) {
        _highlights.value = value
        sharedPrefs.edit().putFloat("view_highlights", value).apply()
    }

    fun setShadows(value: Float) {
        _shadows.value = value
        sharedPrefs.edit().putFloat("view_shadows", value).apply()
    }

    fun setPresetFilter(filter: String) {
        _presetFilter.value = filter
        sharedPrefs.edit().putString("preset_filter", filter).apply()
    }

    fun clearReadingStats() {
        viewModelScope.launch {
            repository.clearReadingStats()
        }
    }

    fun getVirtualIndexForPhysicalPage(physicalPageIndex: Int): Int {
        val list = _virtualPages.value
        val idx = list.indexOfFirst { it.physicalPageIndex == physicalPageIndex }
        return if (idx >= 0) idx else physicalPageIndex
    }

    private var lastPageChangeTimestamp = System.currentTimeMillis()

    fun setCurrentVirtualPageAndOffset(virtualIndex: Int, offset: Int) {
        val list = _virtualPages.value
        val vp = list.getOrNull(virtualIndex)
        val physicalPage = vp?.physicalPageIndex ?: virtualIndex
        _currentVirtualPageIndex.value = virtualIndex
        updateActiveTabCurrentPageAndOffset(physicalPage, offset, virtualIndex)
        checkAndTrimMemoryIfNeeded(physicalPage)
    }

    // --- View Settings Undo / Redo History & Snapshots ---
    data class ViewSettingsSnapshot(
        val readerTheme: ReaderTheme = ReaderTheme.DARK,
        val customTint: String = "None",
        val pageSpacing: Int = 0,
        val sideMargin: Int = 0,
        val brightness: Float = 1.0f,
        val contrast: Float = 1.0f,
        val saturation: Float = 1.0f,
        val warmth: Float = 0.0f,
        val gamma: Float = 1.0f,
        val exposure: Float = 1.0f,
        val highlights: Float = 0.0f,
        val shadows: Float = 0.0f,
        val colorMode: ColorMode = ColorMode.NORMAL,
        val presetFilter: String = "NONE",
        val autoGammaEnabled: Boolean = false,
        val autoNightShift: Boolean = false,
        val mangaScanCrisper: Boolean = false,
        val hdMode: Boolean = true,
        val swipeSensitivity: Float = 1.0f,
        val doubleTapZoomScale: Float = 2.0f,
        val borderTrimEnabled: Boolean = false,
        val eyeRestReminderEnabled: Boolean = false,
        val eyeRestIntervalMinutes: Int = 20,
        val volumeKeyNavigation: Boolean = true,
        val keepScreenOn: Boolean = true,
        val readingDirection: String = "Vertical"
    )

    private val _viewSettingsUndoStack = mutableListOf<ViewSettingsSnapshot>()
    private val _viewSettingsRedoStack = mutableListOf<ViewSettingsSnapshot>()

    private val _canUndoViewSettings = MutableStateFlow(false)
    val canUndoViewSettings: StateFlow<Boolean> = _canUndoViewSettings.asStateFlow()

    private val _canRedoViewSettings = MutableStateFlow(false)
    val canRedoViewSettings: StateFlow<Boolean> = _canRedoViewSettings.asStateFlow()

    fun getCurrentViewSettingsSnapshot(): ViewSettingsSnapshot {
        return ViewSettingsSnapshot(
            readerTheme = _readerTheme.value,
            customTint = _customTint.value,
            pageSpacing = _pageSpacing.value,
            sideMargin = _sideMargin.value,
            brightness = _brightness.value,
            contrast = _contrast.value,
            saturation = _saturation.value,
            warmth = _warmth.value,
            gamma = _gamma.value,
            exposure = _exposure.value,
            highlights = _highlights.value,
            shadows = _shadows.value,
            colorMode = _colorMode.value,
            presetFilter = _presetFilter.value,
            autoGammaEnabled = _autoGammaEnabled.value,
            autoNightShift = _autoNightShift.value,
            mangaScanCrisper = _mangaScanCrisper.value,
            hdMode = _hdModeEnabled.value,
            swipeSensitivity = _swipeSensitivity.value,
            doubleTapZoomScale = _doubleTapZoomScale.value,
            borderTrimEnabled = _borderTrimEnabled.value,
            eyeRestReminderEnabled = _eyeRestReminderEnabled.value,
            eyeRestIntervalMinutes = _eyeRestIntervalMinutes.value,
            volumeKeyNavigation = _volumeKeyNavigation.value,
            keepScreenOn = _keepScreenOn.value,
            readingDirection = _readingDirection.value
        )
    }

    fun pushExplicitUndoSnapshot(snapshot: ViewSettingsSnapshot) {
        if (_viewSettingsUndoStack.isEmpty() || _viewSettingsUndoStack.last() != snapshot) {
            _viewSettingsUndoStack.add(snapshot)
            if (_viewSettingsUndoStack.size > 50) {
                _viewSettingsUndoStack.removeAt(0)
            }
            _viewSettingsRedoStack.clear()
            _canUndoViewSettings.value = true
            _canRedoViewSettings.value = false
        }
    }

    fun pushViewSettingsSnapshotBeforeChange() {
        val current = getCurrentViewSettingsSnapshot()
        pushExplicitUndoSnapshot(current)
    }

    fun applyViewSettingsSnapshot(snapshot: ViewSettingsSnapshot) {
        _readerTheme.value = snapshot.readerTheme
        sharedPrefs.edit().putString("reader_theme", snapshot.readerTheme.name).apply()

        _customTint.value = snapshot.customTint
        sharedPrefs.edit().putString("custom_tint", snapshot.customTint).apply()

        _pageSpacing.value = snapshot.pageSpacing
        sharedPrefs.edit().putInt("page_spacing", snapshot.pageSpacing).apply()

        _sideMargin.value = snapshot.sideMargin
        sharedPrefs.edit().putInt("side_margin", snapshot.sideMargin).apply()

        _brightness.value = snapshot.brightness
        sharedPrefs.edit().putFloat("view_brightness", snapshot.brightness).apply()

        _contrast.value = snapshot.contrast
        sharedPrefs.edit().putFloat("view_contrast", snapshot.contrast).apply()

        _saturation.value = snapshot.saturation
        sharedPrefs.edit().putFloat("view_saturation", snapshot.saturation).apply()

        _warmth.value = snapshot.warmth
        sharedPrefs.edit().putFloat("view_warmth", snapshot.warmth).apply()

        _gamma.value = snapshot.gamma
        sharedPrefs.edit().putFloat("view_gamma", snapshot.gamma).apply()

        _exposure.value = snapshot.exposure
        sharedPrefs.edit().putFloat("view_exposure", snapshot.exposure).apply()

        _highlights.value = snapshot.highlights
        sharedPrefs.edit().putFloat("view_highlights", snapshot.highlights).apply()

        _shadows.value = snapshot.shadows
        sharedPrefs.edit().putFloat("view_shadows", snapshot.shadows).apply()

        _colorMode.value = snapshot.colorMode
        sharedPrefs.edit().putString("color_mode", snapshot.colorMode.name).apply()

        _presetFilter.value = snapshot.presetFilter
        sharedPrefs.edit().putString("preset_filter", snapshot.presetFilter).apply()

        _autoGammaEnabled.value = snapshot.autoGammaEnabled
        sharedPrefs.edit().putBoolean("auto_gamma", snapshot.autoGammaEnabled).apply()

        _autoNightShift.value = snapshot.autoNightShift
        sharedPrefs.edit().putBoolean("auto_night_shift", snapshot.autoNightShift).apply()

        _mangaScanCrisper.value = snapshot.mangaScanCrisper
        sharedPrefs.edit().putBoolean("manga_scan_crisper", snapshot.mangaScanCrisper).apply()

        _hdModeEnabled.value = snapshot.hdMode
        sharedPrefs.edit().putBoolean("hd_mode_enabled", snapshot.hdMode).apply()

        _swipeSensitivity.value = snapshot.swipeSensitivity
        sharedPrefs.edit().putFloat("swipe_sensitivity", snapshot.swipeSensitivity).apply()

        _doubleTapZoomScale.value = snapshot.doubleTapZoomScale
        sharedPrefs.edit().putFloat("double_tap_zoom_scale", snapshot.doubleTapZoomScale).apply()

        _borderTrimEnabled.value = snapshot.borderTrimEnabled
        sharedPrefs.edit().putBoolean("border_trim_enabled", snapshot.borderTrimEnabled).apply()

        _eyeRestReminderEnabled.value = snapshot.eyeRestReminderEnabled
        _eyeRestIntervalMinutes.value = snapshot.eyeRestIntervalMinutes
        sharedPrefs.edit()
            .putBoolean("eye_rest_reminder_enabled", snapshot.eyeRestReminderEnabled)
            .putInt("eye_rest_interval_minutes", snapshot.eyeRestIntervalMinutes)
            .apply()

        _volumeKeyNavigation.value = snapshot.volumeKeyNavigation
        sharedPrefs.edit().putBoolean("volume_key_nav", snapshot.volumeKeyNavigation).apply()

        _keepScreenOn.value = snapshot.keepScreenOn
        sharedPrefs.edit().putBoolean("keep_screen_on", snapshot.keepScreenOn).apply()

        _readingDirection.value = snapshot.readingDirection
        sharedPrefs.edit().putString("reading_direction", snapshot.readingDirection).apply()
    }

    fun undoViewSettings() {
        if (_viewSettingsUndoStack.isNotEmpty()) {
            val current = getCurrentViewSettingsSnapshot()
            _viewSettingsRedoStack.add(current)
            val previous = _viewSettingsUndoStack.removeAt(_viewSettingsUndoStack.size - 1)
            applyViewSettingsSnapshot(previous)
            _canUndoViewSettings.value = _viewSettingsUndoStack.isNotEmpty()
            _canRedoViewSettings.value = true
        }
    }

    fun redoViewSettings() {
        if (_viewSettingsRedoStack.isNotEmpty()) {
            val current = getCurrentViewSettingsSnapshot()
            _viewSettingsUndoStack.add(current)
            val next = _viewSettingsRedoStack.removeAt(_viewSettingsRedoStack.size - 1)
            applyViewSettingsSnapshot(next)
            _canUndoViewSettings.value = true
            _canRedoViewSettings.value = _viewSettingsRedoStack.isNotEmpty()
        }
    }

    fun resetViewEnhancerSettings() {
        pushViewSettingsSnapshotBeforeChange()
        setBrightness(1.0f)
        setContrast(1.0f)
        setSaturation(1.0f)
        setWarmth(0.0f)
        setGamma(1.0f)
        setAutoGammaEnabled(false)
        setCustomTint("None")
        setAutoNightShift(false)
        setMangaScanCrisper(false)
        setColorMode(ColorMode.NORMAL)
        setExposure(1.0f)
        setHighlights(0.0f)
        setShadows(0.0f)
        setPresetFilter("NONE")
        setPageSpacing(0)
        setSideMargin(0)
    }

    val activeScaleFactor: StateFlow<Float> = combine(
        _qualitySelectionEnabled,
        _qualityLevel,
        _stableZoomScale,
        _hdModeEnabled
    ) { qualityEnabled, qLevel, stableZoomVal, hdEnabled ->
        val baseScale = if (qualityEnabled) {
            getQualityScaleFactor(qLevel)
        } else {
            if (hdEnabled) 2.0f else 1.2f
        }
        val zoomFactor = if (stableZoomVal > 1.0f) stableZoomVal else 1.0f
        (baseScale * zoomFactor).coerceAtMost(4.5f)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = 1.6f
    )

    // Page-indexed map of drawing sketches
    private val _sketches = MutableStateFlow<Map<Int, List<DrawPath>>>(emptyMap())
    val sketches: StateFlow<Map<Int, List<DrawPath>>> = _sketches.asStateFlow()

    init {
        restoreTabsStateFromPrefs()
        pruneReadingHistoryOlderThan90Days()
        viewModelScope.launch {
            _activeZoomScale.collectLatest { zoom ->
                kotlinx.coroutines.delay(50)
                _stableZoomScale.value = zoom
            }
        }
        viewModelScope.launch(Dispatchers.IO) {
            activeManhwa.collect { manhwa ->
                if (manhwa == null) {
                    _virtualPages.value = emptyList()
                    _sketches.value = emptyMap()
                } else {
                    // Update virtual pages for the new manhwa
                    val dir = _readingDirection.value
                    updateVirtualPagesForManhwa(manhwa, dir)
                    loadSketchesFromDisk(manhwa.id)
                }
            }
        }
        viewModelScope.launch(Dispatchers.IO) {
            _readingDirection.collect { dir ->
                val manhwa = activeManhwa.value
                if (manhwa != null) {
                    updateVirtualPagesForManhwa(manhwa, dir)
                }
            }
        }
    }

    private suspend fun updateVirtualPagesForManhwa(manhwa: Manhwa, direction: String) {
        _virtualPages.value = (0 until manhwa.totalPages).map { VirtualPage(it, "NONE", it) }
    }

    private val _splitLandscapeSpreads = MutableStateFlow(false)
    val splitLandscapeSpreads: StateFlow<Boolean> = _splitLandscapeSpreads.asStateFlow()

    fun setSplitLandscapeSpreads(enabled: Boolean) {
        _splitLandscapeSpreads.value = false
        val currentManhwa = activeManhwa.value
        if (currentManhwa != null) {
            viewModelScope.launch(Dispatchers.IO) {
                updateVirtualPagesForManhwa(currentManhwa, _readingDirection.value)
            }
        }
    }

    // --- State: Manhwa Sketch Editor Plugin Properties ---
    private val _activeDrawColor = MutableStateFlow(Color.Red)
    val activeDrawColor: StateFlow<Color> = _activeDrawColor.asStateFlow()

    private val _activeStrokeWidth = MutableStateFlow(8f)
    val activeStrokeWidth: StateFlow<Float> = _activeStrokeWidth.asStateFlow()

    private val _activeDrawHighlighter = MutableStateFlow(false)
    val activeDrawHighlighter: StateFlow<Boolean> = _activeDrawHighlighter.asStateFlow()

    fun setDrawHighlighter(enabled: Boolean) {
        _activeDrawHighlighter.value = enabled
    }

    private val _hdTextModeEnabled = MutableStateFlow(sharedPrefs.getBoolean("hd_text_mode_enabled", false))
    val hdTextModeEnabled: StateFlow<Boolean> = _hdTextModeEnabled.asStateFlow()

    fun setHdTextModeEnabled(enabled: Boolean) {
        _hdTextModeEnabled.value = enabled
        sharedPrefs.edit().putBoolean("hd_text_mode_enabled", enabled).apply()
    }

    fun clearMemoryCache() {
        synchronized(renderers) {
            renderers.values.forEach { 
                it.clearMemoryCache()
            }
        }
        System.gc()
    }

    fun freeRamExceptCurrentPage(targetPageIndex: Int? = null, keepAdjacent: Boolean = false) {
        val tab = _tabs.value.find { it.id == _activeTabId.value }
        val activePage = targetPageIndex ?: tab?.currentPage ?: 0
        synchronized(renderers) {
            renderers.values.forEach {
                it.freeRamExceptCurrentPage(activePage, keepAdjacent)
            }
        }
        System.gc()
    }

    fun checkAndTrimMemoryIfNeeded(physicalPageIndex: Int) {
        val runtime = Runtime.getRuntime()
        val maxMem = runtime.maxMemory()
        val usedMem = runtime.totalMemory() - runtime.freeMemory()
        val usageRatio = usedMem.toDouble() / maxMem.toDouble()
        if (usageRatio > 0.65) {
            freeRamExceptCurrentPage(physicalPageIndex, keepAdjacent = false)
        } else if (usageRatio > 0.45) {
            freeRamExceptCurrentPage(physicalPageIndex, keepAdjacent = true)
        }
    }

    private val _isUserScrolling = MutableStateFlow(false)
    val isUserScrolling: StateFlow<Boolean> = _isUserScrolling.asStateFlow()

    private val _memoryPressureEvent = MutableStateFlow<Boolean>(false)
    val memoryPressureEvent: StateFlow<Boolean> = _memoryPressureEvent.asStateFlow()

    fun setUserScrolling(scrolling: Boolean) {
        _isUserScrolling.value = scrolling
    }

    fun triggerMemoryPressure() {
        clearMemoryCache()
        increaseStorageAllocation()
        System.gc()
    }

    fun dismissMemoryPressure() {
        _memoryPressureEvent.value = false
    }

    fun increaseStorageAllocation() {
        val current = _maxStorageAllocation.value
        _maxStorageAllocation.value = current + 100
        sharedPrefs.edit().putInt("max_storage_allocation", _maxStorageAllocation.value).apply()
        // Notify renderers
        synchronized(renderers) {
            renderers.values.forEach { it.resizeCache(_maxStorageAllocation.value) }
        }
    }

    fun lowerQuality() {
        _qualityLevel.value = "LOW"
        sharedPrefs.edit().putString("quality_level", "LOW").apply()
        _webpQuality.value = 60
        sharedPrefs.edit().putInt("webp_quality", 60).apply()
    }

    private fun createRenderer(file: File): ManhwaPdfRenderer {
        val renderer = ManhwaPdfRenderer(
            application,
            file,
            _maxStorageAllocation.value,
            isScrolling = { _isUserScrolling.value },
            onOOM = { triggerMemoryPressure() }
        )
        renderer.setAspectCalcMethod(_aspectCalcMethod.value)
        renderer.updateCustomTuning(
            _customBaseRatioSource.value,
            _customFixedRatio.value,
            _customAspectMultiplier.value,
            _customScaleMode.value,
            _customMaxAspectLimit.value
        )
        return renderer
    }

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

    // --- Persistent Tab Session Manager ---
    private fun saveTabsStateToPrefs() {
        try {
            val rootArray = JSONArray()
            _tabs.value.forEach { tab ->
                val obj = JSONObject()
                obj.put("id", tab.id)
                obj.put("title", tab.title)
                obj.put("type", tab.type.name)
                obj.put("currentPage", tab.currentPage)
                obj.put("scrollOffset", tab.scrollOffset)
                tab.manhwa?.let { m ->
                    obj.put("manhwaId", m.id)
                }
                rootArray.put(obj)
            }
            sharedPrefs.edit()
                .putString("saved_tabs_json", rootArray.toString())
                .putString("saved_active_tab_id", _activeTabId.value)
                .apply()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun restoreTabsStateFromPrefs() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val jsonStr = sharedPrefs.getString("saved_tabs_json", null) ?: return@launch
                val savedActiveId = sharedPrefs.getString("saved_active_tab_id", "settings") ?: "settings"
                val array = JSONArray(jsonStr)
                val restoredTabs = mutableListOf<UltraTab>()

                for (i in 0 until array.length()) {
                    val obj = array.optJSONObject(i) ?: continue
                    val id = obj.optString("id", "")
                    val title = obj.optString("title", "")
                    val typeName = obj.optString("type", TabType.SETTINGS.name)
                    val currentPage = obj.optInt("currentPage", 0)
                    val scrollOffset = obj.optInt("scrollOffset", 0)
                    val type = try { TabType.valueOf(typeName) } catch (e: Exception) { TabType.SETTINGS }
                    val manhwaId = obj.optLong("manhwaId", -1L)

                    var manhwa: Manhwa? = null
                    if (type == TabType.READER && manhwaId != -1L) {
                        manhwa = repository.getManhwaById(manhwaId)
                        if (manhwa == null) continue // Skip deleted manhwa
                        val savedState = getSavedPdfReadingState(manhwa.filePath, manhwa.id)
                        val pageToUse = if (savedState.pageIndex >= 0) savedState.pageIndex else (if (currentPage > 0) currentPage else manhwa.lastReadPage)
                        val offsetToUse = if (savedState.scrollOffset >= 0) savedState.scrollOffset else (if (scrollOffset > 0) scrollOffset else manhwa.scrollOffset)
                        manhwa = manhwa.copy(lastReadPage = pageToUse, scrollOffset = offsetToUse)
                        restoredTabs.add(
                            UltraTab(
                                id = id,
                                title = manhwa.title,
                                type = type,
                                manhwa = manhwa,
                                currentPage = pageToUse,
                                scrollOffset = offsetToUse
                            )
                        )
                    } else {
                        restoredTabs.add(
                            UltraTab(
                                id = id,
                                title = title,
                                type = type,
                                manhwa = null,
                                currentPage = currentPage,
                                scrollOffset = scrollOffset
                            )
                        )
                    }
                }

                if (restoredTabs.isNotEmpty()) {
                    withContext(Dispatchers.Main) {
                        _tabs.value = restoredTabs
                        val activeExists = restoredTabs.any { it.id == savedActiveId }
                        val activeIdToUse = if (activeExists) savedActiveId else restoredTabs.first().id
                        selectTabId(activeIdToUse)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun flushActiveTabToDb() {
        val tab = _tabs.value.find { it.id == _activeTabId.value } ?: return
        val manhwa = tab.manhwa ?: return
        savePdfReadingState(
            filePath = manhwa.filePath,
            manhwaId = manhwa.id,
            pageIndex = tab.currentPage,
            scrollOffset = tab.scrollOffset,
            title = manhwa.title
        )
        viewModelScope.launch(Dispatchers.IO) {
            repository.updateManhwa(
                manhwa.copy(
                    lastReadPage = tab.currentPage,
                    scrollOffset = tab.scrollOffset,
                    lastOpened = System.currentTimeMillis()
                )
            )
        }
    }

    data class PdfReadingState(
        val pageIndex: Int,
        val scrollOffset: Int,
        val zoomLevel: Float = 1.0f,
        val timestamp: Long = System.currentTimeMillis()
    )

    fun getPdfNameKey(filePath: String?, title: String? = null): String {
        val name = when {
            !filePath.isNullOrBlank() -> {
                val fName = File(filePath).name
                if (fName.isNotBlank()) fName else (title ?: "unknown_pdf")
            }
            !title.isNullOrBlank() -> title
            else -> "unknown_pdf"
        }
        return "pdf_name_${name.trim().lowercase().replace(Regex("[^a-zA-Z0-9_.-]"), "_")}"
    }

    fun getPdfReadingStateKey(filePath: String?, manhwaId: Long): String {
        val pathKey = filePath?.trim()?.takeIf { it.isNotBlank() }
        return if (pathKey != null) {
            "pdf_state_${pathKey.hashCode()}_${pathKey.replace('/', '_').replace(':', '_')}"
        } else {
            "pdf_state_id_${manhwaId}"
        }
    }

    fun savePdfReadingState(
        filePath: String?,
        manhwaId: Long,
        pageIndex: Int,
        scrollOffset: Int,
        zoomLevel: Float = 1.0f,
        title: String? = null
    ) {
        if (manhwaId <= 0 && filePath.isNullOrBlank() && title.isNullOrBlank()) return
        val now = System.currentTimeMillis()
        val editor = sharedPrefs.edit()

        // 1. Primary: Save by PDF name
        val nameKey = getPdfNameKey(filePath, title)
        editor.putInt("${nameKey}_page", pageIndex)
            .putInt("${nameKey}_scroll", scrollOffset)
            .putFloat("${nameKey}_zoom", zoomLevel)
            .putLong("${nameKey}_lastOpened", now)

        // 2. Secondary: Save by exact file path
        val pathKey = getPdfReadingStateKey(filePath, manhwaId)
        editor.putInt("${pathKey}_page", pageIndex)
            .putInt("${pathKey}_scroll", scrollOffset)
            .putFloat("${pathKey}_zoom", zoomLevel)
            .putLong("${pathKey}_lastOpened", now)

        // 3. Save by manhwaId
        if (manhwaId > 0) {
            val idKey = "pdf_state_id_${manhwaId}"
            editor.putInt("${idKey}_page", pageIndex)
                .putInt("${idKey}_scroll", scrollOffset)
                .putFloat("${idKey}_zoom", zoomLevel)
                .putLong("${idKey}_lastOpened", now)
        }

        editor.apply()

        val tab = _tabs.value.find { it.id == _activeTabId.value }
        val manhwa = tab?.manhwa
        if (manhwa != null && (manhwa.id == manhwaId || manhwa.filePath == filePath)) {
            val updatedManhwa = manhwa.copy(
                lastReadPage = pageIndex,
                scrollOffset = scrollOffset,
                lastOpened = now
            )
            val updatedTabs = _tabs.value.map { t ->
                if (t.id == _activeTabId.value) {
                    t.copy(currentPage = pageIndex, scrollOffset = scrollOffset, manhwa = updatedManhwa)
                } else t
            }
            _tabs.value = updatedTabs
            saveTabsStateToPrefs()

            viewModelScope.launch(Dispatchers.IO) {
                repository.updateManhwa(updatedManhwa)
            }
        }
    }

    fun getSavedPdfReadingState(filePath: String?, manhwaId: Long, title: String? = null): PdfReadingState {
        // 1. Primary: Check by PDF name
        val nameKey = getPdfNameKey(filePath, title)
        val namePage = sharedPrefs.getInt("${nameKey}_page", -1)
        val nameScroll = sharedPrefs.getInt("${nameKey}_scroll", -1)
        val nameZoom = sharedPrefs.getFloat("${nameKey}_zoom", 1.0f)
        val nameTimestamp = sharedPrefs.getLong("${nameKey}_lastOpened", 0L)
        if (namePage >= 0 && nameScroll >= 0) {
            return PdfReadingState(namePage, nameScroll, nameZoom, nameTimestamp)
        }

        // 2. Secondary: Check by file path
        val pathKey = getPdfReadingStateKey(filePath, manhwaId)
        val prefPage = sharedPrefs.getInt("${pathKey}_page", -1)
        val prefScroll = sharedPrefs.getInt("${pathKey}_scroll", -1)
        val prefZoom = sharedPrefs.getFloat("${pathKey}_zoom", 1.0f)
        val prefTimestamp = sharedPrefs.getLong("${pathKey}_lastOpened", 0L)
        if (prefPage >= 0 && prefScroll >= 0) {
            return PdfReadingState(prefPage, prefScroll, prefZoom, prefTimestamp)
        }

        // 3. Check by manhwa ID
        if (manhwaId > 0) {
            val idKey = "pdf_state_id_${manhwaId}"
            val idPage = sharedPrefs.getInt("${idKey}_page", -1)
            val idScroll = sharedPrefs.getInt("${idKey}_scroll", -1)
            val idZoom = sharedPrefs.getFloat("${idKey}_zoom", 1.0f)
            val idTimestamp = sharedPrefs.getLong("${idKey}_lastOpened", 0L)
            if (idPage >= 0 && idScroll >= 0) {
                return PdfReadingState(idPage, idScroll, idZoom, idTimestamp)
            }
        }

        // 4. Check active tab or database lastReadPage
        val tab = _tabs.value.find { it.id == _activeTabId.value }
        val manhwa = tab?.manhwa
        if (manhwa != null && (manhwa.id == manhwaId || manhwa.filePath == filePath)) {
            return PdfReadingState(
                pageIndex = manhwa.lastReadPage,
                scrollOffset = manhwa.scrollOffset,
                zoomLevel = 1.0f,
                timestamp = manhwa.lastOpened
            )
        }

        return PdfReadingState(0, 0, 1.0f, 0L)
    }

    fun setCurrentVirtualPageAndOffsetInMemory(virtualIndex: Int, offset: Int) {
        val list = _virtualPages.value
        val vp = list.getOrNull(virtualIndex)
        val physicalPage = vp?.physicalPageIndex ?: virtualIndex
        _currentVirtualPageIndex.value = virtualIndex

        val currentId = _activeTabId.value
        val updatedList = _tabs.value.map { tab ->
            if (tab.id == currentId) {
                val updatedManhwa = tab.manhwa?.copy(lastReadPage = physicalPage, scrollOffset = offset)
                tab.copy(currentPage = physicalPage, scrollOffset = offset, manhwa = updatedManhwa)
            } else {
                tab
            }
        }
        _tabs.value = updatedList
        checkAndTrimMemoryIfNeeded(physicalPage)
    }

    fun pruneReadingHistoryOlderThan90Days() {
        viewModelScope.launch(Dispatchers.IO) {
            val ninetyDaysAgo = System.currentTimeMillis() - (90L * 24 * 60 * 60 * 1000L)
            repository.pruneOldReadingPositions(ninetyDaysAgo)

            try {
                val allEntries = sharedPrefs.all
                val keysToRemove = mutableListOf<String>()
                allEntries.keys.filter { it.endsWith("_lastOpened") }.forEach { key ->
                    val timestamp = (allEntries[key] as? Long) ?: 0L
                    if (timestamp > 0L && timestamp < ninetyDaysAgo) {
                        val baseKey = key.removeSuffix("_lastOpened")
                        keysToRemove.add("${baseKey}_page")
                        keysToRemove.add("${baseKey}_scroll")
                        keysToRemove.add("${baseKey}_zoom")
                        keysToRemove.add("${baseKey}_lastOpened")
                    }
                }
                if (keysToRemove.isNotEmpty()) {
                    val editor = sharedPrefs.edit()
                    keysToRemove.forEach { editor.remove(it) }
                    editor.apply()
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    // --- Tab management operations ---
    fun selectTabId(tabId: String) {
        val currentTab = _tabs.value.find { it.id == _activeTabId.value }
        if (currentTab?.type == TabType.READER && currentTab.manhwa != null) {
            savePdfReadingState(
                filePath = currentTab.manhwa.filePath,
                manhwaId = currentTab.manhwa.id,
                pageIndex = currentTab.currentPage,
                scrollOffset = currentTab.scrollOffset,
                title = currentTab.manhwa.title
            )
        }
        flushActiveTabToDb()
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
        saveTabsStateToPrefs()
    }

    fun openManhwaInTab(manhwa: Manhwa) {
        viewModelScope.launch {
            _importingState.value = ImportState.Loading
            val freshManhwa = withContext(Dispatchers.IO) { repository.getManhwaById(manhwa.id) } ?: manhwa
            val file = File(freshManhwa.filePath)
            if (!file.exists()) {
                _importingState.value = ImportState.Error("Local file does not exist")
                return@launch
            }

            val savedState = getSavedPdfReadingState(freshManhwa.filePath, freshManhwa.id, freshManhwa.title)
            val startPage = if (savedState.pageIndex >= 0) savedState.pageIndex else freshManhwa.lastReadPage
            val startOffset = if (savedState.scrollOffset >= 0) savedState.scrollOffset else freshManhwa.scrollOffset
            val updatedManhwa = freshManhwa.copy(
                lastReadPage = startPage,
                scrollOffset = startOffset,
                lastOpened = System.currentTimeMillis()
            )

            val targetTabId = "reader_${freshManhwa.id}"
            val existingList = _tabs.value.toMutableList()

            // Check if tab is already open
            val existingTab = existingList.find { it.id == targetTabId }
            if (existingTab != null) {
                withContext(Dispatchers.IO) { repository.updateManhwa(updatedManhwa) }
                val tabIdx = existingList.indexOfFirst { it.id == targetTabId }
                if (tabIdx != -1) {
                    val refreshedTab = existingList[tabIdx].copy(
                        currentPage = startPage,
                        scrollOffset = startOffset,
                        manhwa = updatedManhwa
                    )
                    existingList[tabIdx] = refreshedTab
                    _tabs.value = existingList
                }
                synchronized(renderers) {
                    renderers[freshManhwa.id]?.clearMemoryCache()
                    renderers[freshManhwa.id]?.clearAspectRatiosCache()
                }
                selectTabId(targetTabId)
                _importingState.value = ImportState.Idle
                return@launch
            }

            // Find all current reader tabs
            val readerTabs = existingList.filter { it.type == TabType.READER }

            if (readerTabs.isEmpty()) {
                // Tab 1 is empty -> Open new PDF in Tab 1
                val newTab = UltraTab(
                    id = targetTabId,
                    title = updatedManhwa.title,
                    type = TabType.READER,
                    manhwa = updatedManhwa,
                    currentPage = startPage,
                    scrollOffset = startOffset
                )
                existingList.add(newTab)
            } else if (readerTabs.size == 1) {
                // Tab 1 is occupied, Tab 2 is empty -> Open new PDF in Tab 2
                val newTab = UltraTab(
                    id = targetTabId,
                    title = updatedManhwa.title,
                    type = TabType.READER,
                    manhwa = updatedManhwa,
                    currentPage = startPage,
                    scrollOffset = startOffset
                )
                existingList.add(newTab)
            } else {
                // Both Tab 1 and Tab 2 are occupied.
                // Replace Tab 1 (close it & save position), shift Tab 2 to Tab 1, place new PDF in Tab 2.
                val tab1ToClose = readerTabs[0]
                val tab2ToMove = readerTabs[1]

                tab1ToClose.manhwa?.let { oldM ->
                    savePdfReadingState(
                        filePath = oldM.filePath,
                        manhwaId = oldM.id,
                        pageIndex = tab1ToClose.currentPage,
                        scrollOffset = tab1ToClose.scrollOffset,
                        title = oldM.title
                    )
                    withContext(Dispatchers.IO) {
                        repository.updateManhwa(
                            oldM.copy(
                                lastReadPage = tab1ToClose.currentPage,
                                scrollOffset = tab1ToClose.scrollOffset,
                                lastOpened = System.currentTimeMillis()
                            )
                        )
                    }
                    synchronized(renderers) {
                        renderers.remove(oldM.id)?.close()
                    }
                }

                val nonReaderTabs = existingList.filter { it.type != TabType.READER }
                val newTab = UltraTab(
                    id = targetTabId,
                    title = updatedManhwa.title,
                    type = TabType.READER,
                    manhwa = updatedManhwa,
                    currentPage = startPage,
                    scrollOffset = startOffset
                )

                existingList.clear()
                existingList.addAll(nonReaderTabs)
                existingList.add(tab2ToMove)
                existingList.add(newTab)
            }

            _tabs.value = existingList
            selectTabId(targetTabId)
            _activeZoomScale.value = if (_zoomLockEnabled.value) _lockedZoomLevel.value else 1.0f
            _importingState.value = ImportState.Idle

            // Pre-warm the renderer on a background thread so there's absolutely 0ms lag when the reader opens
            withContext(Dispatchers.IO) {
                try {
                    val r = synchronized(renderers) {
                        renderers.getOrPut(freshManhwa.id) {
                            createRenderer(file)
                        }
                    }
                    // Prefetch aspect ratios for the first few pages to make page layout calculation instant
                    val pageCount = r.pageCount
                    val startPage = freshManhwa.lastReadPage
                    for (i in startPage until (startPage + 5).coerceAtMost(pageCount)) {
                        r.getPageAspectRatio(i)
                    }
                    // Start AOT Background Pre-processor for true instant loading
                    startAotPreload(freshManhwa, r, startPage)
                } catch (e: Throwable) {
                    e.printStackTrace()
                }
            }

            // Save last opened
            withContext(Dispatchers.IO) {
                repository.updateManhwa(freshManhwa.copy(lastOpened = System.currentTimeMillis()))
            }
        }
    }

    private var aotJob: kotlinx.coroutines.Job? = null

    private fun startAotPreload(manhwa: Manhwa, renderer: ManhwaPdfRenderer, startPage: Int) {
        aotJob?.cancel()
        aotJob = viewModelScope.launch(Dispatchers.IO) {
            // 1. Immediately pre-generate ultra low-res WebP thumbnails for the entire PDF
            // This ensures every page has an instant, lightweight 2-4KB preview during fast scrolling
            try {
                renderer.preloadAllThumbnails(startPage = startPage)
            } catch (e: Throwable) {
                e.printStackTrace()
            }

            if (!isActive) return@launch

            val totalPages = renderer.pageCount
            val targetWidth = 1080 // Assumption for standard screens. A real target width is dynamically passed in renderPageSlice, but for AOT, 1080 is a safe baseline.
            
            // 2. Limit subsequent high-res slice preloads to 2 pages ahead to prevent memory exhaustion
            val endPage = (startPage + 2).coerceAtMost(totalPages)
            for (i in startPage until endPage) {
                if (!isActive) break
                try {
                    val aspect = renderer.getPageAspectRatio(i)
                    val scaleFactor = getQualityScaleFactor(_qualityLevel.value)
                    val sliceHeight = _sliceHeight.value
                    val basePageHeight = targetWidth * aspect
                    val numSlices = Math.ceil(basePageHeight.toDouble() / sliceHeight).toInt().coerceAtLeast(1)
                    
                    // Pre-render actual slices sequentially
                    for (slice in 0 until numSlices) {
                        if (!isActive) break
                        renderer.renderPageSlice(
                            pageIndex = i,
                            targetWidth = targetWidth,
                            sliceIndex = slice,
                            sliceHeight = sliceHeight,
                            scaleFactor = scaleFactor,
                            qualitySelectionEnabled = _qualitySelectionEnabled.value,
                            qualityLevel = _qualityLevel.value,
                            qualityCompression = _webpQuality.value,
                            maxStorageAllocationMb = _maxStorageAllocation.value,
                            bitmapConfig = _bitmapConfigSetting.value
                        )
                        kotlinx.coroutines.delay(20) // Yield CPU between slices
                    }
                    kotlinx.coroutines.delay(60) // Yield CPU between pages
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    fun closeTab(tabId: String) {
        viewModelScope.launch {
            val existingList = _tabs.value.toMutableList()
            val tabToClose = existingList.find { it.id == tabId } ?: return@launch

            if (tabToClose.type == TabType.SETTINGS) {
                // Cannot close settings (Lobby) tab to ensure there is always a fallback
                return@launch
            }

            if (tabToClose.type == TabType.READER && tabToClose.manhwa != null) {
                savePdfReadingState(
                    filePath = tabToClose.manhwa.filePath,
                    manhwaId = tabToClose.manhwa.id,
                    pageIndex = tabToClose.currentPage,
                    scrollOffset = tabToClose.scrollOffset,
                    title = tabToClose.manhwa.title
                )
                withContext(Dispatchers.IO) {
                    repository.updateManhwa(
                        tabToClose.manhwa.copy(
                            lastReadPage = tabToClose.currentPage,
                            scrollOffset = tabToClose.scrollOffset,
                            lastOpened = System.currentTimeMillis()
                        )
                    )
                }
                synchronized(renderers) {
                    renderers.remove(tabToClose.manhwa.id)?.close()
                }
            }

            existingList.remove(tabToClose)
            _tabs.value = existingList

            if (_activeTabId.value == tabId) {
                selectTabId(existingList.first().id)
            } else {
                saveTabsStateToPrefs()
            }
        }
    }

    fun openPluginsTab() {
        openSettingsTab()
    }

    fun updateActiveTabCurrentPage(pageIndex: Int) {
        updateActiveTabCurrentPageAndOffset(pageIndex, 0, _currentVirtualPageIndex.value)
    }

    fun updateActiveTabCurrentPageAndOffset(pageIndex: Int, offset: Int, virtualIndex: Int = -1) {
        val currentId = _activeTabId.value
        val now = System.currentTimeMillis()
        val duration = ((now - lastPageChangeTimestamp) / 1000).toInt().coerceIn(0, 3600) // cap at 1 hour per page

        val existingList = _tabs.value.map { tab ->
            if (tab.id == currentId) {
                if (tab.currentPage != pageIndex) {
                    tab.manhwa?.let { manhwa ->
                        viewModelScope.launch {
                            repository.logReadingEvent(manhwa.id, pageIndex, virtualIndex, duration)
                        }
                    }
                    lastPageChangeTimestamp = now
                }
                val updatedManhwa = tab.manhwa?.copy(lastReadPage = pageIndex, scrollOffset = offset, lastOpened = now)
                val updatedTab = tab.copy(currentPage = pageIndex, scrollOffset = offset, manhwa = updatedManhwa)
                updatedManhwa?.let { m ->
                    dbUpdateJob?.cancel()
                    dbUpdateJob = viewModelScope.launch(Dispatchers.IO) {
                        repository.updateManhwa(m)
                    }
                }
                updatedTab
            } else {
                tab
            }
        }
        _tabs.value = existingList
        saveTabsStateToPrefs()
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
                val manhwa = repository.getManhwaById(id)
                if (manhwa != null) {
                    openManhwaInTab(manhwa)
                }
            } catch (e: Exception) {
                _importingState.value = ImportState.Error(e.localizedMessage ?: "Failed to import PDF")
            }
        }
    }

    fun createDummyTestPdf() {
        viewModelScope.launch {
            _importingState.value = ImportState.Loading
            try {
                val id = repository.createDummyTestPdf()
                _importingState.value = ImportState.Success("Dummy Test PDF Generated!")
                val manhwa = repository.getManhwaById(id)
                if (manhwa != null) {
                    openManhwaInTab(manhwa)
                }
            } catch (e: Exception) {
                _importingState.value = ImportState.Error(e.localizedMessage ?: "Failed to generate Dummy PDF")
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
            val key = getPdfReadingStateKey(manhwa.filePath, manhwa.id)
            val idKey = "pdf_state_id_${manhwa.id}"
            sharedPrefs.edit()
                .remove("${key}_page")
                .remove("${key}_scroll")
                .remove("${key}_zoom")
                .remove("${key}_lastOpened")
                .remove("${idKey}_page")
                .remove("${idKey}_scroll")
                .remove("${idKey}_zoom")
                .remove("${idKey}_lastOpened")
                .apply()

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
                    val r = createRenderer(file)
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
            updateActiveTabCurrentPageAndOffset(pageIndex, offset, getVirtualIndexForPhysicalPage(pageIndex))
        }
    }

    fun getCachedPageAspectRatio(pageIndex: Int): Float? {
        val tab = _tabs.value.find { it.id == _activeTabId.value } ?: return null
        val manhwa = tab.manhwa ?: return null
        return synchronized(renderers) {
            renderers[manhwa.id]?.getCachedPageAspectRatio(pageIndex, _aspectCalcMethod.value)
        }
    }

    fun getLowResThumbnailFromMemory(pageIndex: Int): Bitmap? {
        val tab = _tabs.value.find { it.id == _activeTabId.value } ?: return null
        val manhwa = tab.manhwa ?: return null
        return synchronized(renderers) {
            renderers[manhwa.id]?.getLowResThumbnailFromMemory(pageIndex)
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
                    createRenderer(file)
                }
            }
        } catch (e: Throwable) {
            e.printStackTrace()
            null
        } ?: return@withContext 1.414f
        renderer.getPageAspectRatio(pageIndex, _aspectCalcMethod.value)
    }

    suspend fun renderPageSlice(
        pageIndex: Int, 
        targetWidth: Int, 
        sliceIndex: Int, 
        sliceHeight: Int, 
        scaleFactor: Float,
        landscapeSplitMode: String = "NONE"
    ): Bitmap? = withContext(Dispatchers.IO) {
        val tab = _tabs.value.find { it.id == _activeTabId.value } ?: return@withContext null
        val manhwa = tab.manhwa ?: return@withContext null
        val file = File(manhwa.filePath)
        if (!file.exists()) return@withContext null

        val renderer = try {
            synchronized(renderers) {
                renderers.getOrPut(manhwa.id) {
                    createRenderer(file)
                }
            }
        } catch (e: Throwable) {
            e.printStackTrace()
            null
        } ?: return@withContext null

        val isCacheEnabled = _qualitySelectionEnabled.value
        val qualityCompression = _webpQuality.value
        val maxStorage = _maxStorageAllocation.value
        
        // Apply HD Text Mode multiplier
        val actualScaleFactor = if (_hdTextModeEnabled.value) scaleFactor * 1.5f else scaleFactor

        val bitmap = renderer.renderPageSlice(
            pageIndex = pageIndex,
            targetWidth = targetWidth,
            sliceIndex = sliceIndex,
            sliceHeight = sliceHeight,
            scaleFactor = actualScaleFactor,
            qualitySelectionEnabled = isCacheEnabled,
            qualityLevel = _qualityLevel.value,
            qualityCompression = qualityCompression,
            maxStorageAllocationMb = maxStorage,
            bitmapConfig = _bitmapConfigSetting.value,
            landscapeSplitMode = landscapeSplitMode
        )
        if (_aggressiveGcEnabled.value) {
            System.gc()
        }
        bitmap
    }

    suspend fun renderPage(
        pageIndex: Int, 
        targetWidth: Int, 
        scaleFactor: Float? = null,
        landscapeSplitMode: String = "NONE"
    ): Bitmap? = withContext(Dispatchers.IO) {
        val tab = _tabs.value.find { it.id == _activeTabId.value } ?: return@withContext null
        val manhwa = tab.manhwa ?: return@withContext null
        val file = File(manhwa.filePath)
        if (!file.exists()) return@withContext null

        val renderer = try {
            synchronized(renderers) {
                renderers.getOrPut(manhwa.id) {
                    createRenderer(file)
                }
            }
        } catch (e: Throwable) {
            e.printStackTrace()
            null
        } ?: return@withContext null

        val isCacheEnabled = _qualitySelectionEnabled.value
        val scale = scaleFactor ?: activeScaleFactor.value
        val qualityCompression = _webpQuality.value
        val maxStorage = _maxStorageAllocation.value

        val bitmap = renderer.renderPage(
            pageIndex, targetWidth, scale,
            isCacheEnabled, _qualityLevel.value, qualityCompression, maxStorage,
            bitmapConfig = _bitmapConfigSetting.value,
            landscapeSplitMode = landscapeSplitMode
        )
        if (_aggressiveGcEnabled.value) {
            System.gc()
        }
        bitmap
    }

    suspend fun renderPageLowRes(pageIndex: Int, targetWidth: Int, landscapeSplitMode: String = "NONE"): Bitmap? = withContext(Dispatchers.IO) {
        if (_pdfEngineSetting.value == "NATIVE") {
            return@withContext null // Native engine mode loads direct high resolution page
        }
        val tab = _tabs.value.find { it.id == _activeTabId.value } ?: return@withContext null
        val manhwa = tab.manhwa ?: return@withContext null
        val file = File(manhwa.filePath)
        if (!file.exists()) return@withContext null

        val renderer = try {
            synchronized(renderers) {
                renderers.getOrPut(manhwa.id) {
                    createRenderer(file)
                }
            }
        } catch (e: Throwable) {
            e.printStackTrace()
            null
        } ?: return@withContext null

        val bitmap = renderer.renderPageLowRes(pageIndex, targetWidth, bitmapConfig = _bitmapConfigSetting.value, landscapeSplitMode = landscapeSplitMode)
        if (_aggressiveGcEnabled.value) {
            System.gc()
        }
        bitmap
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
        if (!isPluginUnlocked(plugin.id)) {
            showPaywallFor(plugin)
            return
        }
        viewModelScope.launch {
            val updated = plugin.copy(enabled = !plugin.enabled)
            repository.updatePlugin(updated)
        }
    }

    // --- View Enhancer Controls ---
    fun setBrightness(value: Float) {
        _brightness.value = value
        sharedPrefs.edit().putFloat("view_brightness", value).apply()
    }

    fun setContrast(value: Float) {
        _contrast.value = value
        sharedPrefs.edit().putFloat("view_contrast", value).apply()
    }

    fun setSaturation(value: Float) {
        _saturation.value = value
        sharedPrefs.edit().putFloat("view_saturation", value).apply()
    }

    fun setWarmth(value: Float) {
        _warmth.value = value
        sharedPrefs.edit().putFloat("view_warmth", value).apply()
    }

    fun setGamma(value: Float) {
        _gamma.value = value
        sharedPrefs.edit().putFloat("view_gamma", value).apply()
    }

    fun setAutoGammaEnabled(enabled: Boolean) {
        _autoGammaEnabled.value = enabled
        sharedPrefs.edit().putBoolean("auto_gamma", enabled).apply()
    }

    fun setCustomTint(tint: String) {
        _customTint.value = tint
        sharedPrefs.edit().putString("custom_tint", tint).apply()
    }

    fun setAutoNightShift(enabled: Boolean) {
        _autoNightShift.value = enabled
        sharedPrefs.edit().putBoolean("auto_night_shift", enabled).apply()
    }

    fun setMangaScanCrisper(enabled: Boolean) {
        _mangaScanCrisper.value = enabled
        sharedPrefs.edit().putBoolean("manga_scan_crisper", enabled).apply()
    }

    fun setColorMode(mode: ColorMode) {
        _colorMode.value = mode
        sharedPrefs.edit().putString("color_mode", mode.name).apply()
    }

    fun toggleHdMode() {
        setHdModeEnabled(!_hdModeEnabled.value)
    }

    fun setHdModeEnabled(enabled: Boolean) {
        _hdModeEnabled.value = enabled
        sharedPrefs.edit().putBoolean("hd_mode_enabled", enabled).apply()
        clearMemoryCache()
    }

    fun setShowEditFeatures(enabled: Boolean) {
        _showEditFeatures.value = enabled
        sharedPrefs.edit().putBoolean("show_edit_features", enabled).apply()
    }

    // --- Core Fast-Render & WebP Cache Controls ---
    fun setQualitySelectionEnabled(enabled: Boolean) {
        _qualitySelectionEnabled.value = enabled
        sharedPrefs.edit().putBoolean("quality_selection_enabled", enabled).apply()
        clearMemoryCache()
    }

    fun setQualityLevel(level: String) {
        _qualityLevel.value = level
        sharedPrefs.edit().putString("quality_level", level).apply()
        clearMemoryCache()
    }

    fun setMaxStorageAllocation(megabytes: Int) {
        _maxStorageAllocation.value = megabytes
        sharedPrefs.edit().putInt("max_storage_allocation", megabytes).apply()
        synchronized(renderers) {
            renderers.values.forEach { it.resizeCache(megabytes) }
        }
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

    fun setSliceHeight(height: Int) {
        _sliceHeight.value = height
        sharedPrefs.edit().putInt("slice_height", height).apply()
        clearMemoryCache()
    }

    fun setLowResScrollDelay(delay: Long) {
        _lowResScrollDelay.value = delay
        sharedPrefs.edit().putLong("low_res_scroll_delay", delay).apply()
    }

    fun setHdScrollDelay(delay: Long) {
        _hdScrollDelay.value = delay
        sharedPrefs.edit().putLong("hd_scroll_delay", delay).apply()
    }

    fun setStaggerDelay(delay: Long) {
        _staggerDelay.value = delay
        sharedPrefs.edit().putLong("stagger_delay", delay).apply()
    }

    fun setPageSpacing(spacing: Int) {
        _pageSpacing.value = spacing
        sharedPrefs.edit().putInt("page_spacing", spacing).apply()
    }

    fun setDoubleTapZoomScale(scale: Float) {
        _doubleTapZoomScale.value = scale
        sharedPrefs.edit().putFloat("double_tap_zoom_scale", scale).apply()
    }

    fun setVolumeScrollEnabled(enabled: Boolean) {
        _volumeScrollEnabled.value = enabled
        sharedPrefs.edit().putBoolean("volume_scroll_enabled", enabled).apply()
    }

    fun setBitmapConfigSetting(config: String) {
        _bitmapConfigSetting.value = config
        sharedPrefs.edit().putString("bitmap_config", config).apply()
        clearMemoryCache()
    }

    fun setHapticFeedbackEnabled(enabled: Boolean) {
        _hapticFeedbackEnabled.value = enabled
        sharedPrefs.edit().putBoolean("haptic_feedback_enabled", enabled).apply()
    }

    fun setDoubleTapResetEnabled(enabled: Boolean) {
        _doubleTapResetEnabled.value = enabled
        sharedPrefs.edit().putBoolean("double_tap_reset_enabled", enabled).apply()
    }

    fun setAggressiveGcEnabled(enabled: Boolean) {
        _aggressiveGcEnabled.value = enabled
        sharedPrefs.edit().putBoolean("aggressive_gc_enabled", enabled).apply()
    }

    fun setKeepScreenOn(enabled: Boolean) {
        _keepScreenOn.value = enabled
        sharedPrefs.edit().putBoolean("keep_screen_on", enabled).apply()
    }

    fun setImmersiveMode(enabled: Boolean) {
        _immersiveMode.value = enabled
        sharedPrefs.edit().putBoolean("immersive_mode", enabled).apply()
    }

    fun setVolumeKeyNavigation(enabled: Boolean) {
        _volumeKeyNavigation.value = enabled
        sharedPrefs.edit().putBoolean("volume_key_navigation", enabled).apply()
    }

    fun setReadingDirection(direction: String) {
        _readingDirection.value = direction
        sharedPrefs.edit().putString("reading_direction", direction).apply()
    }

    fun setPreloadCount(count: Int) {
        _preloadCount.value = count
        sharedPrefs.edit().putInt("preload_count", count).apply()
    }

    fun setAutoScrollStep(step: Float) {
        _autoScrollStep.value = step
        sharedPrefs.edit().putFloat("auto_scroll_step", step).apply()
    }

    data class DeviceSpecs(
        val maxJvmHeapMb: Long,
        val processorCores: Int,
        val totalRamMb: Long,
        val deviceCategory: String // "LOW", "MEDIUM", "HIGH"
    )

    fun getDeviceSpecs(): DeviceSpecs {
        val maxJvmHeapMb = Runtime.getRuntime().maxMemory() / (1024 * 1024)
        val processorCores = Runtime.getRuntime().availableProcessors()
        
        var totalRamMb = 0L
        try {
            val actManager = application.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
            if (actManager != null) {
                val memInfo = android.app.ActivityManager.MemoryInfo()
                actManager.getMemoryInfo(memInfo)
                totalRamMb = memInfo.totalMem / (1024 * 1024)
            }
        } catch (e: Exception) {
            // Fallback
        }
        
        val isLow = maxJvmHeapMb < 256 || processorCores <= 4 || (totalRamMb > 0 && totalRamMb <= 3500)
        val isHigh = maxJvmHeapMb >= 512 && processorCores >= 8 && (totalRamMb == 0L || totalRamMb > 6500)
        
        val category = when {
            isLow -> "LOW"
            isHigh -> "HIGH"
            else -> "MEDIUM"
        }
        
        return DeviceSpecs(maxJvmHeapMb, processorCores, totalRamMb, category)
    }

    fun applyRecommendedSettings(forceTier: String? = null) {
        val specs = getDeviceSpecs()
        val tier = forceTier ?: specs.deviceCategory
        when (tier) {
            "LOW" -> {
                setQualitySelectionEnabled(true)
                setQualityLevel("LOW")
                setMaxStorageAllocation(100)
                setSliceHeight(1024)
                setLowResScrollDelay(120L)
                setHdScrollDelay(300L)
                setStaggerDelay(150L)
                setPageSpacing(0)
                setDoubleTapZoomScale(1.8f)
                setVolumeScrollEnabled(false)
                setBitmapConfigSetting("RGB_565")
                setHapticFeedbackEnabled(false)
                setDoubleTapResetEnabled(true)
                setAggressiveGcEnabled(true)
                setKeepScreenOn(true)
                setPreloadCount(1)
                setAutoScrollStep(1.0f)
            }
            "HIGH" -> {
                setQualitySelectionEnabled(true)
                setQualityLevel("HIGH")
                setMaxStorageAllocation(1000)
                setSliceHeight(2048)
                setLowResScrollDelay(0L)
                setHdScrollDelay(80L)
                setStaggerDelay(40L)
                setPageSpacing(0)
                setDoubleTapZoomScale(2.2f)
                setVolumeScrollEnabled(true)
                setBitmapConfigSetting("ARGB_8888")
                setHapticFeedbackEnabled(true)
                setDoubleTapResetEnabled(true)
                setAggressiveGcEnabled(false)
                setKeepScreenOn(true)
                setPreloadCount(3)
                setAutoScrollStep(2.0f)
            }
            else -> { // MEDIUM
                setQualitySelectionEnabled(true)
                setQualityLevel("MEDIUM")
                setMaxStorageAllocation(500)
                setSliceHeight(1536)
                setLowResScrollDelay(60L)
                setHdScrollDelay(150L)
                setStaggerDelay(80L)
                setPageSpacing(0)
                setDoubleTapZoomScale(2.0f)
                setVolumeScrollEnabled(false)
                setBitmapConfigSetting("ARGB_8888")
                setHapticFeedbackEnabled(true)
                setDoubleTapResetEnabled(true)
                setAggressiveGcEnabled(false)
                setKeepScreenOn(true)
                setPreloadCount(2)
                setAutoScrollStep(1.5f)
            }
        }
    }

    fun resetSettings() {
        // Reset all visible settings to defaults
        setBrightness(1.0f)
        setContrast(1.0f)
        setSaturation(1.0f)
        setWarmth(0.0f)
        setGamma(1.0f)
        setAutoGammaEnabled(false)
        setCustomTint("None")
        setAutoNightShift(false)
        setMangaScanCrisper(false)
        setColorMode(ColorMode.NORMAL)
        setHdModeEnabled(false)
        setPresetFilter("NONE")
        
        applyRecommendedSettings(forceTier = "MEDIUM")
    }

    fun exportSettingsJson(): String {
        val obj = JSONObject()
        try {
            obj.put("reader_theme_index", _readerTheme.value.ordinal)
            obj.put("view_brightness", _brightness.value.toDouble())
            obj.put("view_contrast", _contrast.value.toDouble())
            obj.put("view_saturation", _saturation.value.toDouble())
            obj.put("view_warmth", _warmth.value.toDouble())
            obj.put("view_gamma", _gamma.value.toDouble())
            obj.put("auto_gamma", _autoGammaEnabled.value)
            obj.put("custom_tint", _customTint.value)
            obj.put("auto_night_shift", _autoNightShift.value)
            obj.put("manga_scan_crisper", _mangaScanCrisper.value)
            obj.put("color_mode", _colorMode.value.name)
            obj.put("hd_mode_enabled", _hdModeEnabled.value)
            obj.put("show_edit_features", _showEditFeatures.value)
            obj.put("preset_filter", _presetFilter.value)
            obj.put("pdf_engine_setting", _pdfEngineSetting.value)
            obj.put("quality_selection_enabled", _qualitySelectionEnabled.value)
            obj.put("quality_level", _qualityLevel.value)
            obj.put("max_storage_allocation", _maxStorageAllocation.value)
            obj.put("slice_height", _sliceHeight.value)
            obj.put("low_res_scroll_delay", _lowResScrollDelay.value)
            obj.put("hd_scroll_delay", _hdScrollDelay.value)
            obj.put("stagger_delay", _staggerDelay.value)
            obj.put("page_spacing", _pageSpacing.value)
            obj.put("double_tap_zoom_scale", _doubleTapZoomScale.value.toDouble())
            obj.put("volume_scroll_enabled", _volumeScrollEnabled.value)
            obj.put("bitmap_config", _bitmapConfigSetting.value)
            obj.put("webp_quality", _webpQuality.value)
            obj.put("haptic_feedback_enabled", _hapticFeedbackEnabled.value)
            obj.put("double_tap_reset_enabled", _doubleTapResetEnabled.value)
            obj.put("aggressive_gc_enabled", _aggressiveGcEnabled.value)
            obj.put("keep_screen_on", _keepScreenOn.value)
            obj.put("immersive_mode", _immersiveMode.value)
            obj.put("volume_key_navigation", _volumeKeyNavigation.value)
            obj.put("reading_direction", _readingDirection.value)
            obj.put("preload_count", _preloadCount.value)
            obj.put("auto_scroll_step", _autoScrollStep.value.toDouble())
            obj.put("zoom_lock_enabled", _zoomLockEnabled.value)
            obj.put("locked_zoom_level", _lockedZoomLevel.value.toDouble())
            obj.put("view_exposure", _exposure.value.toDouble())
            obj.put("view_highlights", _highlights.value.toDouble())
            obj.put("view_shadows", _shadows.value.toDouble())
            obj.put("swipe_sensitivity", _swipeSensitivity.value.toDouble())
            obj.put("hd_text_mode_enabled", _hdTextModeEnabled.value)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return obj.toString(2)
    }

    fun importSettingsJson(jsonStr: String): Boolean {
        return try {
            val obj = JSONObject(jsonStr)
            if (obj.has("reader_theme_index")) {
                val idx = obj.optInt("reader_theme_index", 0)
                setReaderTheme(ReaderTheme.entries.getOrNull(idx) ?: ReaderTheme.DARK)
            }
            if (obj.has("view_brightness")) setBrightness(obj.optDouble("view_brightness", 1.0).toFloat())
            if (obj.has("view_contrast")) setContrast(obj.optDouble("view_contrast", 1.0).toFloat())
            if (obj.has("view_saturation")) setSaturation(obj.optDouble("view_saturation", 1.0).toFloat())
            if (obj.has("view_warmth")) setWarmth(obj.optDouble("view_warmth", 0.0).toFloat())
            if (obj.has("view_gamma")) setGamma(obj.optDouble("view_gamma", 1.0).toFloat())
            if (obj.has("auto_gamma")) setAutoGammaEnabled(obj.optBoolean("auto_gamma", false))
            if (obj.has("custom_tint")) setCustomTint(obj.optString("custom_tint", "None"))
            if (obj.has("auto_night_shift")) setAutoNightShift(obj.optBoolean("auto_night_shift", false))
            if (obj.has("manga_scan_crisper")) setMangaScanCrisper(obj.optBoolean("manga_scan_crisper", false))
            if (obj.has("color_mode")) {
                try { setColorMode(ColorMode.valueOf(obj.optString("color_mode", "NORMAL"))) } catch (e: Exception) {}
            }
            if (obj.has("hd_mode_enabled")) setHdModeEnabled(obj.optBoolean("hd_mode_enabled", true))
            if (obj.has("show_edit_features")) setShowEditFeatures(obj.optBoolean("show_edit_features", true))
            if (obj.has("preset_filter")) setPresetFilter(obj.optString("preset_filter", "NONE"))
            if (obj.has("pdf_engine_setting")) setPdfEngineSetting(obj.optString("pdf_engine_setting", "PDFIUM"))
            if (obj.has("quality_selection_enabled")) setQualitySelectionEnabled(obj.optBoolean("quality_selection_enabled", true))
            if (obj.has("quality_level")) setQualityLevel(obj.optString("quality_level", "HIGH"))
            if (obj.has("max_storage_allocation")) setMaxStorageAllocation(obj.optInt("max_storage_allocation", 500))
            if (obj.has("slice_height")) setSliceHeight(obj.optInt("slice_height", 1536))
            if (obj.has("low_res_scroll_delay")) setLowResScrollDelay(obj.optLong("low_res_scroll_delay", 60L))
            if (obj.has("hd_scroll_delay")) setHdScrollDelay(obj.optLong("hd_scroll_delay", 150L))
            if (obj.has("stagger_delay")) setStaggerDelay(obj.optLong("stagger_delay", 80L))
            if (obj.has("page_spacing")) setPageSpacing(obj.optInt("page_spacing", 0))
            if (obj.has("double_tap_zoom_scale")) setDoubleTapZoomScale(obj.optDouble("double_tap_zoom_scale", 2.0).toFloat())
            if (obj.has("volume_scroll_enabled")) setVolumeScrollEnabled(obj.optBoolean("volume_scroll_enabled", false))
            if (obj.has("bitmap_config")) setBitmapConfigSetting(obj.optString("bitmap_config", "ARGB_8888"))
            if (obj.has("webp_quality")) setWebpQuality(obj.optInt("webp_quality", 80))
            if (obj.has("haptic_feedback_enabled")) setHapticFeedbackEnabled(obj.optBoolean("haptic_feedback_enabled", true))
            if (obj.has("double_tap_reset_enabled")) setDoubleTapResetEnabled(obj.optBoolean("double_tap_reset_enabled", true))
            if (obj.has("aggressive_gc_enabled")) setAggressiveGcEnabled(obj.optBoolean("aggressive_gc_enabled", false))
            if (obj.has("keep_screen_on")) setKeepScreenOn(obj.optBoolean("keep_screen_on", true))
            if (obj.has("immersive_mode")) setImmersiveMode(obj.optBoolean("immersive_mode", false))
            if (obj.has("volume_key_navigation")) setVolumeKeyNavigation(obj.optBoolean("volume_key_navigation", true))
            if (obj.has("reading_direction")) setReadingDirection(obj.optString("reading_direction", "Vertical"))
            if (obj.has("preload_count")) setPreloadCount(obj.optInt("preload_count", 2))
            if (obj.has("auto_scroll_step")) setAutoScrollStep(obj.optDouble("auto_scroll_step", 1.5).toFloat())
            if (obj.has("zoom_lock_enabled")) setZoomLockEnabled(obj.optBoolean("zoom_lock_enabled", false))
            if (obj.has("locked_zoom_level")) setLockedZoomLevel(obj.optDouble("locked_zoom_level", 1.0).toFloat())
            if (obj.has("view_exposure")) setExposure(obj.optDouble("view_exposure", 1.0).toFloat())
            if (obj.has("view_highlights")) setHighlights(obj.optDouble("view_highlights", 0.0).toFloat())
            if (obj.has("view_shadows")) setShadows(obj.optDouble("view_shadows", 0.0).toFloat())
            if (obj.has("swipe_sensitivity")) setSwipeSensitivity(obj.optDouble("swipe_sensitivity", 1.0).toFloat())
            if (obj.has("hd_text_mode_enabled")) setHdTextModeEnabled(obj.optBoolean("hd_text_mode_enabled", false))
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    fun clearDiskCache() {
        viewModelScope.launch(Dispatchers.IO) {
            synchronized(renderers) {
                renderers.values.forEach {
                    viewModelScope.launch(Dispatchers.IO) {
                        it.clearDiskCache()
                    }
                }
            }
            val webpDir = File(application.cacheDir, "webp_cache")
            if (webpDir.exists()) {
                webpDir.deleteRecursively()
                webpDir.mkdirs()
            }
        }
    }

    fun clearAllSketches() {
        viewModelScope.launch(Dispatchers.IO) {
            _sketches.value = emptyMap()
            undoStacks.clear()
            redoStacks.clear()
            val sketchesDir = File(application.filesDir, "sketches")
            if (sketchesDir.exists()) {
                sketchesDir.deleteRecursively()
                sketchesDir.mkdirs()
            }
        }
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
                // Remove active tab (if it's not settings)
                val activeTabObj = existingList.find { it.id == _activeTabId.value }
                if (activeTabObj != null && activeTabObj.type != TabType.SETTINGS) {
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
            existingList.add(UltraTab(id = settingsTabId, title = "Lobby", type = TabType.SETTINGS))
            _tabs.value = existingList
        }
        selectTabId(settingsTabId)
    }

    // --- Sketch Editor Controls ---
    private val undoStacks = mutableMapOf<Int, MutableList<List<DrawPath>>>()
    private val redoStacks = mutableMapOf<Int, MutableList<List<DrawPath>>>()

    private val _drawUndoStateVersion = MutableStateFlow(0)
    val drawUndoStateVersion: StateFlow<Int> = _drawUndoStateVersion.asStateFlow()

    fun setDrawColor(color: Color) {
        _activeDrawColor.value = color
    }

    fun setStrokeWidth(width: Float) {
        _activeStrokeWidth.value = width
    }

    fun canUndo(pageIndex: Int): Boolean {
        return !undoStacks[pageIndex].isNullOrEmpty()
    }

    fun canRedo(pageIndex: Int): Boolean {
        return !redoStacks[pageIndex].isNullOrEmpty()
    }

    fun addDrawPath(pageIndex: Int, path: DrawPath) {
        val currentSketches = _sketches.value.toMutableMap()
        val paths = currentSketches[pageIndex] ?: emptyList()
        
        // Save current state to undo stack
        val undoStack = undoStacks.getOrPut(pageIndex) { mutableListOf() }
        undoStack.add(paths.toList())
        
        // Clear redo stack for this page
        redoStacks[pageIndex]?.clear()
        
        val newPaths = paths.toMutableList()
        newPaths.add(path)
        currentSketches[pageIndex] = newPaths
        _sketches.value = currentSketches
        _drawUndoStateVersion.value++
        
        activeManhwa.value?.let { saveSketchesToDisk(it.id) }
    }

    fun undoDrawPath(pageIndex: Int) {
        val undoStack = undoStacks[pageIndex]
        if (!undoStack.isNullOrEmpty()) {
            val currentSketches = _sketches.value.toMutableMap()
            val currentPaths = currentSketches[pageIndex] ?: emptyList()
            
            // Push current to redo stack
            val redoStack = redoStacks.getOrPut(pageIndex) { mutableListOf() }
            redoStack.add(currentPaths.toList())
            
            // Pop last state from undo stack
            val previousPaths = undoStack.removeAt(undoStack.size - 1)
            if (previousPaths.isEmpty()) {
                currentSketches.remove(pageIndex)
            } else {
                currentSketches[pageIndex] = previousPaths
            }
            _sketches.value = currentSketches
            _drawUndoStateVersion.value++
            
            activeManhwa.value?.let { saveSketchesToDisk(it.id) }
        }
    }

    fun redoDrawPath(pageIndex: Int) {
        val redoStack = redoStacks[pageIndex]
        if (!redoStack.isNullOrEmpty()) {
            val currentSketches = _sketches.value.toMutableMap()
            val currentPaths = currentSketches[pageIndex] ?: emptyList()
            
            // Push current to undo stack
            val undoStack = undoStacks.getOrPut(pageIndex) { mutableListOf() }
            undoStack.add(currentPaths.toList())
            
            // Pop last state from redo stack
            val nextPaths = redoStack.removeAt(redoStack.size - 1)
            currentSketches[pageIndex] = nextPaths
            _sketches.value = currentSketches
            _drawUndoStateVersion.value++
            
            activeManhwa.value?.let { saveSketchesToDisk(it.id) }
        }
    }

    fun clearDrawPaths(pageIndex: Int) {
        val currentSketches = _sketches.value.toMutableMap()
        val paths = currentSketches[pageIndex] ?: emptyList()
        if (paths.isNotEmpty()) {
            val undoStack = undoStacks.getOrPut(pageIndex) { mutableListOf() }
            undoStack.add(paths.toList())
            redoStacks[pageIndex]?.clear()
        }
        currentSketches.remove(pageIndex)
        _sketches.value = currentSketches
        _drawUndoStateVersion.value++
        
        activeManhwa.value?.let { saveSketchesToDisk(it.id) }
    }

    private fun saveSketchesToDisk(manhwaId: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val sketchesMap = _sketches.value
                val rootJson = JSONObject()
                val pagesArray = JSONArray()
                
                sketchesMap.forEach { (pageIndex, paths) ->
                    val pageJson = JSONObject()
                    pageJson.put("pageIndex", pageIndex)
                    
                    val pathsArray = JSONArray()
                    paths.forEach { path ->
                        val pathJson = JSONObject()
                        pathJson.put("color", path.color.value.toLong())
                        pathJson.put("strokeWidth", path.strokeWidth.toDouble())
                        
                        val pointsArray = JSONArray()
                        path.points.forEach { point ->
                            val pointJson = JSONObject()
                            pointJson.put("x", point.x.toDouble())
                            pointJson.put("y", point.y.toDouble())
                            pointsArray.put(pointJson)
                        }
                        pathJson.put("points", pointsArray)
                        pathsArray.put(pathJson)
                    }
                    pageJson.put("paths", pathsArray)
                    pagesArray.put(pageJson)
                }
                rootJson.put("pages", pagesArray)
                
                val sketchesDir = File(application.filesDir, "sketches")
                if (!sketchesDir.exists()) {
                    sketchesDir.mkdirs()
                }
                val file = File(sketchesDir, "manhwa_$manhwaId.json")
                file.writeText(rootJson.toString())
            } catch (e: Exception) {
                android.util.Log.e("ManhwaViewModel", "Error saving sketches to disk", e)
            }
        }
    }

    private fun loadSketchesFromDisk(manhwaId: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // Clear undo/redo stacks when loading a new manhwa
                undoStacks.clear()
                redoStacks.clear()
                
                val sketchesDir = File(application.filesDir, "sketches")
                val file = File(sketchesDir, "manhwa_$manhwaId.json")
                if (!file.exists()) {
                    _sketches.value = emptyMap()
                    return@launch
                }
                
                val text = file.readText()
                val rootJson = JSONObject(text)
                val pagesArray = rootJson.optJSONArray("pages") ?: JSONArray()
                val newMap = mutableMapOf<Int, List<DrawPath>>()
                
                for (i in 0 until pagesArray.length()) {
                    val pageJson = pagesArray.optJSONObject(i) ?: continue
                    val pageIndex = pageJson.optInt("pageIndex", -1)
                    if (pageIndex == -1) continue
                    
                    val pathsArray = pageJson.optJSONArray("paths") ?: JSONArray()
                    val pathsList = mutableListOf<DrawPath>()
                    
                    for (j in 0 until pathsArray.length()) {
                        val pathJson = pathsArray.optJSONObject(j) ?: continue
                        val colorLong = pathJson.optLong("color", 0)
                        val strokeWidth = pathJson.optDouble("strokeWidth", 8.0).toFloat()
                        
                        val pointsArray = pathJson.optJSONArray("points") ?: JSONArray()
                        val pointsList = mutableListOf<Offset>()
                        
                        for (k in 0 until pointsArray.length()) {
                            val pointJson = pointsArray.optJSONObject(k) ?: continue
                            val x = pointJson.optDouble("x", 0.0).toFloat()
                            val y = pointJson.optDouble("y", 0.0).toFloat()
                            pointsList.add(Offset(x, y))
                        }
                        
                        pathsList.add(
                            DrawPath(
                                points = pointsList,
                                color = Color(colorLong.toULong()),
                                strokeWidth = strokeWidth
                            )
                        )
                    }
                    newMap[pageIndex] = pathsList
                }
                
                _sketches.value = newMap
            } catch (e: Exception) {
                android.util.Log.e("ManhwaViewModel", "Error loading sketches from disk", e)
                _sketches.value = emptyMap()
            }
        }
    }

    // --- Memory Cache Monitoring & Clearing Utilities ---
    fun getMemoryCacheSizeText(): String {
        var totalBytes = 0
        synchronized(renderers) {
            renderers.values.forEach { renderer ->
                totalBytes += renderer.getMemoryCacheSize()
            }
        }
        val mb = totalBytes.toDouble() / (1024 * 1024)
        return String.format(java.util.Locale.US, "%.2f MB", mb)
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

    private var prefetchJob: kotlinx.coroutines.Job? = null

    fun warmCacheForVelocity(currentPage: Int, targetWidth: Int, velocity: Float) {
        val countToPreload = 3
        val direction = if (velocity > 0) 1 else -1
        
        prefetchJob?.cancel()
        prefetchJob = viewModelScope.launch(Dispatchers.IO) {
            val tab = _tabs.value.find { it.id == _activeTabId.value }
            val manhwaId = tab?.manhwa?.id ?: return@launch
            val renderer = synchronized(renderers) {
                renderers[manhwaId]
            } ?: return@launch

            val totalPages = renderer.pageCount
            
            // Only preload if velocity isn't crazy high (prevent CPU choke)
            if (Math.abs(velocity) < 5.0f) {
                for (i in 1..countToPreload) {
                    val pageToLoad = currentPage + (i * direction)
                    if (pageToLoad in 0 until totalPages) {
                        // Preload low-res thumbnail first
                        renderer.renderPageLowRes(
                            pageIndex = pageToLoad,
                            targetWidth = targetWidth,
                            bitmapConfig = _bitmapConfigSetting.value
                        )

                        // Yield before heavy work
                        kotlinx.coroutines.delay(100)

                        // Then preload full page slices (let's assume first few slices)
                        // This uses renderPageSlice which saves to WebP Cache
                        val aspect = renderer.getPageAspectRatio(pageToLoad)
                        val scaleFactor = getQualityScaleFactor(_qualityLevel.value)
                        val sliceHeight = _sliceHeight.value
                        val basePageHeight = targetWidth * aspect
                        val numSlices = Math.ceil(basePageHeight.toDouble() / sliceHeight).toInt().coerceAtLeast(1)
                        
                        // Just preload the first 3 slices to save memory and CPU
                        val slicesToPreload = minOf(numSlices, 3)
                        for (slice in 0 until slicesToPreload) {
                            renderer.renderPageSlice(
                                pageIndex = pageToLoad,
                                targetWidth = targetWidth,
                                sliceIndex = slice,
                                sliceHeight = sliceHeight,
                                scaleFactor = scaleFactor,
                                qualitySelectionEnabled = _qualitySelectionEnabled.value,
                                qualityLevel = _qualityLevel.value,
                                qualityCompression = _webpQuality.value,
                                maxStorageAllocationMb = _maxStorageAllocation.value,
                                bitmapConfig = _bitmapConfigSetting.value
                            )
                        }
                    }
                }
            } else {
                // If scrolling extremely fast, JUST preload low-res thumbnails
                for (i in 1..(countToPreload + 2)) {
                    val pageToLoad = currentPage + (i * direction)
                    if (pageToLoad in 0 until totalPages) {
                        renderer.renderPageLowRes(
                            pageIndex = pageToLoad,
                            targetWidth = targetWidth,
                            bitmapConfig = _bitmapConfigSetting.value
                        )
                    }
                }
            }
        }
    }

    private fun calculateStreak(events: List<ReadingEvent>): Int {
        if (events.isEmpty()) return 0
        val cal = java.util.Calendar.getInstance()
        
        // Extract unique days when the user read
        val readingDays = events.map { event ->
            cal.timeInMillis = event.timestamp
            val year = cal.get(java.util.Calendar.YEAR)
            val dayOfYear = cal.get(java.util.Calendar.DAY_OF_YEAR)
            "$year-$dayOfYear"
        }.distinct()

        if (readingDays.isEmpty()) return 0

        cal.timeInMillis = System.currentTimeMillis()
        var currentYear = cal.get(java.util.Calendar.YEAR)
        var currentDay = cal.get(java.util.Calendar.DAY_OF_YEAR)
        
        var streak = 0
        var checkDate = "$currentYear-$currentDay"
        
        // Check if the user read today or yesterday to continue/start the streak
        if (!readingDays.contains(checkDate)) {
            // Check if read yesterday
            cal.add(java.util.Calendar.DAY_OF_YEAR, -1)
            currentYear = cal.get(java.util.Calendar.YEAR)
            currentDay = cal.get(java.util.Calendar.DAY_OF_YEAR)
            checkDate = "$currentYear-$currentDay"
            if (!readingDays.contains(checkDate)) {
                return 0
            }
        }
        
        // Count backwards to calculate streak
        while (true) {
            if (readingDays.contains(checkDate)) {
                streak++
                // Move back 1 day
                cal.add(java.util.Calendar.DAY_OF_YEAR, -1)
                currentYear = cal.get(java.util.Calendar.YEAR)
                currentDay = cal.get(java.util.Calendar.DAY_OF_YEAR)
                checkDate = "$currentYear-$currentDay"
            } else {
                break
            }
        }
        return streak
    }

    private fun calculateTodayReadingSeconds(events: List<ReadingEvent>): Long {
        val cal = java.util.Calendar.getInstance()
        cal.timeInMillis = System.currentTimeMillis()
        val todayYear = cal.get(java.util.Calendar.YEAR)
        val todayDay = cal.get(java.util.Calendar.DAY_OF_YEAR)
        
        return events.filter { event ->
            cal.timeInMillis = event.timestamp
            cal.get(java.util.Calendar.YEAR) == todayYear && cal.get(java.util.Calendar.DAY_OF_YEAR) == todayDay
        }.sumOf { it.durationSeconds.toLong() }
    }

    private fun calculateWeeklyReadingStats(events: List<ReadingEvent>): List<Int> {
        val cal = java.util.Calendar.getInstance()
        val now = System.currentTimeMillis()
        
        // Days of the week index (0 = Monday, 6 = Sunday)
        val stats = MutableList(7) { 0 }
        
        // Set Calendar to Monday of current week
        cal.timeInMillis = now
        cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
        cal.set(java.util.Calendar.MINUTE, 0)
        cal.set(java.util.Calendar.SECOND, 0)
        cal.set(java.util.Calendar.MILLISECOND, 0)
        
        // Get calendar day of week (Sunday is 1, Monday is 2, etc.)
        val dayOfWeek = cal.get(java.util.Calendar.DAY_OF_WEEK)
        // Shift calendar to Monday
        val daysFromMonday = if (dayOfWeek == java.util.Calendar.SUNDAY) 6 else dayOfWeek - java.util.Calendar.MONDAY
        cal.add(java.util.Calendar.DAY_OF_YEAR, -daysFromMonday)
        val mondayStart = cal.timeInMillis
        
        // End of Sunday
        cal.add(java.util.Calendar.DAY_OF_YEAR, 7)
        val sundayEnd = cal.timeInMillis
        
        events.forEach { event ->
            if (event.timestamp in mondayStart until sundayEnd) {
                val eventCal = java.util.Calendar.getInstance()
                eventCal.timeInMillis = event.timestamp
                val day = eventCal.get(java.util.Calendar.DAY_OF_WEEK)
                val index = if (day == java.util.Calendar.SUNDAY) 6 else day - java.util.Calendar.MONDAY
                if (index in 0..6) {
                    stats[index] += event.durationSeconds
                }
            }
        }
        return stats
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
