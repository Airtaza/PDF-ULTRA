package com.example.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class ManhwaPdfRenderer(private val context: Context, private val file: File) {

    private var parcelFileDescriptor: ParcelFileDescriptor? = null
    private var pdfRenderer: PdfRenderer? = null
    private val aspectRatios = java.util.concurrent.ConcurrentHashMap<Int, Float>()

    // Cache to hold rendered page bitmaps (increased to 60 slices to keep more pages in memory and prevent scroll reload lag)
    private val memoryCache: LruCache<String, Bitmap> = object : LruCache<String, Bitmap>(60) {
        override fun entryRemoved(evicted: Boolean, key: String?, oldValue: Bitmap?, newValue: Bitmap?) {
            if (evicted) {
                oldValue?.recycle()
            }
        }
    }

    val pageCount: Int
        get() = synchronized(this) { pdfRenderer?.pageCount ?: 0 }

    init {
        try {
            parcelFileDescriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            pdfRenderer = PdfRenderer(parcelFileDescriptor!!)
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
        scaleFactor: Float = 1.5f
    ): Bitmap? = withContext(Dispatchers.IO) {
        val cacheKey = "${pageIndex}_$sliceIndex"
        val cached = memoryCache.get(cacheKey)
        if (cached != null && !cached.isRecycled) {
            return@withContext cached
        }

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

                    val bitmap = Bitmap.createBitmap(totalWidth, actualSliceHeight, Bitmap.Config.ARGB_8888)
                    bitmap.eraseColor(android.graphics.Color.WHITE)

                    val scaleX = totalWidth.toFloat() / widthPt
                    val scaleY = totalHeight.toFloat() / heightPt

                    val matrix = Matrix()
                    matrix.postScale(scaleX, scaleY)
                    matrix.postTranslate(0f, -sliceY.toFloat())

                    page.render(bitmap, null, matrix, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    memoryCache.put(cacheKey, bitmap)
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

    suspend fun renderPage(pageIndex: Int, targetWidth: Int, scaleFactor: Float = 1.5f): Bitmap? {
        val aspect = getPageAspectRatio(pageIndex)
        val totalWidth = (targetWidth * scaleFactor).toInt().coerceAtLeast(400)
        val totalHeight = (totalWidth * aspect).toInt().coerceAtLeast(400)
        return renderPageSlice(pageIndex, targetWidth, 0, totalHeight, scaleFactor)
    }

    fun clearCache() {
        memoryCache.evictAll()
        aspectRatios.clear()
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
