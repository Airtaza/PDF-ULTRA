package com.example.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.LruCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

enum class AspectCalcMethod(val displayName: String, val description: String) {
    DYNAMIC_AUTO("Auto Adaptive", "Smart validation of PDF bounds for tall long-strip manhwa"),
    CUSTOM_TUNING("Custom Engine Fine-Tuning", "User-customizable base ratio, scale matrix, multipliers & limits"),
    PDF_BOUNDS("PDF MediaBox Points", "Uses exact height & width from PDF page metadata"),
    FIRST_PAGE_UNIFORM("First Page Uniform", "Applies Page 1's aspect ratio to all pages"),
    SAMPLED_BITMAP("Rendered Pixel Sample", "Renders a sample bitmap to measure exact pixel bounds"),
    CONTENT_BOUNDS_TRIM("Content Artwork Trim", "Trims outer margins to calculate true artwork ratio"),
    STANDARD_A4_PORTRAIT("Fixed Portrait (1.414)", "Forces standard 1.414 portrait ratio for all pages"),
    FIXED_LANDSCAPE("Fixed Spread (0.707)", "Forces 0.707 landscape aspect ratio for double spreads")
}

class ManhwaPdfRenderer(
    private val context: Context, 
    private val file: File, 
    private val maxCacheSizeMb: Int = 100,
    private val isScrolling: () -> Boolean = { false },
    private val onOOM: () -> Unit = {}
) {

    private var parcelFileDescriptor: ParcelFileDescriptor? = null
    private var pdfRenderer: PdfRenderer? = null
    @Volatile private var isClosed = false
    private val aspectRatios = java.util.concurrent.ConcurrentHashMap<String, Float>()
    @Volatile var activeAspectCalcMethod: AspectCalcMethod = AspectCalcMethod.DYNAMIC_AUTO

    @Volatile var customBaseRatioSource: String = "PDF_BOUNDS"
    @Volatile var customFixedRatio: Float = 1.414f
    @Volatile var customAspectMultiplier: Float = 1.0f
    @Volatile var customScaleMode: String = "FIT_WIDTH"
    @Volatile var customMaxAspectLimit: Float = 15.0f

    fun updateCustomTuning(
        baseSource: String,
        fixedRatio: Float,
        multiplier: Float,
        scaleMode: String,
        maxLimit: Float
    ) {
        this.customBaseRatioSource = baseSource
        this.customFixedRatio = fixedRatio
        this.customAspectMultiplier = multiplier
        this.customScaleMode = scaleMode
        this.customMaxAspectLimit = maxLimit
        clearAspectRatiosCache()
        clearMemoryCache()
    }

    fun setAspectCalcMethod(method: AspectCalcMethod) {
        this.activeAspectCalcMethod = method
        clearAspectRatiosCache()
        clearMemoryCache()
    }

    fun clearAspectRatiosCache() {
        aspectRatios.clear()
    }
    
    private val webPCacheManager = WebPCacheManager(context, file.nameWithoutExtension)

    // Dynamic LRU Memory Cache: Scales dynamically to 15-20% of JVM heap (24MB - 64MB)
    private val memoryCache: LruCache<String, Bitmap> = run {
        val runtime = Runtime.getRuntime()
        val maxHeapMb = (runtime.maxMemory() / (1024 * 1024)).toInt()
        val dynamicMb = (maxHeapMb / 6).coerceIn(24, maxCacheSizeMb.coerceAtLeast(32))
        val cacheSizeBytes = dynamicMb * 1024 * 1024
        object : LruCache<String, Bitmap>(cacheSizeBytes) {
            override fun sizeOf(key: String, value: Bitmap): Int {
                return value.byteCount
            }
        }
    }

    fun resizeCache(newMaxCacheSizeMb: Int) {
        val cacheSizeBytes = (newMaxCacheSizeMb.coerceIn(20, 64) * 1024 * 1024)
        memoryCache.resize(cacheSizeBytes)
    }

    fun clearMemoryCache() {
        memoryCache.evictAll()
        webPCacheManager.clearMemoryCache()
    }

    suspend fun clearDiskCache() {
        memoryCache.evictAll()
        webPCacheManager.clearCache()
    }

    fun freeRamExceptCurrentPage(currentPageIndex: Int, keepAdjacent: Boolean = false) {
        val snapshot = memoryCache.snapshot()
        val pagesToKeep = if (keepAdjacent) {
            setOf(currentPageIndex - 1, currentPageIndex, currentPageIndex + 1)
        } else {
            setOf(currentPageIndex)
        }
        for ((key, _) in snapshot) {
            val pageNum = key.substringBefore("_").toIntOrNull()
            if (pageNum != null && pageNum !in pagesToKeep) {
                memoryCache.remove(key)
            }
        }
        webPCacheManager.freeRamExceptCurrentPage(currentPageIndex, keepAdjacent)
    }

    val pageCount: Int
        get() = synchronized(this) { pdfRenderer?.pageCount ?: 0 }

    init {
        try {
            parcelFileDescriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            pdfRenderer = PdfRenderer(parcelFileDescriptor!!)
            
            // Populate aspect ratios of all pages synchronously on initialization
            val renderer = pdfRenderer
            if (renderer != null) {
                val count = renderer.pageCount
                synchronized(this@ManhwaPdfRenderer) {
                    for (i in 0 until count) {
                        try {
                            val page = renderer.openPage(i)
                            if (page.width > 0 && page.height > 0) {
                                val ratio = page.height.toFloat() / page.width.toFloat()
                                aspectRatios["${i}_${AspectCalcMethod.DYNAMIC_AUTO.name}"] = ratio
                                aspectRatios["${i}_${AspectCalcMethod.PDF_BOUNDS.name}"] = ratio
                            }
                            page.close()
                        } catch (e: Throwable) {
                            e.printStackTrace()
                        }
                    }
                }
            }
        } catch (e: Throwable) {
            e.printStackTrace()
        }
    }

    suspend fun getPageAspectRatio(
        pageIndex: Int,
        method: AspectCalcMethod = activeAspectCalcMethod
    ): Float = withContext(Dispatchers.IO) {
        val cacheKey = if (method == AspectCalcMethod.CUSTOM_TUNING) {
            "${pageIndex}_CUSTOM_${customBaseRatioSource}_${customFixedRatio}_${customAspectMultiplier}_${customScaleMode}_${customMaxAspectLimit}"
        } else {
            "${pageIndex}_${method.name}"
        }
        val cached = aspectRatios[cacheKey]
        if (cached != null && cached > 0.05f) {
            return@withContext cached
        }

        if (isClosed || pdfRenderer == null) return@withContext 1.414f
        val renderer = pdfRenderer ?: return@withContext 1.414f
        val count = try { renderer.pageCount } catch (e: Throwable) { 0 }
        if (pageIndex < 0 || pageIndex >= count) return@withContext 1.414f

        try {
            synchronized(this@ManhwaPdfRenderer) {
                if (isClosed || pdfRenderer == null) return@synchronized 1.414f

                val calculatedRatio = when (method) {
                    AspectCalcMethod.STANDARD_A4_PORTRAIT -> 1.414f
                    AspectCalcMethod.FIXED_LANDSCAPE -> 0.707f
                    AspectCalcMethod.FIRST_PAGE_UNIFORM -> {
                        val firstPage = try { renderer.openPage(0) } catch (e: Throwable) { null }
                        val r = if (firstPage != null && firstPage.width > 0) {
                            firstPage.height.toFloat() / firstPage.width.toFloat()
                        } else 1.414f
                        firstPage?.close()
                        if (r in 0.1f..15f) r else 1.414f
                    }
                    AspectCalcMethod.PDF_BOUNDS -> {
                        val page = try { renderer.openPage(pageIndex) } catch (e: Throwable) { null }
                        val r = if (page != null && page.width > 0) {
                            page.height.toFloat() / page.width.toFloat()
                        } else 1.414f
                        page?.close()
                        if (r in 0.1f..15f) r else 1.414f
                    }
                    AspectCalcMethod.CUSTOM_TUNING -> {
                        val baseRatio = when (customBaseRatioSource) {
                            "FIXED_CUSTOM" -> customFixedRatio
                            "FIRST_PAGE" -> {
                                val firstPage = try { renderer.openPage(0) } catch (e: Throwable) { null }
                                val r = if (firstPage != null && firstPage.width > 0) {
                                    firstPage.height.toFloat() / firstPage.width.toFloat()
                                } else 1.414f
                                firstPage?.close()
                                r
                            }
                            "SAMPLED_BITMAP" -> {
                                val page = try { renderer.openPage(pageIndex) } catch (e: Throwable) { null }
                                if (page != null) {
                                    val pw = page.width.coerceAtLeast(1)
                                    val ph = page.height.coerceAtLeast(1)
                                    val sampleW = 120
                                    val sampleH = ((ph.toFloat() / pw.toFloat()) * sampleW).toInt().coerceIn(20, 500)
                                    val bitmap = try { Bitmap.createBitmap(sampleW, sampleH, Bitmap.Config.ARGB_8888) } catch (e: Throwable) { null }
                                    if (bitmap != null) {
                                        try {
                                            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                            val measuredRatio = bitmap.height.toFloat() / bitmap.width.toFloat()
                                            bitmap.recycle()
                                            page.close()
                                            measuredRatio
                                        } catch (e: Throwable) {
                                            bitmap.recycle()
                                            page.close()
                                            1.414f
                                        }
                                    } else {
                                        page.close()
                                        1.414f
                                    }
                                } else 1.414f
                            }
                            else -> { // "PDF_BOUNDS"
                                val page = try { renderer.openPage(pageIndex) } catch (e: Throwable) { null }
                                val r = if (page != null && page.width > 0) {
                                    page.height.toFloat() / page.width.toFloat()
                                } else 1.414f
                                page?.close()
                                r
                            }
                        }
                        (baseRatio * customAspectMultiplier).coerceIn(0.1f, customMaxAspectLimit)
                    }
                    AspectCalcMethod.SAMPLED_BITMAP -> {
                        val page = try { renderer.openPage(pageIndex) } catch (e: Throwable) { null }
                        if (page != null) {
                            val pw = page.width.coerceAtLeast(1)
                            val ph = page.height.coerceAtLeast(1)
                            val sampleW = 120
                            val sampleH = ((ph.toFloat() / pw.toFloat()) * sampleW).toInt().coerceIn(20, 500)
                            val bitmap = try { Bitmap.createBitmap(sampleW, sampleH, Bitmap.Config.ARGB_8888) } catch (e: Throwable) { null }
                            if (bitmap != null) {
                                try {
                                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                    val measuredRatio = bitmap.height.toFloat() / bitmap.width.toFloat()
                                    bitmap.recycle()
                                    page.close()
                                    if (measuredRatio in 0.1f..15f) measuredRatio else 1.414f
                                } catch (e: Throwable) {
                                    bitmap.recycle()
                                    page.close()
                                    1.414f
                                }
                            } else {
                                page.close()
                                1.414f
                            }
                        } else 1.414f
                    }
                    AspectCalcMethod.CONTENT_BOUNDS_TRIM -> {
                        val page = try { renderer.openPage(pageIndex) } catch (e: Throwable) { null }
                        if (page != null) {
                            val pw = page.width.coerceAtLeast(1)
                            val ph = page.height.coerceAtLeast(1)
                            val sampleW = 100
                            val sampleH = ((ph.toFloat() / pw.toFloat()) * sampleW).toInt().coerceIn(20, 500)
                            val bitmap = try { Bitmap.createBitmap(sampleW, sampleH, Bitmap.Config.ARGB_8888) } catch (e: Throwable) { null }
                            if (bitmap != null) {
                                try {
                                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                    var minX = sampleW; var maxX = 0; var minY = sampleH; var maxY = 0
                                    var foundContent = false
                                    for (y in 0 until sampleH step 2) {
                                        for (x in 0 until sampleW step 2) {
                                            val pixel = bitmap.getPixel(x, y)
                                            val red = (pixel shr 16) and 0xFF
                                            val green = (pixel shr 8) and 0xFF
                                            val blue = pixel and 0xFF
                                            if (red < 240 || green < 240 || blue < 240) {
                                                if (x < minX) minX = x
                                                if (x > maxX) maxX = x
                                                if (y < minY) minY = y
                                                if (y > maxY) maxY = y
                                                foundContent = true
                                            }
                                        }
                                    }
                                    bitmap.recycle()
                                    page.close()
                                    if (foundContent && (maxX - minX) > 10 && (maxY - minY) > 10) {
                                        (maxY - minY).toFloat() / (maxX - minX).toFloat()
                                    } else {
                                        ph.toFloat() / pw.toFloat()
                                    }
                                } catch (e: Throwable) {
                                    bitmap.recycle()
                                    page.close()
                                    1.414f
                                }
                            } else {
                                page.close()
                                1.414f
                            }
                        } else 1.414f
                    }
                    AspectCalcMethod.DYNAMIC_AUTO -> {
                        val page = try { renderer.openPage(pageIndex) } catch (e: Throwable) { null }
                        if (page != null) {
                            val ratio = if (page.width > 0) page.height.toFloat() / page.width.toFloat() else 1.414f
                            page.close()
                            if (ratio in 0.05f..100.0f) {
                                ratio
                            } else {
                                1.414f
                            }
                        } else 1.414f
                    }
                }

                if (calculatedRatio > 0.01f) {
                    aspectRatios[cacheKey] = calculatedRatio
                }
                calculatedRatio
            }
        } catch (e: Throwable) {
            e.printStackTrace()
            1.414f
        }
    }

    suspend fun renderPageSlice(
        pageIndex: Int,
        targetWidth: Int,
        sliceIndex: Int,
        sliceHeight: Int,
        scaleFactor: Float = 1.5f,
        qualitySelectionEnabled: Boolean = true,
        qualityLevel: String = "HIGH",
        qualityCompression: Int = 90,
        maxStorageAllocationMb: Int = 500,
        isLowResPlaceholder: Boolean = false,
        bitmapConfig: String = "ARGB_8888",
        landscapeSplitMode: String = "NONE" // "NONE", "LEFT_HALF", "RIGHT_HALF"
    ): Bitmap? = withContext(Dispatchers.IO) {
        if (!this@withContext.isActive) return@withContext null

        val aspect = getPageAspectRatio(pageIndex)
        val halfPageAspectRatio = aspect

        var safeScaleFactor = scaleFactor
        val basePixels = targetWidth * (targetWidth * halfPageAspectRatio)
        val maxPixels = 4500000f
        if (basePixels * safeScaleFactor * safeScaleFactor > maxPixels) {
            safeScaleFactor = kotlin.math.sqrt(maxPixels / basePixels)
        }

        val scaleStr = String.format(java.util.Locale.US, "%.2f", safeScaleFactor)
        val cacheKey = if (isLowResPlaceholder) {
            "${pageIndex}_low"
        } else {
            "${pageIndex}_s${sliceIndex}_h${sliceHeight}_sc${scaleStr}_q${qualityLevel}_${bitmapConfig}"
        }
        val cached = memoryCache.get(cacheKey)
        if (cached != null && !cached.isRecycled) {
            return@withContext cached
        }

        // Try getting from disk cache (WebP)
        val webpCached = webPCacheManager.getFromCache(cacheKey, bitmapConfig)
        if (webpCached != null) {
            memoryCache.put(cacheKey, webpCached)
            return@withContext webpCached
        }

        // Fallback or Normal PDF Render
        if (isClosed || pdfRenderer == null) return@withContext null
        val renderer = pdfRenderer ?: return@withContext null
        val count = try { renderer.pageCount } catch (e: Throwable) { 0 }
        if (pageIndex < 0 || pageIndex >= count) return@withContext null

        var renderDurationMs = 0L
        try {
            val bitmap = synchronized(this@ManhwaPdfRenderer) {
                if (isClosed || pdfRenderer == null) return@synchronized null
                if (!this@withContext.isActive) return@synchronized null

                // Double check cache after lock
                val cached2 = memoryCache.get(cacheKey)
                if (cached2 != null && !cached2.isRecycled) {
                    return@synchronized cached2
                }

                val page = try {
                    renderer.openPage(pageIndex)
                } catch (e: Throwable) {
                    e.printStackTrace()
                    return@synchronized null
                }
                try {
                    val widthPt = page.width
                    val heightPt = page.height
                    val pdfRenderWidth = widthPt.toFloat()
                    val pdfRenderHeight = heightPt.toFloat()

                    // Use targetWidth as base for all calculations to maintain consistency
                    val totalWidth = (targetWidth * safeScaleFactor).toInt().coerceAtLeast(400)
                    
                    val matrix = Matrix()
                    val totalHeight: Int
                    if (activeAspectCalcMethod == AspectCalcMethod.CUSTOM_TUNING && customScaleMode == "FIT_PAGE_STRETCH") {
                        totalHeight = (totalWidth * halfPageAspectRatio).toInt().coerceAtLeast(400)
                        val scaleX = totalWidth.toFloat() / pdfRenderWidth
                        val scaleY = totalHeight.toFloat() / pdfRenderHeight
                        matrix.postScale(scaleX, scaleY)
                    } else if (activeAspectCalcMethod == AspectCalcMethod.CUSTOM_TUNING && customScaleMode == "CONTAIN_BOUNDS") {
                        totalHeight = (totalWidth * halfPageAspectRatio).toInt().coerceAtLeast(400)
                        val scale = minOf(totalWidth.toFloat() / pdfRenderWidth, totalHeight.toFloat() / pdfRenderHeight)
                        matrix.postScale(scale, scale)
                    } else {
                        // Standard exact uniform aspect scale
                        val scale = totalWidth.toFloat() / pdfRenderWidth
                        totalHeight = (pdfRenderHeight * scale).toInt().coerceAtLeast(10)
                        matrix.postScale(scale, scale)
                    }

                    // Calculate slices based on the base page height (at scale 1.0) to keep numSlices stable
                    val basePageHeight = (targetWidth * halfPageAspectRatio).coerceAtLeast(10f)
                    val numSlices = Math.ceil(basePageHeight.toDouble() / sliceHeight).toInt().coerceAtLeast(1)

                    val pixelSliceY = if (isLowResPlaceholder) 0 else (sliceIndex.toDouble() * totalHeight / numSlices).toInt()
                    val nextPixelSliceY = if (isLowResPlaceholder || sliceIndex == numSlices - 1) totalHeight else ((sliceIndex + 1).toDouble() * totalHeight / numSlices).toInt()
                    val pixelRenderHeight = (nextPixelSliceY - pixelSliceY).coerceAtLeast(1)

                    if (pixelRenderHeight <= 0) return@synchronized null
                    if (!this@withContext.isActive) return@synchronized null

                    // Proactive RAM check before bitmap creation
                    val runtime = Runtime.getRuntime()
                    val maxMem = runtime.maxMemory()
                    val usedMem = runtime.totalMemory() - runtime.freeMemory()
                    if (usedMem > maxMem * 0.65) {
                        freeRamExceptCurrentPage(pageIndex, keepAdjacent = false)
                    }

                    // PdfRenderer strictly requires ARGB_8888 format
                    val config = Bitmap.Config.ARGB_8888
                    val bmp = try {
                        Bitmap.createBitmap(totalWidth, pixelRenderHeight, config)
                    } catch (e: OutOfMemoryError) {
                        onOOM()
                        freeRamExceptCurrentPage(pageIndex, keepAdjacent = false)
                        System.gc()
                        try {
                            val lowerWidth = (totalWidth * 0.75f).toInt().coerceAtLeast(200)
                            val lowerHeight = (pixelRenderHeight * 0.75f).toInt().coerceAtLeast(200)
                            Bitmap.createBitmap(lowerWidth, lowerHeight, config)
                        } catch (e2: OutOfMemoryError) {
                            return@synchronized null
                        }
                    }
                    
                    // Fill with white background, as PdfRenderer draws on top and many PDFs have transparent backgrounds
                    val canvas = android.graphics.Canvas(bmp)
                    canvas.drawColor(android.graphics.Color.WHITE)

                    matrix.postTranslate(0f, -pixelSliceY.toFloat())

                    if (!this@withContext.isActive) {
                        return@synchronized null
                    }

                    val renderStartTime = System.nanoTime()
                    page.render(bmp, null, matrix, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    renderDurationMs = (System.nanoTime() - renderStartTime) / 1_000_000

                    memoryCache.put(cacheKey, bmp)
                    bmp
                } finally {
                    page.close()
                }
            }

            if (renderDurationMs > 6) {
                kotlinx.coroutines.yield() // Yield background thread control to prevent scroll micro-stutter
            }

            // Save to WebP in background
            if (bitmap != null) {
                // Determine quality based on settings
                val quality = if (isLowResPlaceholder) 60 else qualityCompression
                // Launch in a new coroutine so we don't block the return of the bitmap
                CoroutineScope(Dispatchers.IO).launch {
                    // If scrolling, wait a bit or skip to prioritize UI smoothness and current page load
                    if (isScrolling()) {
                        kotlinx.coroutines.delay(1000) 
                        if (isScrolling()) return@launch // Skip if still scrolling to save IO/CPU
                    }
                    webPCacheManager.saveToCache(cacheKey, bitmap, quality)
                }
            }

            bitmap
        } catch (e: Throwable) {
            e.printStackTrace()
            null
        }
    }

    suspend fun renderPage(
        pageIndex: Int,
        targetWidth: Int,
        scaleFactor: Float = 1.5f,
        qualitySelectionEnabled: Boolean = true,
        qualityLevel: String = "HIGH",
        qualityCompression: Int = 90,
        maxStorageAllocationMb: Int = 500,
        bitmapConfig: String = "ARGB_8888",
        landscapeSplitMode: String = "NONE"
    ): Bitmap? {
        val aspect = getPageAspectRatio(pageIndex)

        var safeScaleFactor = scaleFactor
        val basePixels = targetWidth * (targetWidth * aspect)
        val maxPixels = 4500000f
        if (basePixels * safeScaleFactor * safeScaleFactor > maxPixels) {
            safeScaleFactor = kotlin.math.sqrt(maxPixels / basePixels)
        }

        val totalWidth = (targetWidth * safeScaleFactor).toInt().coerceAtLeast(400)
        val totalHeight = (totalWidth * aspect).toInt().coerceAtLeast(400)
        return renderPageSlice(
            pageIndex, targetWidth, 0, totalHeight, safeScaleFactor,
            qualitySelectionEnabled, qualityLevel, qualityCompression, maxStorageAllocationMb,
            bitmapConfig = bitmapConfig,
            landscapeSplitMode = landscapeSplitMode
        )
    }

    fun getCachedPageAspectRatio(pageIndex: Int, method: AspectCalcMethod = activeAspectCalcMethod): Float? {
        val cacheKey = if (method == AspectCalcMethod.CUSTOM_TUNING) {
            "${pageIndex}_CUSTOM_${customBaseRatioSource}_${customFixedRatio}_${customAspectMultiplier}_${customScaleMode}_${customMaxAspectLimit}"
        } else {
            "${pageIndex}_${method.name}"
        }
        return aspectRatios[cacheKey] ?: aspectRatios["${pageIndex}_DYNAMIC_AUTO"] ?: aspectRatios["${pageIndex}_PDF_BOUNDS"]
    }

    fun getLowResThumbnailFromMemory(pageIndex: Int): Bitmap? {
        return webPCacheManager.getThumbnailFromMemory(pageIndex)
    }

    suspend fun getOrGenerateThumbnail(pageIndex: Int): Bitmap? = withContext(Dispatchers.IO) {
        if (isClosed || pdfRenderer == null) return@withContext null
        // 1. Check memory & disk WebP thumbnail cache first
        val cached = webPCacheManager.getThumbnail(pageIndex)
        if (cached != null && !cached.isRecycled) {
            return@withContext cached
        }

        // 2. Render extremely low quality, low bitrate WebP thumbnail (160px width, quality 20, ~2-4KB)
        renderLowResThumbnail(pageIndex)
    }

    suspend fun renderLowResThumbnail(pageIndex: Int): Bitmap? = withContext(Dispatchers.IO) {
        if (isClosed || pdfRenderer == null) return@withContext null
        val renderer = pdfRenderer ?: return@withContext null

        try {
            val (bmp, aspect) = synchronized(this@ManhwaPdfRenderer) {
                if (isClosed || pdfRenderer == null) return@synchronized null
                val count = renderer.pageCount
                if (pageIndex < 0 || pageIndex >= count) return@synchronized null

                val page = renderer.openPage(pageIndex)
                try {
                    val pw = page.width.coerceAtLeast(1)
                    val ph = page.height.coerceAtLeast(1)
                    val aspect = ph.toFloat() / pw.toFloat()
                    aspectRatios["${pageIndex}_DYNAMIC_AUTO"] = aspect
                    aspectRatios["${pageIndex}_PDF_BOUNDS"] = aspect

                    val thumbWidth = 160
                    val thumbHeight = ((thumbWidth * aspect).toInt()).coerceIn(40, 1200)

                    val b = Bitmap.createBitmap(thumbWidth, thumbHeight, Bitmap.Config.ARGB_8888)
                    val canvas = android.graphics.Canvas(b)
                    canvas.drawColor(android.graphics.Color.WHITE)

                    val matrix = Matrix()
                    matrix.postScale(thumbWidth.toFloat() / pw, thumbHeight.toFloat() / ph)
                    page.render(b, null, matrix, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    Pair(b, aspect)
                } finally {
                    page.close()
                }
            } ?: return@withContext null

            // Save to ultra low bitrate WebP thumbnail cache (~2-4KB file size)
            webPCacheManager.saveThumbnail(pageIndex, bmp, quality = 20)
            bmp
        } catch (e: Throwable) {
            null
        }
    }

    suspend fun preloadAllThumbnails(startPage: Int = 0, onProgress: (Int, Int) -> Unit = { _, _ -> }) = withContext(Dispatchers.IO) {
        if (isClosed || pdfRenderer == null) return@withContext
        val count = pageCount
        if (count <= 0) return@withContext

        // Create prioritization order: starting from startPage outward
        val order = mutableListOf<Int>()
        var left = startPage
        var right = startPage + 1
        while (left >= 0 || right < count) {
            if (left >= 0) order.add(left--)
            if (right < count) order.add(right++)
        }

        var processed = 0
        for (pageIndex in order) {
            if (isClosed || !isActive) break
            if (!webPCacheManager.hasThumbnail(pageIndex)) {
                renderLowResThumbnail(pageIndex)
            }
            processed++
            onProgress(processed, count)
        }
    }

    suspend fun renderPageLowRes(
        pageIndex: Int,
        targetWidth: Int,
        bitmapConfig: String = "ARGB_8888",
        landscapeSplitMode: String = "NONE"
    ): Bitmap? {
        return getOrGenerateThumbnail(pageIndex)
    }

    fun clearCache() {
        memoryCache.evictAll()
        aspectRatios.clear()
        CoroutineScope(Dispatchers.IO).launch {
            webPCacheManager.clearCache()
        }
    }

    fun getMemoryCacheSize(): Int {
        return memoryCache.size()
    }

    fun close() {
        isClosed = true
        clearCache()
        try {
            synchronized(this) {
                pdfRenderer?.close()
                parcelFileDescriptor?.close()
            }
        } catch (e: Throwable) {
            e.printStackTrace()
        } finally {
            pdfRenderer = null
            parcelFileDescriptor = null
        }
    }
}
