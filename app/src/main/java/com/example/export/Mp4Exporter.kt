package com.example.export

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.view.Surface
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.example.R
import com.example.audio.ProceduralAudioEngine
import com.example.canvas.StickmanRenderer
import com.example.model.ReelProject
import com.example.model.SpeakerSlot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import kotlin.math.sqrt

data class ExportResult(
    val shareUri: Uri,
    val displayPath: String,
    val fileSizeBytes: Long
)

object Mp4Exporter {

    private const val TAG = "Mp4Exporter"
    private const val FPS = 30
    private const val VIDEO_MIME = MediaFormat.MIMETYPE_VIDEO_AVC
    private const val AUDIO_MIME = MediaFormat.MIMETYPE_AUDIO_AAC
    private const val EXPORT_CHANNEL_ID = "inkreel_exports"

    private class PendingPacket(
        val isVideo: Boolean,
        val bytes: ByteArray,
        val ptsUs: Long,
        val flags: Int
    )

    suspend fun savePngThumbnail(
        context: Context,
        project: ReelProject,
        playheadMs: Long,
        lipAmplitude: Float
    ): Result<ExportResult> = withContext(Dispatchers.IO) {
        var bmp: Bitmap? = null
        try {
            val w = project.exportResolution.width
            val h = project.exportResolution.height
            bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            StickmanRenderer.renderFrame(
                canvas = canvas,
                width = w.toFloat(),
                height = h.toFloat(),
                project = project,
                playheadMs = playheadMs,
                lipAmplitude = lipAmplitude,
                showCaptions = true,
                showSpeechBubbles = true
            )

            val fileName = "InkReel_${System.currentTimeMillis()}.png"
            val cacheDir = File(context.cacheDir, "exports").apply { mkdirs() }
            val cacheFile = File(cacheDir, fileName)
            FileOutputStream(cacheFile).use { fos ->
                bmp.compress(Bitmap.CompressFormat.PNG, 100, fos)
            }
            bmp.recycle()
            bmp = null

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
            Log.e(TAG, "PNG thumbnail save failed", e)
            Result.failure(e)
        } finally {
            runCatching {
                if (bmp != null && !bmp.isRecycled) bmp.recycle()
            }
        }
    }

    suspend fun exportMp4(
        context: Context,
        project: ReelProject,
        shotAudioMap: Map<String, ByteArray>,
        isMuted: Boolean,
        showCaptions: Boolean,
        showSpeechBubbles: Boolean,
        customBgmPcm: ByteArray? = null,
        onProgress: (Float) -> Unit
    ): Result<ExportResult> = withContext(Dispatchers.IO) {
        val width = project.exportResolution.width
        val height = project.exportResolution.height
        val totalDurationMs = project.totalDurationMs
        val totalFrames = ((totalDurationMs * FPS) / 1000L).toInt().coerceAtLeast(FPS)

        val exportsDir = File(context.cacheDir, "exports").apply { mkdirs() }
        val fileName = "InkReel_${System.currentTimeMillis()}.mp4"
        val outputFile = File(exportsDir, fileName)

        // Pre-mix full timeline PCM (24kHz 16-bit mono)
        val mixedPcm = ProceduralAudioEngine.mixReelAudio(project, shotAudioMap, isMuted, customBgmPcm)
        // Pre-compute per-frame lip sync amplitude map at 30fps
        val lipMap = buildTimelineLipEnvelope(project, shotAudioMap, totalFrames)

        var videoEncoder: MediaCodec? = null
        var audioEncoder: MediaCodec? = null
        var inputSurface: Surface? = null
        var muxer: MediaMuxer? = null
        var muxerStarted = false

        try {
            val videoFormat = MediaFormat.createVideoFormat(VIDEO_MIME, width, height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, if (width >= 1080) 4_200_000 else 2_500_000)
                setInteger(MediaFormat.KEY_FRAME_RATE, FPS)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
                }
            }
            val activeVideoEncoder = MediaCodec.createEncoderByType(VIDEO_MIME).apply {
                configure(videoFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            }
            videoEncoder = activeVideoEncoder
            val activeSurface = activeVideoEncoder.createInputSurface()
            inputSurface = activeSurface
            activeVideoEncoder.start()

            val audioFormat = MediaFormat.createAudioFormat(
                AUDIO_MIME,
                ProceduralAudioEngine.SAMPLE_RATE,
                1
            ).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, 128_000)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
            }
            val activeAudioEncoder = MediaCodec.createEncoderByType(AUDIO_MIME).apply {
                configure(audioFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                start()
            }
            audioEncoder = activeAudioEncoder

            val activeMuxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxer = activeMuxer
            var videoTrackIndex = -1
            var audioTrackIndex = -1
            var videoEosSeen = false
            var audioEosSeen = false

            val videoBufferInfo = MediaCodec.BufferInfo()
            val audioBufferInfo = MediaCodec.BufferInfo()
            val fixedVideoInfo = MediaCodec.BufferInfo()
            val fixedAudioInfo = MediaCodec.BufferInfo()
            var encodedVideoFrames = 0L
            var lastAudioPtsUs = -1L
            val pendingPackets = ArrayDeque<PendingPacket>()

            fun flushPendingPackets() {
                while (pendingPackets.isNotEmpty() && muxerStarted) {
                    val pkt = pendingPackets.removeFirst()
                    val directBuf = java.nio.ByteBuffer.wrap(pkt.bytes)
                    if (pkt.isVideo) {
                        val ptsUs = (encodedVideoFrames * 1_000_000L) / FPS
                        fixedVideoInfo.set(0, pkt.bytes.size, ptsUs, pkt.flags)
                        activeMuxer.writeSampleData(videoTrackIndex, directBuf, fixedVideoInfo)
                        encodedVideoFrames++
                    } else {
                        val safePtsUs = if (pkt.ptsUs > lastAudioPtsUs) pkt.ptsUs else (lastAudioPtsUs + 1L)
                        lastAudioPtsUs = safePtsUs
                        fixedAudioInfo.set(0, pkt.bytes.size, safePtsUs, pkt.flags)
                        activeMuxer.writeSampleData(audioTrackIndex, directBuf, fixedAudioInfo)
                    }
                }
            }

            fun drainAvailable(encoder: MediaCodec, info: MediaCodec.BufferInfo, isVideo: Boolean, timeoutUs: Long) {
                var iterations = 0
                while (iterations < 64) {
                    iterations++
                    val outIdx = encoder.dequeueOutputBuffer(info, timeoutUs)
                    when {
                        outIdx == MediaCodec.INFO_TRY_AGAIN_LATER -> break
                        outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            if (isVideo && videoTrackIndex < 0) {
                                videoTrackIndex = activeMuxer.addTrack(encoder.outputFormat)
                            } else if (!isVideo && audioTrackIndex < 0) {
                                audioTrackIndex = activeMuxer.addTrack(encoder.outputFormat)
                            }
                            if (videoTrackIndex >= 0 && audioTrackIndex >= 0 && !muxerStarted) {
                                activeMuxer.start()
                                muxerStarted = true
                                flushPendingPackets()
                            }
                        }
                        outIdx >= 0 -> {
                            val encodedBuf = encoder.getOutputBuffer(outIdx)
                            if (encodedBuf != null && (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0 && info.size > 0) {
                                encodedBuf.position(info.offset)
                                encodedBuf.limit(info.offset + info.size)
                                if (muxerStarted) {
                                    if (isVideo) {
                                        val ptsUs = (encodedVideoFrames * 1_000_000L) / FPS
                                        fixedVideoInfo.set(info.offset, info.size, ptsUs, info.flags)
                                        activeMuxer.writeSampleData(videoTrackIndex, encodedBuf, fixedVideoInfo)
                                        encodedVideoFrames++
                                    } else {
                                        val safePtsUs = if (info.presentationTimeUs > lastAudioPtsUs) info.presentationTimeUs else (lastAudioPtsUs + 1L)
                                        lastAudioPtsUs = safePtsUs
                                        fixedAudioInfo.set(info.offset, info.size, safePtsUs, info.flags)
                                        activeMuxer.writeSampleData(audioTrackIndex, encodedBuf, fixedAudioInfo)
                                    }
                                } else {
                                    val copy = ByteArray(info.size)
                                    encodedBuf.get(copy)
                                    pendingPackets.addLast(PendingPacket(isVideo, copy, info.presentationTimeUs, info.flags))
                                }
                            }
                            encoder.releaseOutputBuffer(outIdx, false)
                            if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                                if (isVideo) videoEosSeen = true else audioEosSeen = true
                                break
                            }
                        }
                    }
                }
            }

            var audioBytePos = 0
            var audioSamplesFed = 0L
            var audioEosSent = false
            val audioChunkSize = 4096

            fun stepAudioFeed(maxChunks: Int = 4, waitUs: Long = 0L) {
                var fed = 0
                while (!audioEosSent && fed < maxChunks) {
                    val inIdx = activeAudioEncoder.dequeueInputBuffer(waitUs)
                    if (inIdx < 0) break
                    val buf = activeAudioEncoder.getInputBuffer(inIdx) ?: break
                    buf.clear()
                    val ptsUs = (audioSamplesFed * 1_000_000L) / ProceduralAudioEngine.SAMPLE_RATE
                    if (audioBytePos >= mixedPcm.size) {
                        activeAudioEncoder.queueInputBuffer(inIdx, 0, 0, ptsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        audioEosSent = true
                    } else {
                        val toWrite = minOf(audioChunkSize, buf.capacity(), mixedPcm.size - audioBytePos)
                        buf.put(mixedPcm, audioBytePos, toWrite)
                        audioBytePos += toWrite
                        audioSamplesFed += (toWrite / 2)
                        activeAudioEncoder.queueInputBuffer(inIdx, 0, toWrite, ptsUs, 0)
                    }
                    fed++
                }
            }

            // Pre-warm audio encoder format so muxer starts immediately when frame 0 arrives
            for (warm in 0 until 40) {
                stepAudioFeed(maxChunks = 2, waitUs = 3_000L)
                drainAvailable(activeAudioEncoder, audioBufferInfo, isVideo = false, timeoutUs = 3_000L)
                if (audioTrackIndex >= 0) break
            }

            var hardwareCanvasSupported = true

            // Render directly onto encoder InputSurface via GPU-accelerated lockHardwareCanvas (with safe fallback)
            for (frame in 0 until totalFrames) {
                val playheadMs = (frame * 1000L) / FPS
                val surfCanvas = if (hardwareCanvasSupported) {
                    try {
                        activeSurface.lockHardwareCanvas()
                    } catch (_: Throwable) {
                        hardwareCanvasSupported = false
                        activeSurface.lockCanvas(null)
                    }
                } else {
                    activeSurface.lockCanvas(null)
                }
                try {
                    StickmanRenderer.renderFrame(
                        canvas = surfCanvas,
                        width = width.toFloat(),
                        height = height.toFloat(),
                        project = project,
                        playheadMs = playheadMs,
                        lipAmplitude = lipMap.getOrElse(frame) { 0f },
                        showCaptions = showCaptions,
                        showSpeechBubbles = showSpeechBubbles
                    )
                } finally {
                    activeSurface.unlockCanvasAndPost(surfCanvas)
                }

                stepAudioFeed(maxChunks = 4, waitUs = 0L)
                if (!videoEosSeen) {
                    drainAvailable(activeVideoEncoder, videoBufferInfo, isVideo = true, timeoutUs = 0L)
                }
                if (!audioEosSeen) {
                    drainAvailable(activeAudioEncoder, audioBufferInfo, isVideo = false, timeoutUs = 0L)
                }
                if (frame % 10 == 0) {
                    onProgress((frame.toFloat() / totalFrames.toFloat() * 0.92f).coerceIn(0.02f, 0.92f))
                }
            }

            onProgress(0.95f)
            activeVideoEncoder.signalEndOfInputStream()

            // Bounded concurrent flush so EOS completes cleanly
            val flushDeadlineMs = System.currentTimeMillis() + 8_000L
            while ((!videoEosSeen || !audioEosSeen) && System.currentTimeMillis() < flushDeadlineMs) {
                if (!audioEosSent) {
                    stepAudioFeed(maxChunks = 8, waitUs = 1_000L)
                }
                if (!videoEosSeen) {
                    drainAvailable(activeVideoEncoder, videoBufferInfo, isVideo = true, timeoutUs = 2_000L)
                }
                if (!audioEosSeen) {
                    drainAvailable(activeAudioEncoder, audioBufferInfo, isVideo = false, timeoutUs = 2_000L)
                }
            }
            onProgress(0.98f)

            // Stop & finalize muxer cleanly BEFORE publishing to Downloads so the MP4 moov atom is flushed
            runCatching { activeVideoEncoder.stop() }
            runCatching { activeVideoEncoder.release() }
            videoEncoder = null

            runCatching { activeSurface.release() }
            inputSurface = null

            runCatching { activeAudioEncoder.stop() }
            runCatching { activeAudioEncoder.release() }
            audioEncoder = null

            if (muxerStarted) {
                activeMuxer.stop()
                muxerStarted = false
            }
            activeMuxer.release()
            muxer = null
            onProgress(1f)

            // Save a copy into Android Downloads (API 29+) with clean IS_PENDING handling
            val displayPath = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                    put(MediaStore.Downloads.MIME_TYPE, "video/mp4")
                    put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/InkReel Studio")
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val mediaUri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                if (mediaUri != null) {
                    try {
                        val os = context.contentResolver.openOutputStream(mediaUri)
                            ?: throw IllegalStateException("Unable to open Downloads output stream.")
                        os.use { out ->
                            outputFile.inputStream().use { it.copyTo(out) }
                        }
                        values.clear()
                        values.put(MediaStore.Downloads.IS_PENDING, 0)
                        context.contentResolver.update(mediaUri, values, null, null)
                        "Downloads/InkReel Studio/$fileName"
                    } catch (e: Exception) {
                        runCatching { context.contentResolver.delete(mediaUri, null, null) }
                        throw e
                    }
                } else {
                    outputFile.absolutePath
                }
            } else {
                outputFile.absolutePath
            }

            val shareUri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                outputFile
            )
            postExportNotificationIfPermitted(context, project.title, displayPath, shareUri)
            Result.success(ExportResult(shareUri, displayPath, outputFile.length()))
        } catch (e: Exception) {
            Log.e(TAG, "MP4 export failed", e)
            runCatching { if (outputFile.exists()) outputFile.delete() }
            Result.failure(e)
        } finally {
            runCatching { videoEncoder?.stop() }
            runCatching { videoEncoder?.release() }
            runCatching { inputSurface?.release() }
            runCatching { audioEncoder?.stop() }
            runCatching { audioEncoder?.release() }
            runCatching { if (muxerStarted) muxer?.stop() }
            runCatching { muxer?.release() }
        }
    }

    private fun postExportNotificationIfPermitted(
        context: Context,
        title: String,
        displayPath: String,
        shareUri: Uri
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) return
        }
        runCatching {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    EXPORT_CHANNEL_ID,
                    "Video Exports",
                    NotificationManager.IMPORTANCE_DEFAULT
                ).apply {
                    description = "Notifications when InkReel MP4 exports finish"
                }
                manager.createNotificationChannel(channel)
            }
            val viewIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(shareUri, "video/mp4")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val pendingIntent = PendingIntent.getActivity(
                context,
                1001,
                viewIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val notification = NotificationCompat.Builder(context, EXPORT_CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("MP4 Export Complete")
                .setContentText("\"$title\" saved to $displayPath")
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)
                .build()
            manager.notify((System.currentTimeMillis() and 0x7FFF).toInt(), notification)
        }
    }

    private fun buildTimelineLipEnvelope(
        project: ReelProject,
        shotAudioMap: Map<String, ByteArray>,
        totalFrames: Int
    ): FloatArray {
        val env = FloatArray(totalFrames)
        var cursorFrame = 0
        for (shot in project.shots) {
            val shotFrames = ((shot.durationMs * FPS) / 1000L).toInt().coerceAtLeast(1)
            val pcm = shotAudioMap[shot.shotId]
            if (pcm != null && pcm.size >= 2 && shot.speaker != SpeakerSlot.SILENT) {
                val speed = project.characterFor(shot.speaker)?.voiceSpeed ?: 1.0f
                val samplesPerFrame = ((ProceduralAudioEngine.SAMPLE_RATE / FPS) * speed).toInt().coerceAtLeast(1)
                val totalSamples = pcm.size / 2
                for (f in 0 until shotFrames) {
                    val dst = cursorFrame + f
                    if (dst >= totalFrames) break
                    val startS = f * samplesPerFrame
                    val endS = (startS + samplesPerFrame).coerceAtMost(totalSamples)
                    if (startS < endS) {
                        var sumSq = 0.0
                        for (i in startS until endS) {
                            val lo = pcm[i * 2].toInt() and 0xFF
                            val hi = pcm[i * 2 + 1].toInt()
                            val v = ((hi shl 8) or lo).toShort().toFloat() / 32768f
                            sumSq += v * v
                        }
                        env[dst] = (sqrt(sumSq / (endS - startS)).toFloat() * 4.2f).coerceIn(0f, 1f)
                    }
                }
            }
            cursorFrame += shotFrames
        }
        return env
    }
}
