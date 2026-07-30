package com.example.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import java.io.File
import java.io.FileOutputStream
import java.util.Collections
import java.util.LinkedList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class WebPCacheManager(private val context: Context, private val pdfIdentifier: String) {
    private val cacheDir = File(context.cacheDir, "webp_cache/$pdfIdentifier").apply {
        if (!exists()) mkdirs()
    }

    // Bitmap Pool for zero-allocation fast decoding
    private val bitmapPool = Collections.synchronizedList(LinkedList<Bitmap>())

    // Memory Cache for recently viewed pages (15% of available memory or 20MB)
    private val memoryCache = object : LruCache<String, Bitmap>(20 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int {
            return value.byteCount
        }

        override fun entryRemoved(evicted: Boolean, key: String, oldValue: Bitmap, newValue: Bitmap?) {
            if (evicted) {
                releaseBitmap(oldValue)
            }
        }
    }

    fun releaseBitmap(bitmap: Bitmap) {
        if (!bitmap.isRecycled) {
            if (bitmapPool.size < 10) { // Keep up to 10 bitmaps in the pool
                bitmapPool.add(bitmap)
            } else {
                bitmap.recycle()
            }
        }
    }

    fun getReusableBitmap(width: Int, height: Int, config: Bitmap.Config): Bitmap? {
        val targetBytes = if (config == Bitmap.Config.RGB_565) {
            width * height * 2
        } else {
            width * height * 4
        }
        
        synchronized(bitmapPool) {
            val iterator = bitmapPool.iterator()
            while (iterator.hasNext()) {
                val item = iterator.next()
                if (!item.isRecycled && item.allocationByteCount >= targetBytes && item.isMutable) {
                    iterator.remove()
                    try {
                        item.reconfigure(width, height, config)
                        return item
                    } catch (e: Throwable) {
                        e.printStackTrace()
                    }
                }
            }
        }
        return null
    }

    private fun getReusableBitmap(options: BitmapFactory.Options): Bitmap? {
        val width = options.outWidth
        val height = options.outHeight
        val config = options.inPreferredConfig ?: Bitmap.Config.ARGB_8888
        return getReusableBitmap(width, height, config)
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
                    inJustDecodeBounds = true
                }
                BitmapFactory.decodeFile(file.absolutePath, options)
                
                options.inJustDecodeBounds = false
                options.inPreferredConfig = if (bitmapConfig == "RGB_565") Bitmap.Config.RGB_565 else Bitmap.Config.ARGB_8888
                options.inMutable = true
                
                val reusable = getReusableBitmap(options)
                if (reusable != null) {
                    options.inBitmap = reusable
                }
                
                val bitmap = try {
                    BitmapFactory.decodeFile(file.absolutePath, options)
                } catch (e: IllegalArgumentException) {
                    // Fallback if inBitmap fails
                    options.inBitmap = null
                    BitmapFactory.decodeFile(file.absolutePath, options)
                }
                
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
        synchronized(bitmapPool) {
            bitmapPool.forEach { 
                try {
                    if (!it.isRecycled) it.recycle()
                } catch (e: Throwable) {
                    e.printStackTrace()
                }
            }
            bitmapPool.clear()
        }
    }

    suspend fun clearCache() = withContext(Dispatchers.IO) {
        clearMemoryCache()
        cacheDir.deleteRecursively()
        cacheDir.mkdirs()
    }
}
