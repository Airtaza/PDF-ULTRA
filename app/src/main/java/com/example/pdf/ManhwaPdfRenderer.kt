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

class ManhwaPdfRenderer(private val context: Context, private val file: File, private val maxCacheSizeMb: Int = 100) {

    private var parcelFileDescriptor: ParcelFileDescriptor? = null
    private var pdfRenderer: PdfRenderer? = null
    private val aspectRatios = java.util.concurrent.ConcurrentHashMap<Int, Float>()

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
                            if (pdfRenderer == null) break
                            synchronized(this@ManhwaPdfRenderer) {
                                if (pdfRenderer == null) return@synchronized
                                val page = renderer.openPage(i)
                                val ratio = page.height.toFloat() / page.width.toFloat()
                                page.close()
                                aspectRatios[i] = ratio
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

        val renderer = pdfRenderer ?: return@withContext 1.414f
        if (pageIndex < 0 || pageIndex >= renderer.pageCount) return@withContext 1.414f

        try {
            synchronized(this@ManhwaPdfRenderer) {
                // Double check after acquiring lock
                val cached2 = aspectRatios[pageIndex]
                if (cached2 != null) return@synchronized cached2

                val page = renderer.openPage(pageIndex)
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
        bitmapConfig: String = "ARGB_8888"
    ): Bitmap? = withContext(Dispatchers.IO) {
        if (!this@withContext.isActive) return@withContext null

        val scaleStr = String.format(java.util.Locale.US, "%.2f", scaleFactor)
        val cacheKey = if (isLowResPlaceholder) "${pageIndex}_low" else "${pageIndex}_${sliceIndex}_$scaleStr"
        val cached = memoryCache.get(cacheKey)
        if (cached != null && !cached.isRecycled) {
            return@withContext cached
        }

        // Fallback or Normal PDF Render
        val renderer = pdfRenderer ?: return@withContext null
        if (pageIndex < 0 || pageIndex >= renderer.pageCount) return@withContext null

        var renderDurationMs = 0L
        try {
            val bitmap = synchronized(this@ManhwaPdfRenderer) {
                if (!this@withContext.isActive) return@synchronized null

                // Double check cache after lock
                val cached2 = memoryCache.get(cacheKey)
                if (cached2 != null && !cached2.isRecycled) {
                    return@synchronized cached2
                }

                val page = renderer.openPage(pageIndex)
                try {
                    val widthPt = page.width
                    val heightPt = page.height
                    val pageAspectRatio = heightPt.toFloat() / widthPt.toFloat()

                    val totalWidth = (targetWidth * scaleFactor).toInt().coerceAtLeast(400)
                    val totalHeight = (totalWidth * pageAspectRatio).toInt().coerceAtLeast(400)

                    val sliceY = if (isLowResPlaceholder) 0 else sliceIndex * sliceHeight
                    val actualSliceHeight = if (isLowResPlaceholder) totalHeight else (totalHeight - sliceY).coerceAtMost(sliceHeight)

                    if (actualSliceHeight <= 0) return@synchronized null
                    if (!this@withContext.isActive) return@synchronized null

                    // PdfRenderer requires ARGB_8888 format
                    val config = Bitmap.Config.ARGB_8888
                    val bmp = Bitmap.createBitmap(totalWidth, actualSliceHeight, config)
                    
                    // Fill with white background, as PdfRenderer draws on top and many PDFs have transparent backgrounds
                    val canvas = android.graphics.Canvas(bmp)
                    canvas.drawColor(android.graphics.Color.WHITE)

                    val scaleX = totalWidth.toFloat() / widthPt
                    val scaleY = totalHeight.toFloat() / heightPt

                    val matrix = Matrix()
                    matrix.postScale(scaleX, scaleY)
                    matrix.postTranslate(0f, -sliceY.toFloat())

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
        bitmapConfig: String = "ARGB_8888"
    ): Bitmap? {
        val aspect = getPageAspectRatio(pageIndex)
        val totalWidth = (targetWidth * scaleFactor).toInt().coerceAtLeast(400)
        val totalHeight = (totalWidth * aspect).toInt().coerceAtLeast(400)
        return renderPageSlice(
            pageIndex, targetWidth, 0, totalHeight, scaleFactor,
            qualitySelectionEnabled, qualityLevel, qualityCompression, maxStorageAllocationMb,
            bitmapConfig = bitmapConfig
        )
    }

    suspend fun renderPageLowRes(
        pageIndex: Int,
        targetWidth: Int,
        bitmapConfig: String = "ARGB_8888"
    ): Bitmap? {
        val aspect = getPageAspectRatio(pageIndex)
        val lowResScale = 0.4f
        val totalWidth = (targetWidth * lowResScale).toInt().coerceAtLeast(200)
        val totalHeight = (totalWidth * aspect).toInt().coerceAtLeast(200)
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
            bitmapConfig = bitmapConfig
        )
    }

    fun clearCache() {
        memoryCache.evictAll()
        aspectRatios.clear()
    }

    fun getMemoryCacheSize(): Int {
        return memoryCache.size()
    }

    fun close() {
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
