package com.example.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class ManhwaRepository(private val context: Context, private val dao: ManhwaDao) {

    val allManhwas: Flow<List<Manhwa>> = dao.getAllManhwas()
    val allPlugins: Flow<List<PluginConfig>> = dao.getAllPlugins()

    suspend fun getManhwaById(id: Long): Manhwa? = dao.getManhwaById(id)

    suspend fun updateManhwa(manhwa: Manhwa) = dao.updateManhwa(manhwa)

    fun getBookmarksForManhwa(manhwaId: Long): Flow<List<Bookmark>> = dao.getBookmarksForManhwa(manhwaId)

    suspend fun getBookmarkByPage(manhwaId: Long, pageIndex: Int): Bookmark? = dao.getBookmarkByPage(manhwaId, pageIndex)

    suspend fun addBookmark(bookmark: Bookmark) = dao.insertBookmark(bookmark)

    suspend fun removeBookmark(bookmark: Bookmark) = dao.deleteBookmark(bookmark)

    suspend fun updatePlugin(plugin: PluginConfig) = dao.insertPlugin(plugin)

    suspend fun importPdf(uri: Uri): Long = withContext(Dispatchers.IO) {
        var name = "Imported_Manhwa.pdf"
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (nameIndex != -1 && cursor.moveToFirst()) {
                name = cursor.getString(nameIndex)
            }
        }

        val title = if (name.endsWith(".pdf", ignoreCase = true)) {
            name.substring(0, name.length - 4)
        } else {
            name
        }

        val dir = File(context.filesDir, "manhwas")
        if (!dir.exists()) {
            dir.mkdirs()
        }

        val cleanFileName = "manhwa_${System.currentTimeMillis()}.pdf"
        val destFile = File(dir, cleanFileName)

        context.contentResolver.openInputStream(uri)?.use { inputStream ->
            FileOutputStream(destFile).use { outputStream ->
                inputStream.copyTo(outputStream)
            }
        } ?: throw Exception("Failed to open input stream")

        var totalPages = 0
        try {
            val parcelFileDescriptor = android.os.ParcelFileDescriptor.open(destFile, android.os.ParcelFileDescriptor.MODE_READ_ONLY)
            val renderer = android.graphics.pdf.PdfRenderer(parcelFileDescriptor)
            totalPages = renderer.pageCount
            renderer.close()
            parcelFileDescriptor.close()
        } catch (e: Exception) {
            if (destFile.exists()) {
                destFile.delete()
            }
            throw e
        }

        val manhwa = Manhwa(
            title = title,
            filePath = destFile.absolutePath,
            totalPages = totalPages
        )
        dao.insertManhwa(manhwa)
    }

    suspend fun deleteManhwa(manhwa: Manhwa) = withContext(Dispatchers.IO) {
        val file = File(manhwa.filePath)
        if (file.exists()) {
            file.delete()
        }
        dao.deleteManhwa(manhwa)
    }
}
