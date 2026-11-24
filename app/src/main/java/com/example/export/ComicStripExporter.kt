package com.example.export

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.core.content.FileProvider
import com.example.canvas.StickmanRenderer
import com.example.model.ReelProject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * Renders a crisp 4:5 Instagram Carousel / Comic Strip PNG (1080x1350) featuring
 * 4 key story beats from the current InkReel project with panel borders and title header.
 */
object ComicStripExporter {

    private const val TAG = "ComicStripExporter"

    suspend fun saveComicStripPng(
        context: Context,
        project: ReelProject
    ): Result<ExportResult> = withContext(Dispatchers.IO) {
        var pageBmp: Bitmap? = null
        var tileBmp: Bitmap? = null
        try {
            val pageW = 1080
            val pageH = 1350
            pageBmp = Bitmap.createBitmap(pageW, pageH, Bitmap.Config.ARGB_8888)
            val pageCanvas = Canvas(pageBmp)
            pageCanvas.drawColor(Color.parseColor("#FAF8F3"))

            val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
            val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = 6f
                color = Color.parseColor("#09090B")
            }
            val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#09090B")
                textAlign = Paint.Align.CENTER
                textSize = 44f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            }
            val badgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textAlign = Paint.Align.CENTER
                textSize = 24f
                typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            }
            val footerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#52525B")
                textAlign = Paint.Align.CENTER
                textSize = 26f
                typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            }

            val headerH = 120f
            val footerH = 74f
            val outerPad = 34f
            val gap = 22f
            val cellW = (pageW - outerPad * 2f - gap) / 2f
            val cellH = (pageH - headerH - footerH - gap) / 2f

            // Title Header
            val clippedTitle = project.title.trim().ifBlank { "InkReel Comic" }.take(34)
            pageCanvas.drawText(clippedTitle, pageW * 0.5f, 76f, titlePaint)

            // Pick 4 milestone playhead timestamps evenly across project shots
            val shots = project.shots
            val shotOffsets = LongArray(shots.size)
            var acc = 0L
            for (i in shots.indices) {
                shotOffsets[i] = acc + (shots[i].durationMs / 2L)
                acc += shots[i].durationMs
            }

            val chosenTimestamps = LongArray(4)
            if (shots.isEmpty()) {
                chosenTimestamps.fill(0L)
            } else {
                for (pIdx in 0 until 4) {
                    val mappedIdx = ((pIdx.toFloat() / 3f) * (shots.size - 1)).toInt().coerceIn(0, shots.lastIndex)
                    chosenTimestamps[pIdx] = shotOffsets[mappedIdx]
                }
            }

            // Render each panel cleanly into a 540x960 frame and center-crop/fit inside comic panel cell
            val renderW = 540
            val renderH = 960
            tileBmp = Bitmap.createBitmap(renderW, renderH, Bitmap.Config.ARGB_8888)
            val tileCanvas = Canvas(tileBmp)
            val dstRect = RectF()
            val badgeRect = RectF()

            for (idx in 0 until 4) {
                val col = idx % 2
                val row = idx / 2
                val left = outerPad + col * (cellW + gap)
                val top = headerH + row * (cellH + gap)
                val right = left + cellW
                val bottom = top + cellH

                tileCanvas.drawColor(Color.WHITE)
                StickmanRenderer.renderFrame(
                    canvas = tileCanvas,
                    width = renderW.toFloat(),
                    height = renderH.toFloat(),
                    project = project.copy(introHookBanner = "", watermarkHandle = ""),
                    playheadMs = chosenTimestamps[idx],
                    lipAmplitude = 0.5f,
                    showCaptions = true,
                    showSpeechBubbles = true,
                    showSafeZoneOverlay = false
                )

                // Fit 9:16 tile cleanly inside 4:5 cell without stretching distortion
                val scale = maxOf(cellW / renderW.toFloat(), cellH / renderH.toFloat())
                val drawnW = renderW * scale
                val drawnH = renderH * scale
                val dx = left + (cellW - drawnW) * 0.5f
                val dy = top + (cellH - drawnH) * 0.45f
                dstRect.set(dx, dy, dx + drawnW, dy + drawnH)

                pageCanvas.save()
                pageCanvas.clipRect(left, top, right, bottom)
                pageCanvas.drawBitmap(tileBmp, null, dstRect, null)
                pageCanvas.restore()

                // Crisp ink comic border
                pageCanvas.drawRect(left, top, right, bottom, borderPaint)

                // Panel number tag (#1..#4)
                bgPaint.color = Color.parseColor("#09090B")
                badgeRect.set(left + 12f, top + 12f, left + 68f, top + 52f)
                pageCanvas.drawRoundRect(badgeRect, 8f, 8f, bgPaint)
                pageCanvas.drawText("#${idx + 1}", badgeRect.centerX(), badgeRect.centerY() + 8f, badgePaint)
            }

            // Footer with creator handle
            val handle = project.watermarkHandle.trim().let {
                when {
                    it.isBlank() -> "@inkreel.studio"
                    it.startsWith("@") -> it
                    else -> "@$it"
                }
            }
            pageCanvas.drawText("$handle  •  SWIPE / SHARE COMIC", pageW * 0.5f, pageH - 26f, footerPaint)

            tileBmp.recycle()
            tileBmp = null

            val fileName = "InkReel_ComicStrip_${System.currentTimeMillis()}.png"
            val cacheDir = File(context.cacheDir, "exports").apply { mkdirs() }
            val cacheFile = File(cacheDir, fileName)
            FileOutputStream(cacheFile).use { fos ->
                pageBmp.compress(Bitmap.CompressFormat.PNG, 100, fos)
            }
            pageBmp.recycle()
            pageBmp = null

            val displayPath = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                    put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/InkReel Studio")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
                val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                if (uri != null) {
                    try {
                        val os = context.contentResolver.openOutputStream(uri)
                            ?: throw IllegalStateException("Unable to open Pictures output stream.")
                        os.use { out ->
                            cacheFile.inputStream().use { it.copyTo(out) }
                        }
                        values.clear()
                        values.put(MediaStore.Images.Media.IS_PENDING, 0)
                        context.contentResolver.update(uri, values, null, null)
                        "Pictures/InkReel Studio/$fileName"
                    } catch (e: Exception) {
                        runCatching { context.contentResolver.delete(uri, null, null) }
                        throw e
                    }
                } else {
                    cacheFile.absolutePath
                }
            } else {
                cacheFile.absolutePath
            }

            val shareUri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                cacheFile
            )
            Result.success(ExportResult(shareUri, displayPath, cacheFile.length()))
        } catch (e: Exception) {
            Log.e(TAG, "Comic strip export failed", e)
            Result.failure(e)
        } finally {
            runCatching { if (tileBmp != null && !tileBmp.isRecycled) tileBmp.recycle() }
            runCatching { if (pageBmp != null && !pageBmp.isRecycled) pageBmp.recycle() }
        }
    }
}
