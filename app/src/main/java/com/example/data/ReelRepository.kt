package com.example.data

import android.content.Context
import android.util.Log
import com.example.BuildConfig
import com.example.model.CastPreset
import com.example.model.CharacterProfile
import com.example.model.ReelProject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import kotlin.math.sqrt

class ReelRepository(context: Context) {

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("inkreel_prefs", Context.MODE_PRIVATE)
    private val projectsDir = File(appContext.filesDir, "inkreel_projects").apply { mkdirs() }
    private val audioRootDir = File(appContext.filesDir, "inkreel_audio").apply { mkdirs() }
    private val castPresetsFile = File(appContext.filesDir, "inkreel_cast_presets.json")
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val buildConfigKey: String
        get() = BuildConfig.GEMINI_API_KEY
            .trim()
            .takeIf { it.isNotBlank() && it != "MY_GEMINI_API_KEY" && it != "your_api_key_here" }
            .orEmpty()

    private fun resolveEffectiveApiKey(): String {
        val userKey = prefs.getString(KEY_GEMINI_API, "").orEmpty().trim()
        return userKey.ifBlank { buildConfigKey }
    }

    private val _apiKeyFlow = MutableStateFlow(resolveEffectiveApiKey())
    val apiKeyFlow: StateFlow<String> = _apiKeyFlow.asStateFlow()

    private val _watermarkFlow = MutableStateFlow(
        prefs.getString(KEY_WATERMARK_USERNAME, "@inkreel.studio") ?: "@inkreel.studio"
    )
    val watermarkFlow: StateFlow<String> = _watermarkFlow.asStateFlow()

    private val _savedReels = MutableStateFlow<List<ReelProject>>(emptyList())
    val savedReels: StateFlow<List<ReelProject>> = _savedReels.asStateFlow()

    private val _castPresets = MutableStateFlow<List<CastPreset>>(emptyList())
    val castPresets: StateFlow<List<CastPreset>> = _castPresets.asStateFlow()

    init {
        ioScope.launch {
            _savedReels.value = loadAllProjectsSync()
            _castPresets.value = loadCastPresetsSync()
        }
    }

    fun hasBuildConfigApiKey(): Boolean = buildConfigKey.isNotBlank()

    fun saveApiKey(rawKey: String) {
        val cleaned = rawKey.trim()
        prefs.edit().putString(KEY_GEMINI_API, cleaned).apply()
        _apiKeyFlow.value = resolveEffectiveApiKey()
    }

    fun clearApiKey() {
        prefs.edit().remove(KEY_GEMINI_API).apply()
        _apiKeyFlow.value = resolveEffectiveApiKey()
    }

    fun getApiKey(): String = _apiKeyFlow.value

    fun saveWatermarkHandle(rawHandle: String) {
        val cleaned = rawHandle.take(36)
        prefs.edit().putString(KEY_WATERMARK_USERNAME, cleaned).apply()
        _watermarkFlow.value = cleaned
    }

    fun getWatermarkHandle(): String = _watermarkFlow.value

    private fun loadAllProjectsSync(): List<ReelProject> {
        val files = projectsDir.listFiles { f -> f.extension == "json" } ?: return emptyList()
        return files.mapNotNull { f ->
            runCatching {
                ReelProject.fromJson(JSONObject(f.readText()))
            }.getOrNull()
        }.sortedByDescending { it.updatedAtMs }
    }

    suspend fun refreshSavedReels() = withContext(Dispatchers.IO) {
        _savedReels.value = loadAllProjectsSync()
    }

    suspend fun saveReel(project: ReelProject): Result<String> = withContext(Dispatchers.IO) {
        try {
            val now = System.currentTimeMillis()
            val resolvedId = project.id.ifBlank { "reel_${UUID.randomUUID().toString().take(8)}" }
            val toSave = project.copy(
                id = resolvedId,
                createdAtMs = if (project.id.isBlank()) now else project.createdAtMs,
                updatedAtMs = now
            )
            File(projectsDir, "$resolvedId.json").writeText(toSave.toJson().toString())
            _savedReels.value = loadAllProjectsSync()
            Result.success(resolvedId)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save reel", e)
            Result.failure(e)
        }
    }

    suspend fun renameReel(reelId: String, newTitle: String): Result<ReelProject> = withContext(Dispatchers.IO) {
        try {
            val cleaned = newTitle.trim().take(120)
            if (cleaned.isBlank()) {
                return@withContext Result.failure(IllegalArgumentException("Reel title cannot be empty."))
            }
            val file = File(projectsDir, "$reelId.json")
            if (!file.exists()) {
                return@withContext Result.failure(IllegalStateException("Saved reel not found on disk."))
            }
            val existing = ReelProject.fromJson(JSONObject(file.readText()))
            val updated = existing.copy(
                title = cleaned,
                updatedAtMs = System.currentTimeMillis()
            )
            file.writeText(updated.toJson().toString())
            _savedReels.value = loadAllProjectsSync()
            Result.success(updated)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to rename reel", e)
            Result.failure(e)
        }
    }

    suspend fun duplicateReel(project: ReelProject): Result<String> {
        val copy = project.copy(
            id = "",
            title = "${project.title.take(105)} (Copy)",
            createdAtMs = System.currentTimeMillis(),
            updatedAtMs = System.currentTimeMillis()
        )
        return saveReel(copy)
    }

    suspend fun deleteReel(reelId: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val f = File(projectsDir, "$reelId.json")
            if (f.exists()) f.delete()
            val safeProj = reelId.replace(Regex("[^a-zA-Z0-9_-]"), "_")
            if (safeProj.isNotBlank()) {
                File(audioRootDir, safeProj).deleteRecursively()
            }
            _savedReels.value = loadAllProjectsSync()
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete reel", e)
            Result.failure(e)
        }
    }

    // --- Reusable Saved Cast Presets ---

    private fun loadCastPresetsSync(): List<CastPreset> {
        if (!castPresetsFile.exists()) return emptyList()
        return runCatching {
            val arr = JSONArray(castPresetsFile.readText())
            (0 until arr.length()).map { CastPreset.fromJson(arr.getJSONObject(it)) }
        }.getOrDefault(emptyList())
    }

    suspend fun saveCastPreset(profile: CharacterProfile): Result<CastPreset> = withContext(Dispatchers.IO) {
        try {
            val cleanName = profile.name.trim().ifBlank { "Character" }
            val cleanRole = profile.role.trim().ifBlank { profile.gender.label }
            val preset = CastPreset(
                presetLabel = "$cleanName ($cleanRole)",
                profile = profile
            )
            val current = loadCastPresetsSync()
                .filterNot { it.presetLabel.equals(preset.presetLabel, ignoreCase = true) }
            val updated = (listOf(preset) + current).take(24)
            val arr = JSONArray().apply { updated.forEach { put(it.toJson()) } }
            castPresetsFile.writeText(arr.toString())
            _castPresets.value = updated
            Result.success(preset)
        } catch (e: Exception) {
            Log.e(TAG, "Failed saving cast preset", e)
            Result.failure(e)
        }
    }

    suspend fun deleteCastPreset(presetId: String) = withContext(Dispatchers.IO) {
        runCatching {
            val updated = loadCastPresetsSync().filterNot { it.id == presetId }
            val arr = JSONArray().apply { updated.forEach { put(it.toJson()) } }
            castPresetsFile.writeText(arr.toString())
            _castPresets.value = updated
        }
        Unit
    }

    // --- Local 24kHz 16-bit Mono PCM Shot Audio Cache ---

    private fun shotAudioFile(projectKey: String, shotId: String): File {
        val safeProj = projectKey.replace(Regex("[^a-zA-Z0-9_-]"), "_").ifBlank { "draft" }
        val safeShot = shotId.replace(Regex("[^a-zA-Z0-9_-]"), "_").ifBlank { "shot" }
        val dir = File(audioRootDir, safeProj)
        if (!dir.exists()) dir.mkdirs()
        return File(dir, "$safeShot.pcm")
    }

    suspend fun saveShotAudioPcm(projectKey: String, shotId: String, pcmBytes: ByteArray): Result<Unit> =
        withContext(Dispatchers.IO) {
            try {
                shotAudioFile(projectKey, shotId).writeBytes(pcmBytes)
                Result.success(Unit)
            } catch (e: Exception) {
                Log.e(TAG, "Failed saving shot audio PCM", e)
                Result.failure(e)
            }
        }

    suspend fun loadShotAudioPcm(projectKey: String, shotId: String): ByteArray? =
        withContext(Dispatchers.IO) {
            runCatching {
                val f = shotAudioFile(projectKey, shotId)
                if (f.exists() && f.length() > 0L) f.readBytes() else null
            }.getOrNull()
        }

    private fun customBgmFile(projectKey: String): File {
        val safeProj = projectKey.replace(Regex("[^a-zA-Z0-9_-]"), "_").ifBlank { "draft" }
        val dir = File(audioRootDir, safeProj)
        if (!dir.exists()) dir.mkdirs()
        return File(dir, "_custom_bgm.pcm")
    }

    suspend fun saveCustomBgmPcm(projectKey: String, pcmBytes: ByteArray): Result<Unit> =
        withContext(Dispatchers.IO) {
            try {
                customBgmFile(projectKey).writeBytes(pcmBytes)
                Result.success(Unit)
            } catch (e: Exception) {
                Log.e(TAG, "Failed saving custom BGM PCM", e)
                Result.failure(e)
            }
        }

    suspend fun loadCustomBgmPcm(projectKey: String): ByteArray? =
        withContext(Dispatchers.IO) {
            runCatching {
                val f = customBgmFile(projectKey)
                if (f.exists() && f.length() > 0L) f.readBytes() else null
            }.getOrNull()
        }

    suspend fun deleteCustomBgmPcm(projectKey: String) = withContext(Dispatchers.IO) {
        runCatching {
            val f = customBgmFile(projectKey)
            if (f.exists()) f.delete()
        }
        Unit
    }

    fun computeEnvelope30Fps(pcmBytes: ByteArray, sampleRate: Int = 24000): FloatArray {
        val totalSamples = pcmBytes.size / 2
        if (totalSamples <= 0) return FloatArray(0)
        val samplesPerFrame = (sampleRate / 30).coerceAtLeast(1)
        val frameCount = (totalSamples + samplesPerFrame - 1) / samplesPerFrame
        val envelope = FloatArray(frameCount)
        var maxRms = 0.001f

        for (frame in 0 until frameCount) {
            val start = frame * samplesPerFrame
            val end = ((frame + 1) * samplesPerFrame).coerceAtMost(totalSamples)
            var sumSq = 0.0
            for (i in start until end) {
                val lo = pcmBytes[i * 2].toInt() and 0xFF
                val hi = pcmBytes[i * 2 + 1].toInt()
                val sample = ((hi shl 8) or lo).toShort().toFloat() / 32768f
                sumSq += (sample * sample)
            }
            val rms = sqrt(sumSq / (end - start).coerceAtLeast(1)).toFloat()
            envelope[frame] = rms
            if (rms > maxRms) maxRms = rms
        }

        val norm = maxRms.coerceAtLeast(0.08f)
        for (i in envelope.indices) {
            envelope[i] = (envelope[i] / norm).coerceIn(0f, 1f)
        }
        return envelope
    }

    companion object {
        private const val TAG = "ReelRepository"
        private const val KEY_GEMINI_API = "user_gemini_api_key"
        private const val KEY_WATERMARK_USERNAME = "user_watermark_handle"
    }
}
