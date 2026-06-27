package com.example.pdf

import android.content.Context
import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import android.util.LruCache
import com.shockwave.pdfium.PdfDocument
import com.shockwave.pdfium.PdfiumCore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class ManhwaPdfRenderer(private val context: Context, private val file: File) {

    private var parcelFileDescriptor: ParcelFileDescriptor? = null
    private var pdfiumCore: PdfiumCore? = null
    private var pdfDocument: PdfDocument? = null

    // Cache to hold rendered page bitmaps (size = 30 tiles/slices to avoid OOM while enabling adjacent page prefetching)
    private val memoryCache: LruCache<String, Bitmap> = object : LruCache<String, Bitmap>(30) {
        override fun entryRemoved(evicted: Boolean, key: String?, oldValue: Bitmap?, newValue: Bitmap?) {
            if (evicted) {
                oldValue?.recycle()
            }
        }
    }

    val pageCount: Int
        get() = pdfiumCore?.getPageCount(pdfDocument) ?: 0

    init {
        try {
            parcelFileDescriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            pdfiumCore = PdfiumCore(context.applicationContext)
            pdfDocument = pdfiumCore!!.newDocument(parcelFileDescriptor)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun getPageAspectRatio(pageIndex: Int): Float {
        val core = pdfiumCore ?: return 1.414f
        val doc = pdfDocument ?: return 1.414f
        if (pageIndex < 0 || pageIndex >= core.getPageCount(doc)) return 1.414f
        return try {
            synchronized(doc) {
                core.openPage(doc, pageIndex)
                val widthPt = core.getPageWidthPoint(doc, pageIndex)
                val heightPt = core.getPageHeightPoint(doc, pageIndex)
                heightPt.toFloat() / widthPt.toFloat()
            }
        } catch (e: Exception) {
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

        val core = pdfiumCore ?: return@withContext null
        val doc = pdfDocument ?: return@withContext null
        if (pageIndex < 0 || pageIndex >= core.getPageCount(doc)) return@withContext null

        try {
            synchronized(doc) {
                core.openPage(doc, pageIndex)
                val widthPt = core.getPageWidthPoint(doc, pageIndex)
                val heightPt = core.getPageHeightPoint(doc, pageIndex)
                val pageAspectRatio = heightPt.toFloat() / widthPt.toFloat()

                val totalWidth = (targetWidth * scaleFactor).toInt().coerceAtLeast(400)
                val totalHeight = (totalWidth * pageAspectRatio).toInt().coerceAtLeast(400)

                val sliceY = sliceIndex * sliceHeight
                val actualSliceHeight = (totalHeight - sliceY).coerceAtMost(sliceHeight)

                if (actualSliceHeight <= 0) return@withContext null

                val bitmap = Bitmap.createBitmap(totalWidth, actualSliceHeight, Bitmap.Config.ARGB_8888)
                bitmap.eraseColor(android.graphics.Color.WHITE)

                core.renderPageBitmap(doc, bitmap, pageIndex, 0, -sliceY, totalWidth, totalHeight, true)
                memoryCache.put(cacheKey, bitmap)
                return@withContext bitmap
            }
        } catch (e: Exception) {
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
    }

    fun close() {
        clearCache()
        try {
            pdfiumCore?.closeDocument(pdfDocument)
            parcelFileDescriptor?.close()
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            pdfiumCore = null
            pdfDocument = null
            parcelFileDescriptor = null
        }
    }
}
