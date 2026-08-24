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

    suspend fun pruneOldReadingPositions(thresholdTimestamp: Long): Int = dao.pruneOldReadingPositions(thresholdTimestamp)

    fun getBookmarksForManhwa(manhwaId: Long): Flow<List<Bookmark>> = dao.getBookmarksForManhwa(manhwaId)

    suspend fun getBookmarkByPage(manhwaId: Long, pageIndex: Int): Bookmark? = dao.getBookmarkByPage(manhwaId, pageIndex)

    suspend fun addBookmark(bookmark: Bookmark) = dao.insertBookmark(bookmark)

    suspend fun removeBookmark(bookmark: Bookmark) = dao.deleteBookmark(bookmark)

    // Page Notes
    fun getPageNotesForManhwa(manhwaId: Long): Flow<List<PageNote>> = dao.getPageNotesForManhwa(manhwaId)

    suspend fun getPageNoteByPage(manhwaId: Long, pageIndex: Int): PageNote? = dao.getPageNoteByPage(manhwaId, pageIndex)

    suspend fun savePageNote(note: PageNote) = dao.insertPageNote(note)

    suspend fun deletePageNote(note: PageNote) = dao.deletePageNote(note)

    suspend fun deletePageNoteByPage(manhwaId: Long, pageIndex: Int) = dao.deletePageNoteByPage(manhwaId, pageIndex)

    suspend fun updatePlugin(plugin: PluginConfig) = dao.insertPlugin(plugin)

    val allReadingEvents: Flow<List<ReadingEvent>> = dao.getAllReadingEvents()

    suspend fun logReadingEvent(manhwaId: Long, pageIndex: Int, virtualPageIndex: Int = -1, durationSeconds: Int = 0) = withContext(Dispatchers.IO) {
        dao.insertReadingEvent(ReadingEvent(manhwaId = manhwaId, pageIndex = pageIndex, virtualPageIndex = virtualPageIndex, durationSeconds = durationSeconds))
    }

    suspend fun clearReadingStats() = withContext(Dispatchers.IO) {
        dao.clearAllReadingEvents()
    }

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

    suspend fun createDummyTestPdf(): Long = withContext(Dispatchers.IO) {
        val dir = File(context.filesDir, "manhwas")
        if (!dir.exists()) dir.mkdirs()

        val destFile = File(dir, "Dummy_Test_Comic_${System.currentTimeMillis()}.pdf")

        val pdfDocument = android.graphics.pdf.PdfDocument()

        val pageConfigs = listOf(
            Triple(android.graphics.Color.parseColor("#E53935"), "PAGE 1 - VIVID CORAL RED", "Welcome to PDF ULTRA testing!\nTest zoom, scrollbar, auto-scroll, and reading ruler here."),
            Triple(android.graphics.Color.parseColor("#1E88E5"), "PAGE 2 - OCEAN BLUE", "Sample Chapter 1: High Contrast View\nFont size 36sp, 28sp, 20sp, 14sp testing."),
            Triple(android.graphics.Color.parseColor("#43A047"), "PAGE 3 - EMERALD GREEN", "Sample Chapter 2: Gapless Vertical Continuous Layout\nSmooth swipe speed and gesture physics."),
            Triple(android.graphics.Color.parseColor("#FB8C00"), "PAGE 4 - SUNSET ORANGE", "Sample Chapter 3: HD Page Rendering Engine\nWebP Caching & Memory Optimizations."),
            Triple(android.graphics.Color.parseColor("#8E24AA"), "PAGE 5 - ROYAL PURPLE", "Sample Chapter 4: Hands-Free Auto Scroll & Ruler\nTry toggling the floating auto-scroll button."),
            Triple(android.graphics.Color.parseColor("#00ACC1"), "PAGE 6 - CYAN TEAL", "Sample Chapter 5: Custom Tab Management\nMultiple PDFs can be open at once."),
            Triple(android.graphics.Color.parseColor("#263238"), "PAGE 7 - CHARCOAL DARK", "Sample Chapter 6: Dark Mode Contrast Test\nCheck text legibility and ruler highlights."),
            Triple(android.graphics.Color.parseColor("#D81B60"), "PAGE 8 - PASTEL MAGENTA", "Sample Chapter 7: Drawing & Annotation Test\nTry drawing or bookmarking this page."),
            Triple(android.graphics.Color.parseColor("#FDD835"), "PAGE 9 - BOLD YELLOW", "Sample Chapter 8: Multi-touch Pinch Zoom\nTest zooming in and out seamlessly."),
            Triple(android.graphics.Color.parseColor("#3E2723"), "PAGE 10 - DEEP MAHOGANY", "Sample Chapter 9: End of Test Comic!\nDouble-tap tabs to close or return to shelf.")
        )

        pageConfigs.forEachIndexed { index, (bgColor, titleText, descText) ->
            val pageInfo = android.graphics.pdf.PdfDocument.PageInfo.Builder(720, 1280, index + 1).create()
            val page = pdfDocument.startPage(pageInfo)
            val canvas = page.canvas

            // 1. Draw Background
            val bgPaint = android.graphics.Paint().apply {
                color = bgColor
                style = android.graphics.Paint.Style.FILL
            }
            canvas.drawRect(0f, 0f, 720f, 1280f, bgPaint)

            // 2. Draw Grid Lines
            val gridPaint = android.graphics.Paint().apply {
                color = android.graphics.Color.argb(40, 255, 255, 255)
                strokeWidth = 2f
                style = android.graphics.Paint.Style.STROKE
            }
            for (y in 100..1200 step 100) {
                canvas.drawLine(0f, y.toFloat(), 720f, y.toFloat(), gridPaint)
            }
            for (x in 100..700 step 100) {
                canvas.drawLine(x.toFloat(), 0f, x.toFloat(), 1280f, gridPaint)
            }

            val isYellow = (bgColor == android.graphics.Color.parseColor("#FDD835"))
            val textColor = if (isYellow) android.graphics.Color.BLACK else android.graphics.Color.WHITE

            // 3. Draw Header Title
            val titlePaint = android.graphics.Paint().apply {
                color = textColor
                textSize = 36f
                isFakeBoldText = true
                isAntiAlias = true
            }
            canvas.drawText(titleText, 40f, 120f, titlePaint)

            // 4. Draw Subtitle / Badge
            val badgePaint = android.graphics.Paint().apply {
                color = if (isYellow) android.graphics.Color.argb(60, 0, 0, 0) else android.graphics.Color.argb(60, 255, 255, 255)
                style = android.graphics.Paint.Style.FILL
            }
            canvas.drawRoundRect(40f, 150f, 420f, 200f, 25f, 25f, badgePaint)

            val badgeTextPaint = android.graphics.Paint().apply {
                color = textColor
                textSize = 20f
                isFakeBoldText = true
                isAntiAlias = true
            }
            canvas.drawText("PDF ULTRA TEST SUITE", 60f, 183f, badgeTextPaint)

            // 5. Draw Various Text Sizes for Check
            val textSizes = listOf(
                Pair(38f, "Header 38sp (Extra Large)"),
                Pair(30f, "Section Title 30sp (Large)"),
                Pair(22f, "Body Text Paragraph 22sp (Medium)"),
                Pair(16f, "Caption & Small Details 16sp (Small)"),
                Pair(12f, "Tiny Footnote & Metadata 12sp (Extra Small)")
            )

            var currentY = 270f
            val bodyPaint = android.graphics.Paint().apply {
                color = textColor
                isAntiAlias = true
            }

            textSizes.forEach { (sz, sampleStr) ->
                bodyPaint.textSize = sz
                bodyPaint.isFakeBoldText = (sz >= 26f)
                canvas.drawText(sampleStr, 40f, currentY, bodyPaint)
                currentY += sz + 24f
            }

            // 6. Draw Description Box
            currentY += 10f
            val descBoxPaint = android.graphics.Paint().apply {
                color = if (isYellow) android.graphics.Color.argb(40, 0, 0, 0) else android.graphics.Color.argb(40, 255, 255, 255)
                style = android.graphics.Paint.Style.FILL
            }
            canvas.drawRoundRect(40f, currentY, 680f, currentY + 140f, 16f, 16f, descBoxPaint)

            bodyPaint.textSize = 18f
            bodyPaint.isFakeBoldText = false
            val lines = descText.split("\n")
            var lineY = currentY + 45f
            for (line in lines) {
                canvas.drawText(line, 60f, lineY, bodyPaint)
                lineY += 32f
            }

            // 7. Draw Visual Target Circles for Zoom Testing
            val circlePaint = android.graphics.Paint().apply {
                color = textColor
                style = android.graphics.Paint.Style.STROKE
                strokeWidth = 4f
                isAntiAlias = true
            }
            canvas.drawCircle(360f, 1020f, 120f, circlePaint)
            canvas.drawCircle(360f, 1020f, 80f, circlePaint)
            canvas.drawCircle(360f, 1020f, 40f, circlePaint)

            val centerPaint = android.graphics.Paint().apply {
                color = textColor
                style = android.graphics.Paint.Style.FILL
                isAntiAlias = true
            }
            canvas.drawCircle(360f, 1020f, 10f, centerPaint)

            pdfDocument.finishPage(page)
        }

        FileOutputStream(destFile).use { out ->
            pdfDocument.writeTo(out)
        }
        pdfDocument.close()

        val manhwa = Manhwa(
            title = "Dummy Test Comic (10 Colors)",
            filePath = destFile.absolutePath,
            totalPages = pageConfigs.size
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
