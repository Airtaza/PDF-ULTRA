package com.example.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class WebPCacheManager(private val context: Context, private val pdfIdentifier: String) {
    private val cacheDir = File(context.cacheDir, "webp_cache/$pdfIdentifier").apply {
        if (!exists()) mkdirs()
    }

    // Memory Cache for recently viewed pages (capped to ~30MB)
    private val memoryCache = object : LruCache<String, Bitmap>(30 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int {
            return value.byteCount
        }
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
    }

    suspend fun saveToCache(key: String, bitmap: Bitmap, quality: Int = 80) = withContext(Dispatchers.IO) {
        // Also put in memory cache for immediate access
        memoryCache.put(key, bitmap)
        
        try {
            val file = File(cacheDir, "$key.webp")
            if (!file.exists()) {
                val tempFile = File(cacheDir, "$key.webp.tmp")
                FileOutputStream(tempFile).use { out ->
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                        bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSY, quality, out)
                    } else {
                        @Suppress("DEPRECATION")
                        bitmap.compress(Bitmap.CompressFormat.WEBP, quality, out)
                    }
                }
                tempFile.renameTo(file)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    suspend fun getFromCache(key: String, bitmapConfig: String = "ARGB_8888"): Bitmap? = withContext(Dispatchers.IO) {
        // Check memory cache first
        memoryCache.get(key)?.let { return@withContext it }
        
        try {
            val file = File(cacheDir, "$key.webp")
            if (file.exists()) {
                val options = BitmapFactory.Options().apply {
                    inPreferredConfig = if (bitmapConfig == "RGB_565") Bitmap.Config.RGB_565 else Bitmap.Config.ARGB_8888
                    inMutable = true
                }
                val bitmap = BitmapFactory.decodeFile(file.absolutePath, options)
                if (bitmap != null) {
                    memoryCache.put(key, bitmap)
                }
                bitmap
            } else {
                null
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    fun clearMemoryCache() {
        memoryCache.evictAll()
    }

    suspend fun clearCache() = withContext(Dispatchers.IO) {
        clearMemoryCache()
        cacheDir.deleteRecursively()
        cacheDir.mkdirs()
    }
}
