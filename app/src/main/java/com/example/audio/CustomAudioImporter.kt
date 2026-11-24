package com.example.audio

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.nio.ByteOrder
import kotlin.math.roundToInt

data class ImportedAudioTrack(
    val displayName: String,
    val pcm24kMono: ByteArray
)

object CustomAudioImporter {

    private const val TARGET_RATE = ProceduralAudioEngine.SAMPLE_RATE
    private const val MAX_BGM_SECONDS = 90

    suspend fun importAs24kMonoPcm(context: Context, uri: Uri): Result<ImportedAudioTrack> =
        withContext(Dispatchers.IO) {
            var extractor: MediaExtractor? = null
            var codec: MediaCodec? = null
            try {
                val label = resolveDisplayName(context, uri)
                val ex = MediaExtractor()
                extractor = ex
                ex.setDataSource(context, uri, null)

                var audioTrackIndex = -1
                var inputFormat: MediaFormat? = null
                for (i in 0 until ex.trackCount) {
                    val fmt = ex.getTrackFormat(i)
                    val mime = fmt.getString(MediaFormat.KEY_MIME).orEmpty()
                    if (mime.startsWith("audio/")) {
                        audioTrackIndex = i
                        inputFormat = fmt
                        break
                    }
                }
                if (audioTrackIndex < 0 || inputFormat == null) {
                    return@withContext Result.failure(IllegalArgumentException("No audio track found in selected file."))
                }
                ex.selectTrack(audioTrackIndex)

                val mime = inputFormat.getString(MediaFormat.KEY_MIME)
                    ?: return@withContext Result.failure(IllegalStateException("Missing audio MIME type."))
                var srcSampleRate = inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE).coerceAtLeast(8000)
                var srcChannels = if (inputFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                    inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
                } else 1

                val dec = MediaCodec.createDecoderByType(mime)
                codec = dec
                dec.configure(inputFormat, null, null, 0)
                dec.start()

                val rawMonoFloats = mutableListOf<Float>()
                val maxSrcSamples = MAX_BGM_SECONDS * srcSampleRate
                val info = MediaCodec.BufferInfo()
                var sawInputEos = false
                var sawOutputEos = false

                while (!sawOutputEos && rawMonoFloats.size < maxSrcSamples) {
                    if (!sawInputEos) {
                        val inIdx = dec.dequeueInputBuffer(10_000L)
                        if (inIdx >= 0) {
                            val inBuf = dec.getInputBuffer(inIdx)
                            if (inBuf != null) {
                                val sampleSize = ex.readSampleData(inBuf, 0)
                                if (sampleSize < 0) {
                                    dec.queueInputBuffer(inIdx, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                    sawInputEos = true
                                } else {
                                    val pts = ex.sampleTime.coerceAtLeast(0L)
                                    dec.queueInputBuffer(inIdx, 0, sampleSize, pts, 0)
                                    ex.advance()
                                }
                            }
                        }
                    }

                    val outIdx = dec.dequeueOutputBuffer(info, 10_000L)
                    when {
                        outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val outFmt = dec.outputFormat
                            if (outFmt.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                                srcSampleRate = outFmt.getInteger(MediaFormat.KEY_SAMPLE_RATE).coerceAtLeast(8000)
                            }
                            if (outFmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                                srcChannels = outFmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
                            }
                        }
                        outIdx >= 0 -> {
                            val outBuf = dec.getOutputBuffer(outIdx)
                            if (outBuf != null && info.size > 0) {
                                outBuf.position(info.offset)
                                outBuf.limit(info.offset + info.size)
                                val shortBuf = outBuf.slice().order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                                val shortCount = shortBuf.remaining()
                                val frames = shortCount / srcChannels
                                for (f in 0 until frames) {
                                    if (rawMonoFloats.size >= maxSrcSamples) break
                                    var sum = 0f
                                    for (c in 0 until srcChannels) {
                                        sum += shortBuf.get().toFloat() / 32768f
                                    }
                                    rawMonoFloats.add(sum / srcChannels)
                                }
                            }
                            dec.releaseOutputBuffer(outIdx, false)
                            if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                                sawOutputEos = true
                            }
                        }
                    }
                }

                if (rawMonoFloats.size < 2000) {
                    return@withContext Result.failure(IllegalStateException("Selected audio file was empty or unreadable."))
                }

                // Linear-interpolation resample to 24kHz mono 16-bit PCM
                val ratio = srcSampleRate.toDouble() / TARGET_RATE.toDouble()
                val dstCount = ((rawMonoFloats.size / ratio).roundToInt()).coerceIn(TARGET_RATE / 2, TARGET_RATE * MAX_BGM_SECONDS)
                val outBytes = ByteArray(dstCount * 2)
                for (i in 0 until dstCount) {
                    val srcPos = i * ratio
                    val idx0 = srcPos.toInt().coerceIn(0, rawMonoFloats.lastIndex)
                    val idx1 = (idx0 + 1).coerceAtMost(rawMonoFloats.lastIndex)
                    val frac = (srcPos - idx0).toFloat()
                    val v = (rawMonoFloats[idx0] + (rawMonoFloats[idx1] - rawMonoFloats[idx0]) * frac).coerceIn(-1f, 1f)
                    val pcm = (v * 32767f).toInt().coerceIn(-32768, 32767)
                    outBytes[i * 2] = (pcm and 0xFF).toByte()
                    outBytes[i * 2 + 1] = ((pcm shr 8) and 0xFF).toByte()
                }

                Result.success(ImportedAudioTrack(displayName = label, pcm24kMono = outBytes))
            } catch (e: Exception) {
                Result.failure(IllegalStateException(e.localizedMessage ?: "Failed to import audio track.", e))
            } finally {
                runCatching { codec?.stop() }
                runCatching { codec?.release() }
                runCatching { extractor?.release() }
            }
        }

    private fun resolveDisplayName(context: Context, uri: Uri): String {
        runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) {
                        val name = cursor.getString(idx)
                        if (!name.isNullOrBlank()) return name.take(40)
                    }
                }
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/')?.take(40)?.ifBlank { "Custom Track" } ?: "Custom Track"
    }
}
