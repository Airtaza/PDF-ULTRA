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
    private val thumbDir = File(cacheDir, "thumbnails").apply {
        if (!exists()) mkdirs()
    }

    // Dynamic Heap-based LRU Caching based on available device RAM
    private val maxMemoryBytes = Runtime.getRuntime().maxMemory()
    private val memCacheLimit = ((maxMemoryBytes / 8).coerceIn(16L * 1024 * 1024, 64L * 1024 * 1024)).toInt()
    private val thumbCacheLimit = ((maxMemoryBytes / 32).coerceIn(4L * 1024 * 1024, 16L * 1024 * 1024)).toInt()

    // Memory Cache for recently viewed full slices / pages
    private val memoryCache = object : LruCache<String, Bitmap>(memCacheLimit) {
        override fun sizeOf(key: String, value: Bitmap): Int {
            return value.byteCount
        }
    }

    // Dedicated Fast Memory Cache for tiny WebP low-res thumbnails
    private val thumbnailCache = object : LruCache<Int, Bitmap>(thumbCacheLimit) {
        override fun sizeOf(key: Int, value: Bitmap): Int {
            return value.byteCount
        }
    }

    suspend fun autoTrimDiskCache(maxStorageMb: Int = 150) = withContext(Dispatchers.IO) {
        try {
            val rootCacheDir = File(context.cacheDir, "webp_cache")
            if (!rootCacheDir.exists()) return@withContext
            val maxBytes = maxStorageMb.toLong() * 1024 * 1024
            val files = rootCacheDir.walkTopDown().filter { it.isFile && it.extension == "webp" }.toList()
            var totalSize = files.sumOf { it.length() }
            if (totalSize > maxBytes) {
                val sorted = files.sortedBy { it.lastModified() }
                val targetSize = (maxBytes * 0.7f).toLong()
                for (f in sorted) {
                    val len = f.length()
                    if (f.delete()) {
                        totalSize -= len
                        if (totalSize <= targetSize) break
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun hasThumbnail(pageIndex: Int): Boolean {
        if (thumbnailCache.get(pageIndex) != null) return true
        val file = File(thumbDir, "thumb_$pageIndex.webp")
        return file.exists() && file.length() > 0
    }

    fun getThumbnailFromMemory(pageIndex: Int): Bitmap? {
        val cached = thumbnailCache.get(pageIndex)
        if (cached != null && !cached.isRecycled) {
            return cached
        }
        return null
    }

    suspend fun getThumbnail(pageIndex: Int): Bitmap? = withContext(Dispatchers.IO) {
        thumbnailCache.get(pageIndex)?.let {
            if (!it.isRecycled) return@withContext it
        }

        try {
            val file = File(thumbDir, "thumb_$pageIndex.webp")
            if (file.exists() && file.length() > 0) {
                val options = BitmapFactory.Options().apply {
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                    inMutable = false
                }
                val bmp = BitmapFactory.decodeFile(file.absolutePath, options)
                if (bmp != null) {
                    thumbnailCache.put(pageIndex, bmp)
                }
                bmp
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    suspend fun saveThumbnail(pageIndex: Int, bitmap: Bitmap, quality: Int = 20) = withContext(Dispatchers.IO) {
        thumbnailCache.put(pageIndex, bitmap)
        try {
            val file = File(thumbDir, "thumb_$pageIndex.webp")
            val tempFile = File(thumbDir, "thumb_$pageIndex.webp.tmp")
            FileOutputStream(tempFile).use { out ->
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                    bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSY, quality, out)
                } else {
                    @Suppress("DEPRECATION")
                    bitmap.compress(Bitmap.CompressFormat.WEBP, quality, out)
                }
            }
            tempFile.renameTo(file)
        } catch (e: Exception) {
            e.printStackTrace()
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
        thumbnailCache.evictAll()
    }

    suspend fun clearCache() = withContext(Dispatchers.IO) {
        clearMemoryCache()
        cacheDir.deleteRecursively()
        cacheDir.mkdirs()
    }
}
