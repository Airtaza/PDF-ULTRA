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

        val destFile = File(dir, "Multi_Aspect_Test_Suite_${System.currentTimeMillis()}.pdf")

        val pdfDocument = android.graphics.pdf.PdfDocument()

        data class DemoPageSpec(
            val widthPt: Int,
            val heightPt: Int,
            val bgColorHex: String,
            val titleText: String,
            val descText: String
        )

        val pageSpecs = listOf(
            DemoPageSpec(720, 1018, "#E53935", "PAGE 1 - STANDARD A4 PORTRAIT", "Aspect Ratio: ~1.414 (720x1018 pt)\nFormat: Standard Book / Document Page\nTests: Standard vertical scaling, text sharpness & reading ruler."),
            DemoPageSpec(720, 5040, "#0D47A1", "PAGE 2 - ULTRA-TALL LONG-STRIP MANHWA", "Aspect Ratio: 7.000 (720x5040 pt)\nFormat: Webtoon Long-Strip Continuous Comic\nTests: Slicing engine, gapless vertical scrolling & custom aspect tuning."),
            DemoPageSpec(1280, 720, "#2E7D32", "PAGE 3 - 16:9 WIDESCREEN DOUBLE SPREAD", "Aspect Ratio: 0.562 (1280x720 pt)\nFormat: Double Page Spread / Landscape\nTests: Landscape split mode (Left/Right half) & widescreen canvas fit."),
            DemoPageSpec(720, 720, "#E65100", "PAGE 4 - 1:1 SQUARE SPLASH PANEL", "Aspect Ratio: 1.000 (720x720 pt)\nFormat: Square Splash Artwork\nTests: 1:1 aspect calculation & concentric target calibration."),
            DemoPageSpec(720, 2160, "#6A1B9A", "PAGE 5 - 3:1 MEDIUM WEBTOON STRIP", "Aspect Ratio: 3.000 (720x2160 pt)\nFormat: Medium Webtoon Strip (3 Panels)\nTests: Multi-slice rendering & custom multiplier fine tuning."),
            DemoPageSpec(600, 6000, "#212121", "PAGE 6 - EXTREME ULTRA-LONG STRIP", "Aspect Ratio: 10.000 (600x6000 pt)\nFormat: 10:1 Continuous Battle Scene\nTests: Max aspect clamp & memory slice allocation under stress."),
            DemoPageSpec(720, 930, "#00838F", "PAGE 7 - US GRAPHIC NOVEL PAGE", "Aspect Ratio: 1.291 (720x930 pt)\nFormat: Standard US Comic Book\nTests: 6-panel grid layout, artwork trim, & custom engine tuning.")
        )

        pageSpecs.forEachIndexed { index, spec ->
            val pageInfo = android.graphics.pdf.PdfDocument.PageInfo.Builder(spec.widthPt, spec.heightPt, index + 1).create()
            val page = pdfDocument.startPage(pageInfo)
            val canvas = page.canvas

            val w = spec.widthPt.toFloat()
            val h = spec.heightPt.toFloat()

            // 1. Draw Background
            val bgPaint = android.graphics.Paint().apply {
                color = android.graphics.Color.parseColor(spec.bgColorHex)
                style = android.graphics.Paint.Style.FILL
            }
            canvas.drawRect(0f, 0f, w, h, bgPaint)

            // 2. Draw Grid & Measurement Ruler Lines
            val gridPaint = android.graphics.Paint().apply {
                color = android.graphics.Color.argb(40, 255, 255, 255)
                strokeWidth = 2f
                style = android.graphics.Paint.Style.STROKE
            }
            var gridY = 200f
            while (gridY < h) {
                canvas.drawLine(0f, gridY, w, gridY, gridPaint)
                gridY += 200f
            }
            var gridX = 100f
            while (gridX < w) {
                canvas.drawLine(gridX, 0f, gridX, h, gridPaint)
                gridX += 100f
            }

            // Height Ruler Markings on Right Edge
            val rulerPaint = android.graphics.Paint().apply {
                color = android.graphics.Color.argb(180, 255, 255, 255)
                textSize = 14f
                isAntiAlias = true
            }
            var tickY = 500f
            while (tickY < h) {
                canvas.drawLine(w - 40f, tickY, w, tickY, gridPaint)
                canvas.drawText("${tickY.toInt()}pt", w - 90f, tickY - 6f, rulerPaint)
                tickY += 500f
            }

            val textColor = android.graphics.Color.WHITE

            // 3. Draw Header Title
            val titlePaint = android.graphics.Paint().apply {
                color = textColor
                textSize = 32f
                isFakeBoldText = true
                isAntiAlias = true
            }
            canvas.drawText(spec.titleText, 40f, 80f, titlePaint)

            // 4. Draw Subtitle / Badge
            val badgePaint = android.graphics.Paint().apply {
                color = android.graphics.Color.argb(60, 255, 255, 255)
                style = android.graphics.Paint.Style.FILL
            }
            canvas.drawRoundRect(40f, 100f, 480f, 145f, 12f, 12f, badgePaint)

            val badgeTextPaint = android.graphics.Paint().apply {
                color = textColor
                textSize = 16f
                isFakeBoldText = true
                isAntiAlias = true
            }
            canvas.drawText("ASPECT RATIO TEST SUITE", 55f, 130f, badgeTextPaint)

            // 5. Draw Description Box
            val descBoxPaint = android.graphics.Paint().apply {
                color = android.graphics.Color.argb(50, 0, 0, 0)
                style = android.graphics.Paint.Style.FILL
            }
            canvas.drawRoundRect(40f, 160f, (w - 40f).coerceAtLeast(300f), 300f, 16f, 16f, descBoxPaint)

            val bodyPaint = android.graphics.Paint().apply {
                color = textColor
                textSize = 18f
                isAntiAlias = true
            }
            val lines = spec.descText.split("\n")
            var lineY = 195f
            for (line in lines) {
                canvas.drawText(line, 60f, lineY, bodyPaint)
                lineY += 28f
            }

            // 6. Draw Comic Panel Boxes & Target Calibration Circles
            var panelY = 340f
            val panelBorderPaint = android.graphics.Paint().apply {
                color = android.graphics.Color.WHITE
                style = android.graphics.Paint.Style.STROKE
                strokeWidth = 4f
                isAntiAlias = true
            }
            val panelFillPaint = android.graphics.Paint().apply {
                color = android.graphics.Color.argb(30, 255, 255, 255)
                style = android.graphics.Paint.Style.FILL
            }

            var panelNum = 1
            while (panelY + 280f < h - 100f) {
                val panelRect = android.graphics.RectF(50f, panelY, w - 50f, panelY + 280f)
                canvas.drawRoundRect(panelRect, 12f, 12f, panelFillPaint)
                canvas.drawRoundRect(panelRect, 12f, 12f, panelBorderPaint)

                bodyPaint.textSize = 22f
                bodyPaint.isFakeBoldText = true
                canvas.drawText("Panel $panelNum - Scene Artwork Block (Y: ${panelY.toInt()}pt)", 70f, panelY + 50f, bodyPaint)

                // Calibration Target
                val centerX = w / 2f
                val centerY = panelY + 160f
                val circlePaint = android.graphics.Paint().apply {
                    color = android.graphics.Color.WHITE
                    style = android.graphics.Paint.Style.STROKE
                    strokeWidth = 3f
                    isAntiAlias = true
                }
                canvas.drawCircle(centerX, centerY, 60f, circlePaint)
                canvas.drawCircle(centerX, centerY, 30f, circlePaint)
                canvas.drawCircle(centerX, centerY, 6f, circlePaint)

                panelY += 340f
                panelNum++
            }

            pdfDocument.finishPage(page)
        }

        FileOutputStream(destFile).use { out ->
            pdfDocument.writeTo(out)
        }
        pdfDocument.close()

        val manhwa = Manhwa(
            title = "Multi-Aspect Test Suite (7 Types)",
            filePath = destFile.absolutePath,
            totalPages = pageSpecs.size
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
