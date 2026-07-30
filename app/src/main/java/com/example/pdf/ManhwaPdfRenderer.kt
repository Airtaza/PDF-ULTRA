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
    
    private val webPCacheManager = WebPCacheManager(context, file.nameWithoutExtension)

    // Cache to hold rendered page bitmaps. Limit size in bytes to safe heap levels to prevent OOM/GC freezes.
    private val memoryCache: LruCache<String, Bitmap> = run {
        val maxMemory = Runtime.getRuntime().maxMemory()
        // Convert user setting in MB to bytes, capped safely at 25% of available JVM heap
        val cacheSize = (maxCacheSizeMb * 1024L * 1024L)
            .coerceAtMost(maxMemory / 4)
            .toInt()
            .coerceAtLeast(16 * 1024 * 1024)
        object : LruCache<String, Bitmap>(cacheSize) {
            override fun sizeOf(key: String, value: Bitmap): Int {
                return value.byteCount
            }
            override fun entryRemoved(evicted: Boolean, key: String, oldValue: Bitmap, newValue: Bitmap?) {
                super.entryRemoved(evicted, key, oldValue, newValue)
                if (evicted && oldValue !== newValue) {
                    webPCacheManager.releaseBitmap(oldValue)
                }
            }
        }
    }

    fun resizeCache(newMaxCacheSizeMb: Int) {
        val maxMemory = Runtime.getRuntime().maxMemory()
        val newCacheSize = (newMaxCacheSizeMb * 1024L * 1024L)
            .coerceAtMost(maxMemory / 4)
            .toInt()
            .coerceAtLeast(16 * 1024 * 1024)
        memoryCache.resize(newCacheSize)
    }

    fun clearMemoryCache() {
        memoryCache.evictAll()
        webPCacheManager.clearMemoryCache()
    }

    val pageCount: Int
        get() = synchronized(this) { pdfRenderer?.pageCount ?: 0 }

    init {
        try {
            parcelFileDescriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            pdfRenderer = PdfRenderer(parcelFileDescriptor!!)
            
            // Asynchronously pre-populate aspect ratios of all pages to make scrolling completely instant
            val renderer = pdfRenderer
            if (renderer != null) {
                val count = renderer.pageCount
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        for (i in 0 until count) {
                            if (isClosed || pdfRenderer == null) break
                            synchronized(this@ManhwaPdfRenderer) {
                                if (isClosed || pdfRenderer == null) return@synchronized
                                val page = try { renderer.openPage(i) } catch (e: Throwable) { null }
                                if (page != null) {
                                    val ratio = page.height.toFloat() / page.width.toFloat()
                                    page.close()
                                    aspectRatios[i] = ratio
                                }
                            }
                            // Yield CPU and allow render requests to acquire lock
                            kotlinx.coroutines.delay(30)
                        }
                    } catch (e: Throwable) {
                        e.printStackTrace()
                    }
                }
            }
        } catch (e: Throwable) {
            e.printStackTrace()
        }
    }

    suspend fun getPageAspectRatio(pageIndex: Int): Float = withContext(Dispatchers.IO) {
        val cached = aspectRatios[pageIndex]
        if (cached != null) return@withContext cached

        if (isClosed || pdfRenderer == null) return@withContext 1.414f
        val renderer = pdfRenderer ?: return@withContext 1.414f
        val count = try { renderer.pageCount } catch (e: Throwable) { 0 }
        if (pageIndex < 0 || pageIndex >= count) return@withContext 1.414f

        try {
            synchronized(this@ManhwaPdfRenderer) {
                if (isClosed || pdfRenderer == null) return@synchronized 1.414f
                // Double check after acquiring lock
                val cached2 = aspectRatios[pageIndex]
                if (cached2 != null) return@synchronized cached2

                val page = try { renderer.openPage(pageIndex) } catch (e: Throwable) { null } ?: return@synchronized 1.414f
                val ratio = page.height.toFloat() / page.width.toFloat()
                page.close()
                aspectRatios[pageIndex] = ratio
                ratio
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

        val scaleStr = String.format(java.util.Locale.US, "%.2f", scaleFactor)
        val cacheKey = if (isLowResPlaceholder) "${pageIndex}_low_$landscapeSplitMode" else "${pageIndex}_${sliceIndex}_${scaleStr}_$landscapeSplitMode"
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
                    val halfPageAspectRatio = if (isSplit) 2f * pageAspectRatio else pageAspectRatio

                    // Use targetWidth as base for all calculations to maintain consistency between slices
                    val totalWidth = (targetWidth * scaleFactor).toInt().coerceAtLeast(400)
                    val totalHeight = (totalWidth * halfPageAspectRatio).toInt().coerceAtLeast(400)

                    // Calculate slices based on the base page height (at scale 1.0) to keep numSlices stable
                    val basePageHeight = targetWidth * halfPageAspectRatio
                    val numSlices = Math.ceil(basePageHeight.toDouble() / sliceHeight).toInt().coerceAtLeast(1)

                    val pixelSliceY = if (isLowResPlaceholder) 0 else (sliceIndex * sliceHeight * scaleFactor).toInt()
                    val pixelRenderHeight = if (isLowResPlaceholder) {
                        totalHeight 
                    } else if (sliceIndex == numSlices - 1) {
                        totalHeight - pixelSliceY
                    } else {
                        ((sliceIndex + 1) * sliceHeight * scaleFactor).toInt() - pixelSliceY
                    }

                    if (pixelRenderHeight <= 0) return@synchronized null
                    if (!this@withContext.isActive) return@synchronized null

                    // PdfRenderer strictly requires ARGB_8888 format
                    val config = Bitmap.Config.ARGB_8888
                    var bmp = try {
                        webPCacheManager.getReusableBitmap(totalWidth, pixelRenderHeight, config)
                            ?: Bitmap.createBitmap(totalWidth, pixelRenderHeight, config)
                    } catch (e: OutOfMemoryError) {
                        onOOM()
                        return@synchronized null
                    }
                    
                    // Fill with white background, as PdfRenderer draws on top and many PDFs have transparent backgrounds
                    val canvas = android.graphics.Canvas(bmp)
                    canvas.drawColor(android.graphics.Color.WHITE)

                    // Calculate scale factors precisely
                    val pdfRenderWidth = if (isSplit) widthPt / 2f else widthPt.toFloat()
                    val scaleX = totalWidth.toFloat() / pdfRenderWidth
                    val scaleY = totalHeight.toFloat() / heightPt.toFloat()

                    val matrix = Matrix()
                    matrix.postScale(scaleX, scaleY)
                    
                    val translateX = if (landscapeSplitMode == "RIGHT_HALF") -totalWidth.toFloat() else 0f
                    matrix.postTranslate(translateX, -pixelSliceY.toFloat())

                    if (!this@withContext.isActive) {
                        bmp.recycle()
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
        val totalWidth = (targetWidth * scaleFactor).toInt().coerceAtLeast(400)
        val totalHeight = (totalWidth * halfAspect).toInt().coerceAtLeast(400)
        return renderPageSlice(
            pageIndex, targetWidth, 0, totalHeight, scaleFactor,
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
