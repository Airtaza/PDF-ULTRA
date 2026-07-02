package com.example.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class WebPCacheManager(private val context: Context, private val pdfIdentifier: String) {
    private val cacheDir = File(context.cacheDir, "webp_cache/$pdfIdentifier").apply {
        if (!exists()) mkdirs()
    }

    suspend fun saveToCache(key: String, bitmap: Bitmap, quality: Int = 80) = withContext(Dispatchers.IO) {
        try {
            val file = File(cacheDir, "$key.webp")
            if (!file.exists()) {
                FileOutputStream(file).use { out ->
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                        bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSY, quality, out)
                    } else {
                        @Suppress("DEPRECATION")
                        bitmap.compress(Bitmap.CompressFormat.WEBP, quality, out)
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    suspend fun getFromCache(key: String, bitmapConfig: String = "ARGB_8888"): Bitmap? = withContext(Dispatchers.IO) {
        try {
            val file = File(cacheDir, "$key.webp")
            if (file.exists()) {
                val options = BitmapFactory.Options().apply {
                    inPreferredConfig = if (bitmapConfig == "RGB_565") Bitmap.Config.RGB_565 else Bitmap.Config.ARGB_8888
                }
                BitmapFactory.decodeFile(file.absolutePath, options)
            } else {
                null
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    suspend fun clearCache() = withContext(Dispatchers.IO) {
        cacheDir.deleteRecursively()
        cacheDir.mkdirs()
    }
}
