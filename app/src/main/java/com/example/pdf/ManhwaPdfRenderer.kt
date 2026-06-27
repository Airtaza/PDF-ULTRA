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
        val webpFile = File(webpDir, "${pageIndex}_${qualityLevel}.webp")

        if (qualitySelectionEnabled && webpFile.exists() && webpFile.length() > 0) {
            try {
                // Read from WebP cache!
                val fullBitmap = BitmapFactory.decodeFile(webpFile.absolutePath)
                if (fullBitmap != null) {
                    val sliceY = sliceIndex * sliceHeight
                    val actualSliceHeight = (fullBitmap.height - sliceY).coerceAtMost(sliceHeight)
                    if (actualSliceHeight > 0) {
                        val sliceBitmap = BitmapPool.acquire(fullBitmap.width, actualSliceHeight, Bitmap.Config.ARGB_8888)
                        val canvas = android.graphics.Canvas(sliceBitmap)
                        val srcRect = android.graphics.Rect(0, sliceY, fullBitmap.width, sliceY + actualSliceHeight)
                        val destRect = android.graphics.Rect(0, 0, fullBitmap.width, actualSliceHeight)
                        canvas.drawBitmap(fullBitmap, srcRect, destRect, null)
                        
                        fullBitmap.recycle()
                        
                        // Save to LruCache
                        memoryCache.put(cacheKey, sliceBitmap)
                        return@withContext sliceBitmap
                    }
                    fullBitmap.recycle()
                }
            } catch (e: Throwable) {
                e.printStackTrace()
            }
        }

        // Fallback or Normal PDF Render
        val renderer = pdfRenderer ?: return@withContext null
        if (pageIndex < 0 || pageIndex >= renderer.pageCount) return@withContext null

        try {
            synchronized(this@ManhwaPdfRenderer) {
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
                    val bitmap = BitmapPool.acquire(totalWidth, actualSliceHeight, Bitmap.Config.ARGB_8888)

                    val scaleX = totalWidth.toFloat() / widthPt
                    val scaleY = totalHeight.toFloat() / heightPt

                    val matrix = Matrix()
                    matrix.postScale(scaleX, scaleY)
                    matrix.postTranslate(0f, -sliceY.toFloat())

                    page.render(bitmap, null, matrix, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    memoryCache.put(cacheKey, bitmap)

                    // Asynchronously save to WebP cache for future lightning-fast loads!
                    if (qualitySelectionEnabled) {
                        CoroutineScope(Dispatchers.IO).launch {
                            preCachePageAsWebP(pageIndex, targetWidth, scaleFactor, qualityLevel, qualityCompression, maxStorageAllocationMb)
                        }
                    }

                    bitmap
                } finally {
                    page.close()
                }
            }
        } catch (e: Throwable) {
            e.printStackTrace()
            null
        }
    }

    private suspend fun preCachePageAsWebP(
        pageIndex: Int,
        targetWidth: Int,
        scaleFactor: Float,
        qualityLevel: String,
        qualityCompression: Int,
        maxStorageAllocationMb: Int
    ) = withContext(Dispatchers.IO) {
        try {
            val webpDir = File(context.cacheDir, "webp_cache/${file.nameWithoutExtension}")
            if (!webpDir.exists()) webpDir.mkdirs()
            val webpFile = File(webpDir, "${pageIndex}_${qualityLevel}.webp")
            if (webpFile.exists() && webpFile.length() > 0) return@withContext

            val renderer = pdfRenderer ?: return@withContext
            synchronized(this@ManhwaPdfRenderer) {
                if (pdfRenderer == null) return@synchronized
                if (pageIndex < 0 || pageIndex >= renderer.pageCount) return@synchronized
                
                val page = renderer.openPage(pageIndex)
                try {
                    val widthPt = page.width
                    val heightPt = page.height
                    val pageAspectRatio = heightPt.toFloat() / widthPt.toFloat()

                    val totalWidth = (targetWidth * scaleFactor).toInt().coerceAtLeast(400)
                    val totalHeight = (totalWidth * pageAspectRatio).toInt().coerceAtLeast(400)

                    val bitmap = Bitmap.createBitmap(totalWidth, totalHeight, Bitmap.Config.ARGB_8888)
                    val matrix = Matrix()
                    matrix.postScale(totalWidth.toFloat() / widthPt, totalHeight.toFloat() / heightPt)
                    page.render(bitmap, null, matrix, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)

                    val tempFile = File(webpDir, "${pageIndex}_${qualityLevel}.webp.tmp")
                    FileOutputStream(tempFile).use { out ->
                        bitmap.compress(Bitmap.CompressFormat.WEBP, qualityCompression, out)
                    }
                    tempFile.renameTo(webpFile)
                    bitmap.recycle()

                    // evict cache if needed
                    checkAndManageCacheSize(maxStorageAllocationMb)
                } catch (e: Throwable) {
                    e.printStackTrace()
                } finally {
                    page.close()
                }
            }
        } catch (e: Throwable) {
            e.printStackTrace()
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
