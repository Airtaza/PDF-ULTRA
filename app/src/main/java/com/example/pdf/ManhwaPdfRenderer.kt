package com.example.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.LruCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

object BitmapPool {
    private val pool = ArrayList<Bitmap>()

    fun acquire(width: Int, height: Int, config: Bitmap.Config): Bitmap {
        synchronized(pool) {
            val requiredBytes = width * height * when (config) {
                Bitmap.Config.ARGB_8888 -> 4
                Bitmap.Config.RGB_565 -> 2
                else -> 4
            }
            val iterator = pool.iterator()
            while (iterator.hasNext()) {
                val bitmap = iterator.next()
                if (!bitmap.isRecycled && bitmap.isMutable) {
                    if (bitmap.allocationByteCount >= requiredBytes) {
                        iterator.remove()
                        return try {
                            bitmap.reconfigure(width, height, config)
                            bitmap.eraseColor(android.graphics.Color.WHITE)
                            bitmap
                        } catch (e: Throwable) {
                            Bitmap.createBitmap(width, height, config)
                        }
                    }
                } else {
                    iterator.remove()
                }
            }
        }
        return Bitmap.createBitmap(width, height, config)
    }

    fun release(bitmap: Bitmap?) {
        if (bitmap == null || !bitmap.isMutable || bitmap.isRecycled) return
        synchronized(pool) {
            if (pool.size < 16) {
                pool.add(bitmap)
            } else {
                bitmap.recycle()
            }
        }
    }

    fun clear() {
        synchronized(pool) {
            for (bitmap in pool) {
                bitmap.recycle()
            }
            pool.clear()
        }
    }
}

class ManhwaPdfRenderer(private val context: Context, private val file: File) {

    private var parcelFileDescriptor: ParcelFileDescriptor? = null
    private var pdfRenderer: PdfRenderer? = null
    private val aspectRatios = java.util.concurrent.ConcurrentHashMap<Int, Float>()
    private val activeCacheTasks = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    // Single-threaded background worker for WebP compression to prevent CPU starvation and scrolling lag
    private val cacheExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "PdfWebpCacheWorker").apply {
            priority = Thread.MIN_PRIORITY // Run at lowest priority so rendering is never blocked
        }
    }
    private val cacheDispatcher = cacheExecutor.asCoroutineDispatcher()

    // Cache to hold rendered page bitmaps (increased to 60 slices to keep more pages in memory and prevent scroll reload lag)
    private val memoryCache: LruCache<String, Bitmap> = object : LruCache<String, Bitmap>(60) {
        override fun entryRemoved(evicted: Boolean, key: String?, oldValue: Bitmap?, newValue: Bitmap?) {
            if (evicted) {
                BitmapPool.release(oldValue)
            }
        }
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
        maxStorageAllocationMb: Int = 500
    ): Bitmap? = withContext(Dispatchers.IO) {
        val cacheKey = "${pageIndex}_$sliceIndex"
        val cached = memoryCache.get(cacheKey)
        if (cached != null && !cached.isRecycled) {
            return@withContext cached
        }

        val webpDir = File(context.cacheDir, "webp_cache/${file.nameWithoutExtension}")
        val sliceCacheKey = "${pageIndex}_s${sliceIndex}_${qualityLevel}"
        val webpFile = File(webpDir, "$sliceCacheKey.webp")

        if (qualitySelectionEnabled && webpFile.exists() && webpFile.length() > 0) {
            try {
                // Read slice directly from WebP cache - super fast!
                val sliceBitmap = BitmapFactory.decodeFile(webpFile.absolutePath)
                if (sliceBitmap != null) {
                    memoryCache.put(cacheKey, sliceBitmap)
                    return@withContext sliceBitmap
                }
            } catch (e: Throwable) {
                e.printStackTrace()
            }
        }

        // Fallback or Normal PDF Render
        val renderer = pdfRenderer ?: return@withContext null
        if (pageIndex < 0 || pageIndex >= renderer.pageCount) return@withContext null

        var renderDurationMs = 0L
        try {
            val bitmap = synchronized(this@ManhwaPdfRenderer) {
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

                    val sliceY = sliceIndex * sliceHeight
                    val actualSliceHeight = (totalHeight - sliceY).coerceAtMost(sliceHeight)

                    if (actualSliceHeight <= 0) return@synchronized null

                    // Acquire high-performance reusable bitmap from BitmapPool
                    val bmp = BitmapPool.acquire(totalWidth, actualSliceHeight, Bitmap.Config.ARGB_8888)

                    val scaleX = totalWidth.toFloat() / widthPt
                    val scaleY = totalHeight.toFloat() / heightPt

                    val matrix = Matrix()
                    matrix.postScale(scaleX, scaleY)
                    matrix.postTranslate(0f, -sliceY.toFloat())

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

            // Asynchronously save to WebP cache for future lightning-fast loads!
            if (qualitySelectionEnabled && bitmap != null) {
                val sliceBitmapCopy = try {
                    bitmap.copy(bitmap.config ?: Bitmap.Config.ARGB_8888, false)
                } catch (e: Throwable) {
                    null
                }
                if (sliceBitmapCopy != null) {
                    CoroutineScope(cacheDispatcher).launch {
                        preCacheSliceAsWebP(
                            pageIndex = pageIndex,
                            sliceIndex = sliceIndex,
                            sliceBitmap = sliceBitmapCopy,
                            webpDir = webpDir,
                            qualityLevel = qualityLevel,
                            qualityCompression = qualityCompression,
                            maxStorageAllocationMb = maxStorageAllocationMb
                        )
                    }
                }
            }

            bitmap
        } catch (e: Throwable) {
            e.printStackTrace()
            null
        }
    }

    private suspend fun preCacheSliceAsWebP(
        pageIndex: Int,
        sliceIndex: Int,
        sliceBitmap: Bitmap,
        webpDir: File,
        qualityLevel: String,
        qualityCompression: Int,
        maxStorageAllocationMb: Int
    ) = withContext(cacheDispatcher) {
        val taskKey = "${pageIndex}_s${sliceIndex}_${qualityLevel}"
        if (activeCacheTasks.putIfAbsent(taskKey, true) != null) {
            sliceBitmap.recycle()
            return@withContext
        }
        try {
            if (!webpDir.exists()) webpDir.mkdirs()
            val webpFile = File(webpDir, "$taskKey.webp")
            if (webpFile.exists() && webpFile.length() > 0) return@withContext

            val tempFile = File(webpDir, "$taskKey.webp.tmp")
            FileOutputStream(tempFile).use { out ->
                val compressFormat = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                    Bitmap.CompressFormat.WEBP_LOSSY
                } else {
                    @Suppress("DEPRECATION")
                    Bitmap.CompressFormat.WEBP
                }
                sliceBitmap.compress(compressFormat, qualityCompression, out)
            }
            tempFile.renameTo(webpFile)
            checkAndManageCacheSize(maxStorageAllocationMb)
        } catch (e: Throwable) {
            e.printStackTrace()
        } finally {
            sliceBitmap.recycle()
            activeCacheTasks.remove(taskKey)
        }
    }

    private fun checkAndManageCacheSize(maxSizeMb: Int) {
        val webpParentDir = File(context.cacheDir, "webp_cache")
        if (!webpParentDir.exists()) return

        val maxSizeBytes = maxSizeMb.toLong() * 1024 * 1024
        val allFiles = webpParentDir.walkTopDown().filter { it.isFile && it.extension == "webp" }.toList()
        
        var totalSize = allFiles.sumOf { it.length() }
        if (totalSize > maxSizeBytes) {
            val sortedFiles = allFiles.sortedBy { f -> f.lastModified() }
            for (f in sortedFiles) {
                val size = f.length()
                if (f.delete()) {
                    totalSize -= size
                    if (totalSize <= maxSizeBytes * 0.8) {
                        break
                    }
                }
            }
        }
    }

    suspend fun renderPage(
        pageIndex: Int,
        targetWidth: Int,
        scaleFactor: Float = 1.5f,
        qualitySelectionEnabled: Boolean = true,
        qualityLevel: String = "HIGH",
        qualityCompression: Int = 90,
        maxStorageAllocationMb: Int = 500
    ): Bitmap? {
        val aspect = getPageAspectRatio(pageIndex)
        val totalWidth = (targetWidth * scaleFactor).toInt().coerceAtLeast(400)
        val totalHeight = (totalWidth * aspect).toInt().coerceAtLeast(400)
        return renderPageSlice(
            pageIndex, targetWidth, 0, totalHeight, scaleFactor,
            qualitySelectionEnabled, qualityLevel, qualityCompression, maxStorageAllocationMb
        )
    }

    fun clearCache() {
        memoryCache.evictAll()
        aspectRatios.clear()
        BitmapPool.clear()
    }

    fun close() {
        clearCache()
        try {
            cacheExecutor.shutdown()
        } catch (e: Throwable) {
            e.printStackTrace()
        }
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
