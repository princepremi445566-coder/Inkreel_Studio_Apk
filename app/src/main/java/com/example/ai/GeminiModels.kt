package com.example.ai

import org.json.JSONObject
import java.net.URLEncoder

/**
 * Single source of truth for all Gemini API model identifiers, endpoints,
 * and dynamic ListModels fallback resolution.
 */
object GeminiModels {
    const val BASE_URL_V1_BETA = "https://generativelanguage.googleapis.com/v1beta"

    const val PRIMARY_TEXT_MODEL = "gemini-3.5-flash-lite"
    const val PRIMARY_VISION_MODEL = "gemini-3.5-flash-lite"
    const val PRIMARY_TTS_MODEL = "gemini-3.8-flash-tts"

    val TEXT_MODEL_FALLBACKS: List<String> = listOf(
        PRIMARY_TEXT_MODEL,
        "gemini-3.5-flash",
        "gemini-3.1-flash-lite-preview",
        "gemini-2.5-flash"
    )

    val VISION_MODEL_FALLBACKS: List<String> = listOf(
        PRIMARY_VISION_MODEL,
        "gemini-3.5-flash",
        "gemini-2.5-flash"
    )

    val TTS_MODEL_FALLBACKS: List<String> = listOf(
        PRIMARY_TTS_MODEL,
        "gemini-3.1-flash-tts",
        "gemini-2.5-flash-preview-tts"
    )

    private val RETIRED_MODELS = setOf(
        "gemini-2.5-flash-lite",
        "gemini-2.0-flash-lite",
        "gemini-1.5-flash",
        "gemini-1.5-flash-8b",
        "gemini-1.5-pro"
    )

    enum class ModelKind {
        TEXT,
        VISION,
        TTS
    }

    @Volatile
    private var cachedTextModel: String? = null

    @Volatile
    private var cachedVisionModel: String? = null

    @Volatile
    private var cachedTtsModel: String? = null

    fun generateContentUrl(model: String, apiKey: String): String {
        val cleanModel = model.removePrefix("models/").trim()
        val encodedKey = URLEncoder.encode(apiKey.trim(), "UTF-8")
        return "$BASE_URL_V1_BETA/models/$cleanModel:generateContent?key=$encodedKey"
    }

    fun listModelsUrl(apiKey: String): String {
        val encodedKey = URLEncoder.encode(apiKey.trim(), "UTF-8")
        return "$BASE_URL_V1_BETA/models?key=$encodedKey&pageSize=100"
    }

    fun candidatesFor(kind: ModelKind): List<String> {
        val cached = when (kind) {
            ModelKind.TEXT -> cachedTextModel
            ModelKind.VISION -> cachedVisionModel
            ModelKind.TTS -> cachedTtsModel
        }
        val base = when (kind) {
            ModelKind.TEXT -> TEXT_MODEL_FALLBACKS
            ModelKind.VISION -> VISION_MODEL_FALLBACKS
            ModelKind.TTS -> TTS_MODEL_FALLBACKS
        }
        return buildList {
            if (!cached.isNullOrBlank()) add(cached)
            addAll(base)
        }.distinct()
    }

    fun cacheModel(kind: ModelKind, model: String) {
        val clean = model.removePrefix("models/").trim()
        if (clean.isBlank()) return
        when (kind) {
            ModelKind.TEXT -> cachedTextModel = clean
            ModelKind.VISION -> cachedVisionModel = clean
            ModelKind.TTS -> cachedTtsModel = clean
        }
    }

    fun clearCachedModels() {
        cachedTextModel = null
        cachedVisionModel = null
        cachedTtsModel = null
    }

    fun isModelNotFoundError(e: Throwable): Boolean {
        if (e is GeminiHttpException && e.statusCode == 404) return true
        val msg = e.message.orEmpty()
        return msg.contains("HTTP 404", ignoreCase = true) ||
            msg.contains("not found", ignoreCase = true) ||
            msg.contains("no longer available", ignoreCase = true) ||
            msg.contains("is not supported for generateContent", ignoreCase = true)
    }

    fun pickBestModelFromListModels(
        listModelsJson: JSONObject,
        kind: ModelKind,
        excludeModels: Set<String> = emptySet()
    ): String? {
        val arr = listModelsJson.optJSONArray("models") ?: return null
        val normalizedExcluded = excludeModels.map { it.removePrefix("models/").lowercase() }.toSet()

        data class Candidate(
            val id: String,
            val major: Int,
            val minor: Int,
            val tierScore: Int,
            val stabilityScore: Int
        )

        val candidates = mutableListOf<Candidate>()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val rawName = obj.optString("name", "").removePrefix("models/").trim()
            if (rawName.isBlank()) continue
            val lower = rawName.lowercase()
            if (lower in normalizedExcluded) continue
            if (lower in RETIRED_MODELS) continue
            if (!lower.startsWith("gemini-")) continue

            val methods = obj.optJSONArray("supportedGenerationMethods")
            var supportsGenerateContent = false
            if (methods != null) {
                for (m in 0 until methods.length()) {
                    if (methods.optString(m) == "generateContent") {
                        supportsGenerateContent = true
                        break
                    }
                }
            }
            if (!supportsGenerateContent) continue

            val isTtsModel = lower.contains("tts")
            when (kind) {
                ModelKind.TTS -> {
                    if (!isTtsModel) continue
                }
                ModelKind.TEXT, ModelKind.VISION -> {
                    if (isTtsModel ||
                        lower.contains("embedding") ||
                        lower.contains("imagen") ||
                        lower.contains("veo") ||
                        lower.contains("aqa") ||
                        lower.contains("robotics") ||
                        lower.contains("native-audio") ||
                        lower.contains("-live")
                    ) {
                        continue
                    }
                }
            }

            val verMatch = Regex("gemini-(\\d+)(?:\\.(\\d+))?").find(lower)
            val major = verMatch?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
            val minor = verMatch?.groupValues?.getOrNull(2)?.toIntOrNull() ?: 0

            val tierScore = when {
                kind == ModelKind.TTS && lower.contains("flash-tts") -> 3
                kind == ModelKind.TTS && lower.contains("flash") -> 2
                kind != ModelKind.TTS && lower.contains("flash-lite") -> 3
                kind != ModelKind.TTS && lower.contains("flash") -> 2
                else -> 1
            }

            val stabilityScore = when {
                !lower.contains("preview") && !lower.contains("exp") -> 3
                lower.contains("preview") -> 2
                else -> 1
            }

            candidates.add(Candidate(rawName, major, minor, tierScore, stabilityScore))
        }

        return candidates.maxWithOrNull(
            compareBy<Candidate>(
                { it.major },
                { it.minor },
                { it.tierScore },
                { it.stabilityScore }
            )
        )?.id
    }
}
