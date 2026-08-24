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
    DYNAMIC_AUTO("Auto Adaptive", "Smart validation of PDF bounds with bitmap sample fallbacks"),
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
    private val aspectRatios = java.util.concurrent.ConcurrentHashMap<Int, Float>()

    fun clearAspectRatiosCache() {
        aspectRatios.clear()
    }
    
    private val webPCacheManager = WebPCacheManager(context, file.nameWithoutExtension)

    // Cache to hold rendered page bitmaps (capped to max 50MB RAM)
    private val memoryCache: LruCache<String, Bitmap> = run {
        val cacheSizeBytes = (maxCacheSizeMb.coerceIn(20, 50) * 1024 * 1024)
        object : LruCache<String, Bitmap>(cacheSizeBytes) {
            override fun sizeOf(key: String, value: Bitmap): Int {
                return value.byteCount
            }
        }
    }

    fun resizeCache(newMaxCacheSizeMb: Int) {
        val cacheSizeBytes = (newMaxCacheSizeMb.coerceIn(20, 50) * 1024 * 1024)
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
        System.gc()
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
                                aspectRatios[i] = ratio
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
        method: AspectCalcMethod = AspectCalcMethod.DYNAMIC_AUTO
    ): Float = withContext(Dispatchers.IO) {
        val cached = aspectRatios[pageIndex]
        if (cached != null && cached > 0.05f && method == AspectCalcMethod.DYNAMIC_AUTO) {
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
                        if (r in 0.1f..10f) r else 1.414f
                    }
                    AspectCalcMethod.PDF_BOUNDS -> {
                        val page = try { renderer.openPage(pageIndex) } catch (e: Throwable) { null }
                        val r = if (page != null && page.width > 0) {
                            page.height.toFloat() / page.width.toFloat()
                        } else 1.414f
                        page?.close()
                        if (r in 0.1f..10f) r else 1.414f
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
                                    if (measuredRatio in 0.1f..10f) measuredRatio else 1.414f
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
                            if (ratio in 0.3f..4.0f) {
                                ratio
                            } else {
                                1.414f
                            }
                        } else 1.414f
                    }
                }

                if (calculatedRatio > 0.05f) {
                    aspectRatios[pageIndex] = calculatedRatio
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
        val halfPageAspectRatio = if (landscapeSplitMode != "NONE") 2 * aspect else aspect

        var safeScaleFactor = scaleFactor
        val basePixels = targetWidth * (targetWidth * halfPageAspectRatio)
        val maxPixels = 4500000f
        if (basePixels * safeScaleFactor * safeScaleFactor > maxPixels) {
            safeScaleFactor = kotlin.math.sqrt(maxPixels / basePixels)
        }

        val scaleStr = String.format(java.util.Locale.US, "%.2f", safeScaleFactor)
        val cacheKey = if (isLowResPlaceholder) {
            "${pageIndex}_low_${landscapeSplitMode}"
        } else {
            "${pageIndex}_s${sliceIndex}_h${sliceHeight}_sc${scaleStr}_q${qualityLevel}_${bitmapConfig}_${landscapeSplitMode}"
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
                    val pageAspectRatio = heightPt.toFloat() / widthPt.toFloat()

                    val isSplit = landscapeSplitMode != "NONE"

                    // Use targetWidth as base for all calculations to maintain consistency between slices
                    val totalWidth = (targetWidth * safeScaleFactor).toInt().coerceAtLeast(400)
                    val totalHeight = (totalWidth * halfPageAspectRatio).toInt().coerceAtLeast(400)

                    // Calculate slices based on the base page height (at scale 1.0) to keep numSlices stable
                    val basePageHeight = targetWidth * halfPageAspectRatio
                    val numSlices = Math.ceil(basePageHeight.toDouble() / sliceHeight).toInt().coerceAtLeast(1)

                    val pixelSliceY = if (isLowResPlaceholder) 0 else (sliceIndex * sliceHeight * safeScaleFactor).toInt()
                    val pixelRenderHeight = if (isLowResPlaceholder) {
                        totalHeight 
                    } else if (sliceIndex == numSlices - 1) {
                        totalHeight - pixelSliceY
                    } else {
                        ((sliceIndex + 1) * sliceHeight * safeScaleFactor).toInt() - pixelSliceY
                    }

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

                    // Calculate scale factors precisely
                    val pdfRenderWidth = if (isSplit) widthPt / 2f else widthPt.toFloat()
                    val scale = totalWidth.toFloat() / pdfRenderWidth

                    val matrix = Matrix()
                    matrix.postScale(scale, scale)
                    
                    val translateX = if (landscapeSplitMode == "RIGHT_HALF") -totalWidth.toFloat() else 0f
                    matrix.postTranslate(translateX, -pixelSliceY.toFloat())

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
        val halfAspect = if (landscapeSplitMode != "NONE") 2 * aspect else aspect

        var safeScaleFactor = scaleFactor
        val basePixels = targetWidth * (targetWidth * halfAspect)
        val maxPixels = 4500000f
        if (basePixels * safeScaleFactor * safeScaleFactor > maxPixels) {
            safeScaleFactor = kotlin.math.sqrt(maxPixels / basePixels)
        }

        val totalWidth = (targetWidth * safeScaleFactor).toInt().coerceAtLeast(400)
        val totalHeight = (totalWidth * halfAspect).toInt().coerceAtLeast(400)
        return renderPageSlice(
            pageIndex, targetWidth, 0, totalHeight, safeScaleFactor,
            qualitySelectionEnabled, qualityLevel, qualityCompression, maxStorageAllocationMb,
            bitmapConfig = bitmapConfig,
            landscapeSplitMode = landscapeSplitMode
        )
    }

    suspend fun renderPageLowRes(
        pageIndex: Int,
        targetWidth: Int,
        bitmapConfig: String = "ARGB_8888",
        landscapeSplitMode: String = "NONE"
    ): Bitmap? {
        val aspect = getPageAspectRatio(pageIndex)
        val halfAspect = if (landscapeSplitMode != "NONE") 2 * aspect else aspect
        val lowResScale = 0.4f
        val totalWidth = (targetWidth * lowResScale).toInt().coerceAtLeast(200)
        val totalHeight = (totalWidth * halfAspect).toInt().coerceAtLeast(200)
        return renderPageSlice(
            pageIndex = pageIndex,
            targetWidth = targetWidth,
            sliceIndex = 0,
            sliceHeight = totalHeight,
            scaleFactor = lowResScale,
            qualitySelectionEnabled = true,
            qualityLevel = "LOW",
            qualityCompression = 60,
            maxStorageAllocationMb = 100,
            isLowResPlaceholder = true,
            bitmapConfig = bitmapConfig,
            landscapeSplitMode = landscapeSplitMode
        )
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
