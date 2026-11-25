package com.example.ai

import com.example.model.BgmMood
import com.example.model.CameraFraming
import com.example.model.CharacterActivity
import com.example.model.ClonedStyleBlueprint
import com.example.model.ComicSfx
import com.example.model.MemeSticker
import com.example.model.MoodPose
import com.example.model.ReelProject
import com.example.model.ReelShot
import com.example.model.RoomSetting
import com.example.model.ShotTransition
import com.example.model.SpeakerSlot
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import kotlin.random.Random

/**
 * Viral 2D Stickman Hinglish comedy script prompt builder (8-10 scenes, 25-35s,
 * 24-topic generator, 14-conflict generator, TITLE "3 GHANTE BAAD" time jump,
 * rolling 10-item history, strict JSON schema, and consecutive scene-diversity parser).
 */
object ComedyScriptGenerator {

    val TOPIC_POOL: List<String> = listOf(
        "Chai tapri",
        "Shaadi buffet",
        "Gym",
        "Exam hall",
        "Auto-rickshaw",
        "Hostel mess",
        "Mobile recharge",
        "Food delivery",
        "WhatsApp group",
        "Power cut",
        "Parking problem",
        "Salon",
        "Kirana shop",
        "Railway station",
        "College canteen",
        "Shopping mall",
        "Movie theatre",
        "Office",
        "Coaching centre",
        "Electricity bill",
        "Online shopping",
        "Neighbourhood",
        "Mobile repair shop",
        "Street food stall"
    )

    val CONFLICT_POOL: List<String> = listOf(
        "Freebie abuse",
        "Fake discount",
        "Rival shop",
        "Lazy employee",
        "Strict relative",
        "Jugaad gone wrong",
        "Greedy customer",
        "Over-smart friend",
        "Misunderstanding",
        "Unnecessary attitude",
        "Cheap trick",
        "Unexpected competition",
        "Wrong calculation",
        "Revenge plan backfires"
    )

    val ACTION_STYLES: List<String> = listOf(
        "Fight Comedy - Funny cartoon fight, dodging, dramatic reactions.",
        "Dance Comedy - Unexpected dance battle, funny moves.",
        "Chase Comedy - Characters chasing each other.",
        "Prank Comedy - One character tricks another.",
        "Detective Comedy - Funny investigation and clues.",
        "Horror Comedy - Spooky setup with a silly reveal.",
        "Race Comedy - Funny racing competition.",
        "Drama Comedy - Overacting and dramatic reactions.",
        "Magic Comedy - Unexpected magical-looking cartoon events.",
        "Sports Comedy - Ridiculous sports competition.",
        "Slow Motion Comedy - Dramatic slow-motion scenes.",
        "Disguise Comedy - Characters using funny disguises.",
        "Challenge Comedy - Impossible-looking funny challenges.",
        "Accident Comedy - Harmless cartoon mishaps.",
        "Musical Comedy - Funny singing and dancing.",
        "Stealth Comedy - Characters secretly doing something.",
        "Reaction Comedy - Exaggerated facial and body reactions.",
        "Competition Comedy - Two characters competing for something.",
        "Reverse Comedy - The victim unexpectedly becomes the winner.",
        "Domino Comedy - One silly action triggers multiple funny events."
    )

    private const val MAX_BANNED_HISTORY = 10

    data class BannedJokeEntry(
        val topic: String,
        val title: String,
        val punchline: String
    ) {
        fun formatSummary(): String {
            val cleanPunch = punchline.trim().take(60)
            return if (cleanPunch.isNotBlank()) {
                "$topic ($title — \"$cleanPunch\")"
            } else {
                "$topic ($title)"
            }
        }
    }

    data class ComedyPromptBundle(
        val seed: Int,
        val topic: String,
        val conflict: String,
        val actionStyle: String,
        val niche: String,
        val bannedTopics: List<String>,
        val charactersSpec: String,
        val systemPrompt: String,
        val userPrompt: String
    )

    private val historyLock = Any()
    private val bannedHistory = ArrayDeque<BannedJokeEntry>()
    private var lastTopicUsed: String? = null
    private var lastConflictIdx: Int = -1
    private var lastActionStyleIdx: Int = -1

    fun recordGenerated(topic: String, title: String, punchline: String) {
        val cleanTopic = topic.trim()
        val cleanTitle = title.trim()
        if (cleanTopic.isBlank() && cleanTitle.isBlank()) return
        synchronized(historyLock) {
            val effectiveTopic = cleanTopic.ifBlank { "general" }
            lastTopicUsed = effectiveTopic
            bannedHistory.addLast(
                BannedJokeEntry(
                    topic = effectiveTopic,
                    title = cleanTitle.ifBlank { "Untitled" },
                    punchline = punchline.trim()
                )
            )
            while (bannedHistory.size > MAX_BANNED_HISTORY) {
                bannedHistory.removeFirst()
            }
        }
    }

    fun getBannedSummaries(): List<String> = synchronized(historyLock) {
        bannedHistory.map { it.formatSummary() }
    }

    fun getBannedTopics(): Set<String> = synchronized(historyLock) {
        bannedHistory.map { it.topic.lowercase() }.toSet()
    }

    fun clearHistory() {
        synchronized(historyLock) {
            bannedHistory.clear()
            lastTopicUsed = null
            lastConflictIdx = -1
            lastActionStyleIdx = -1
        }
    }

    fun pickRandomConflict(random: Random = Random.Default): String {
        synchronized(historyLock) {
            val indices = CONFLICT_POOL.indices.filter { it != lastConflictIdx }
            val chosenIdx = if (indices.isNotEmpty()) {
                indices[random.nextInt(indices.size)]
            } else {
                random.nextInt(CONFLICT_POOL.size)
            }
            lastConflictIdx = chosenIdx
            return CONFLICT_POOL[chosenIdx]
        }
    }

    fun pickActionStyle(topic: String = "", random: Random = Random.Default): String {
        val lower = topic.lowercase()
        val preferredKeywords: List<String> = when {
            lower.contains("gym") || lower.contains("sports") -> listOf("Challenge", "Sports", "Slow Motion", "Competition", "Accident")
            lower.contains("power cut") || lower.contains("hostel") -> listOf("Horror", "Stealth", "Detective", "Prank", "Domino")
            lower.contains("shaadi") || lower.contains("mall") -> listOf("Dance", "Musical", "Stealth", "Race", "Disguise", "Competition")
            lower.contains("chai") || lower.contains("street") || lower.contains("kirana") -> listOf("Fight", "Competition", "Domino", "Reverse", "Magic", "Prank")
            lower.contains("auto") || lower.contains("parking") || lower.contains("delivery") || lower.contains("railway") -> listOf("Chase", "Race", "Accident", "Slow Motion", "Domino")
            lower.contains("exam") || lower.contains("coaching") || lower.contains("office") || lower.contains("canteen") -> listOf("Stealth", "Disguise", "Detective", "Reverse", "Drama", "Reaction")
            lower.contains("whatsapp") || lower.contains("recharge") || lower.contains("online") || lower.contains("repair") -> listOf("Drama", "Reaction", "Prank", "Detective", "Magic", "Reverse")
            else -> emptyList()
        }

        synchronized(historyLock) {
            val nonConsecutive = ACTION_STYLES.indices.filter { it != lastActionStyleIdx }
            val topicMatched = if (preferredKeywords.isNotEmpty()) {
                nonConsecutive.filter { idx ->
                    val entry = ACTION_STYLES[idx]
                    preferredKeywords.any { kw -> entry.contains(kw, ignoreCase = true) }
                }
            } else {
                emptyList()
            }
            val pool = if (topicMatched.isNotEmpty() && random.nextInt(10) < 7) {
                topicMatched
            } else {
                nonConsecutive
            }
            val chosenIdx = if (pool.isNotEmpty()) {
                pool[random.nextInt(pool.size)]
            } else {
                random.nextInt(ACTION_STYLES.size)
            }
            lastActionStyleIdx = chosenIdx
            return ACTION_STYLES[chosenIdx]
        }
    }

    fun pickTopic(requestedTopic: String = "random", random: Random = Random.Default): String {
        val clean = requestedTopic.trim()
        if (clean.isNotBlank() && !clean.equals("random", ignoreCase = true)) {
            return clean
        }
        val banned = getBannedTopics()
        val prev = synchronized(historyLock) { lastTopicUsed?.lowercase() }
        val available = TOPIC_POOL.filter {
            val lower = it.lowercase()
            lower !in banned && lower != prev
        }
        val pool = if (available.isNotEmpty()) {
            available
        } else {
            TOPIC_POOL.filter { it.lowercase() != prev }.ifEmpty { TOPIC_POOL }
        }
        return pool[random.nextInt(pool.size)]
    }

    fun buildPromptBundle(
        niche: String,
        twist: String,
        language: String,
        hook: String,
        clonedBlueprint: ClonedStyleBlueprint?,
        baseProject: ReelProject,
        requestedTopic: String = "random",
        random: Random = Random.Default
    ): ComedyPromptBundle {
        val seed = random.nextInt(100_000, 999_999)
        val topic = pickTopic(requestedTopic, random)
        val conflict = pickRandomConflict(random)
        val actionStyle = pickActionStyle(topic, random)
        val effectiveNiche = niche.trim().ifBlank { "general" }
        val banned = getBannedSummaries()

        val chars = baseProject.characters
        val charA = chars.getOrNull(0)
        val charB = chars.getOrNull(1)
        val charC = chars.getOrNull(2)
        val specA = if (charA != null) "${charA.name}/${charA.gender.name}" else "Aarav/MALE"
        val specB = if (charB != null) "${charB.name}/${charB.gender.name}" else "Priya/FEMALE"
        val specC = if (charC != null) "${charC.name}/${charC.gender.name}" else "none"
        val charactersSpec = "A = $specA, B = $specB, C = $specC"

        val roomsList = RoomSetting.entries.joinToString(", ") { it.name }
        val camerasList = CameraFraming.entries.joinToString(", ") { it.name }
        val activitiesList = CharacterActivity.entries.joinToString(", ") { it.name }
        val moodsList = MoodPose.entries.joinToString(", ") { it.name }

        val languageInstruction = when {
            language.equals("English", ignoreCase = true) ->
                "Roman English with natural Indian phrasing"
            language.equals("Hindi", ignoreCase = true) ->
                "Natural Roman Hindi/Hinglish"
            else ->
                "Roman Hinglish"
        }

        val systemPrompt = """
            You are a professional Hinglish comedy writer specializing in viral 2D Stickman Animation Reels for YouTube Shorts and Instagram Reels.

            1. VIDEO FORMAT
            - Aspect Ratio: 9:16 (Portrait)
            - Duration: 25-35 seconds
            - Animation Style: 2D Stickman Cartoon
            - Language: $languageInstruction
            - Characters: 2 main characters (A and B), optional third character (C) for the final twist.
            - Total Scenes: 8-10 lines. Each line represents exactly one scene.

            2. COMEDY STYLE
            Create comedy similar to viral Indian daily-life cartoon reels using relatable Indian situations, funny arguments and misunderstandings, clever jugaad and stupid decisions, unexpected consequences, overconfident characters, expressive stickman physical comedy, and unexpected punchlines.

            3. SCRIPT STRUCTURE (follow this exact storytelling structure)
            - Scene 1: STRONG HOOK — Start with a funny or shocking dialogue that immediately grabs attention.
            - Scene 2: PROBLEM — Introduce the conflict between characters A and B.
            - Scene 3: FUNNY ARGUMENT — Make the situation more entertaining with short dialogues.
            - Scene 4: CLEVER PLAN — One character creates an over-smart plan to solve the problem.
            - Scene 5: TIME JUMP — Show a separate title card with speaker "TITLE" and line "3 GHANTE BAAD" (camera TIME_SKIP_CARD).
            - Scene 6: UNEXPECTED RESULT — The plan produces a ridiculous result.
            - Scene 7: TWIST — The clever character gets trapped or the losing character wins accidentally (C can appear here for the twist if present).
            - Scene 8-10: FINAL PUNCHLINE — End with the funniest possible dialogue. The LAST dialogue must deliver an unexpected comedy punchline.

            4. DIALOGUE RULES
            - Every spoken line must contain maximum 12 words.
            - Use natural Indian Roman Hinglish. Short, easy to understand, no boring explanations, no unnecessary narration.
            - Every character must sound different (A = overconfident/dramatic, B = sarcastic/sharp, C = chaotic twist).
            - The final punchline must be stronger than the opening hook.
            - Never copy jokes from previous scripts or banned_topics.

            5. SCENE VISUALIZATION (use ONLY these exact enum names)
            Every scene must include: speaker (A, B, C, or TITLE), line, bubble (max 4 words; empty for TITLE), room, camera, activity, mood.
            - Change the room in consecutive scenes (never repeat the same room in consecutive scenes; use at least 4 distinct rooms).
            - Change the camera angle in consecutive scenes.
            - Change the activity in consecutive scenes.
            - Change the mood in consecutive scenes.
            - The TITLE scene MUST have speaker "TITLE", camera "TIME_SKIP_CARD", line "3 GHANTE BAAD".

            ALLOWED VALUES:
            rooms: $roomsList
            cameras: $camerasList
            activities: $activitiesList
            moods: $moodsList

            6. RANDOM SCENE ACTION & PRESENTATION GENERATOR (STRICT VARIETY RULES)
            Build the entire story and visual staging around the given primary action style:
            - Never use the same primary action style in consecutive videos.
            - Change characters' movements and body language; do NOT make the video a simple standing-and-talking conversation (use DANCING, GYM_WORKOUT, PLAYING_CRICKET, COOKING_CHAI, WALKING_IN, GAMING_PHONE, SLEEPING_SNORING, CLAPPING_CHEERING, CRYING_DRAMA, TAKING_SELFIE, DRIVING_RIDING, SWEEPING_CLEANING, STUDYING_WRITING across scenes).
            - Mix close-ups (CLOSE_UP_A/B/C), wide shots (WIDE_DOORWAY), dramatic angles (OVER_THE_SHOULDER, DESK_SLAM, PROP_DROP, SLAPSTICK_CHASE, BONK_ENDING).
            - The final scene must have a visually funny payoff along with the punchline.
            - Keep all physical comedy harmless and cartoonish.

            7. COMEDY QUALITY CONTROL & CONTENT RULES
            Before returning JSON, verify: opening hook grabs attention, relatable situation, funny plan, proper "3 GHANTE BAAD" time jump card, ridiculous result, winner/loser flip, hilarious last punchline with visual payoff, all spoken dialogues <= 12 words, consecutive visuals always differ, family-friendly (no abuse, politics, religion, or offensive stereotypes).

            8. OUTPUT FORMAT
            Return ONLY valid JSON. No introduction, explanation or Markdown:
            {
              "title": "Funny short title",
              "caption": "Viral caption + exactly 4 hashtags",
              "lines": [
                {
                  "speaker": "A",
                  "line": "Short Hinglish dialogue",
                  "bubble": "Short Bubble",
                  "room": "OFFICE",
                  "camera": "WIDE_DOORWAY",
                  "activity": "DANCING",
                  "mood": "SMUG"
                }
              ]
            }
        """.trimIndent()

        val optionalExtras = buildString {
            if (hook.isNotBlank()) {
                appendLine("- hook_hint: ${hook.trim()}")
            }
            if (twist.isNotBlank()) {
                appendLine("- style_twist_hint: ${twist.trim()}")
            }
            if (clonedBlueprint != null) {
                appendLine("- cloned_reference_style: ${clonedBlueprint.pacingStyle} (${clonedBlueprint.comedyFormula})")
            }
        }.trimEnd()

        val userPrompt = buildString {
            appendLine("GENERATE ONE COMPLETELY NEW STICKMAN COMEDY REEL SCRIPT:")
            appendLine("- seed: $seed")
            appendLine("- topic: $topic")
            appendLine("- conflict: $conflict")
            appendLine("- primary_action_style: $actionStyle")
            appendLine("- niche_context: $effectiveNiche")
            appendLine("- characters: $charactersSpec")
            append("- banned_topics_and_jokes: ${if (banned.isEmpty()) "none" else banned.joinToString(" | ")}")
            if (optionalExtras.isNotBlank()) {
                appendLine()
                append(optionalExtras)
            }
        }

        return ComedyPromptBundle(
            seed = seed,
            topic = topic,
            conflict = conflict,
            actionStyle = actionStyle,
            niche = effectiveNiche,
            bannedTopics = banned,
            charactersSpec = charactersSpec,
            systemPrompt = systemPrompt,
            userPrompt = userPrompt
        )
    }

    fun comedyScriptResponseSchema(): JSONObject {
        val lineProps = JSONObject().apply {
            put("speaker", JSONObject().apply {
                put("type", "STRING")
                put("enum", JSONArray(listOf("A", "B", "C", "TITLE")))
            })
            put("line", JSONObject().put("type", "STRING"))
            put("bubble", JSONObject().put("type", "STRING"))
            put("room", JSONObject().apply {
                put("type", "STRING")
                put("enum", JSONArray(RoomSetting.entries.map { it.name }))
            })
            put("camera", JSONObject().apply {
                put("type", "STRING")
                put("enum", JSONArray(CameraFraming.entries.map { it.name }))
            })
            put("activity", JSONObject().apply {
                put("type", "STRING")
                put("enum", JSONArray(CharacterActivity.entries.map { it.name }))
            })
            put("mood", JSONObject().apply {
                put("type", "STRING")
                put("enum", JSONArray(MoodPose.entries.map { it.name }))
            })
        }

        return JSONObject().apply {
            put("type", "OBJECT")
            put("properties", JSONObject().apply {
                put("title", JSONObject().put("type", "STRING"))
                put("caption", JSONObject().put("type", "STRING"))
                put("lines", JSONObject().apply {
                    put("type", "ARRAY")
                    put("items", JSONObject().apply {
                        put("type", "OBJECT")
                        put("properties", lineProps)
                        put(
                            "required",
                            JSONArray(listOf("speaker", "line", "bubble", "room", "camera", "activity", "mood"))
                        )
                    })
                })
            })
            put("required", JSONArray(listOf("title", "caption", "lines")))
        }
    }

    fun parseComedyScriptJson(
        jsonText: String,
        niche: String,
        twist: String,
        language: String,
        clonedSummary: String,
        baseProject: ReelProject,
        fallbackTopic: String = "general"
    ): ReelProject {
        val json = JSONObject(jsonText)
        val title = json.optString("title", "Untitled InkReel").trim().ifBlank { "Untitled InkReel" }.take(120)
        val usedTopic = json.optString("topic", fallbackTopic).trim().ifBlank { fallbackTopic }
        val rawCaption = json.optString("caption", "").trim()

        val hashtagRegex = Regex("#[A-Za-z0-9_]+")
        val extractedTags = hashtagRegex.findAll(rawCaption).map { it.value }.distinct().take(4).toMutableList()
        val cleanCaption = hashtagRegex.replace(rawCaption, "").replace(Regex("\\s+"), " ").trim().take(500)
        val defaultTags = listOf("#hinglishcomedy", "#stickmanreels", "#desi", "#funnyreels")
        for (tag in defaultTags) {
            if (extractedTags.size >= 4) break
            if (extractedTags.none { it.equals(tag, ignoreCase = true) }) {
                extractedTags.add(tag)
            }
        }

        val linesArr = json.optJSONArray("lines") ?: JSONArray()
        val hasThirdChar = baseProject.characters.size >= 3
        val rawShots = mutableListOf<ReelShot>()
        val transitions = listOf(ShotTransition.CUT, ShotTransition.WHIP_PAN, ShotTransition.ZOOM_PUNCH, ShotTransition.FLASH)
        val lineCount = linesArr.length().coerceAtMost(ReelProject.MAX_SHOTS)

        for (idx in 0 until lineCount) {
            val obj = linesArr.optJSONObject(idx) ?: continue
            val isLast = idx == lineCount - 1
            val rawSpeaker = obj.optString("speaker", "A").trim().uppercase()
            val rawLine = obj.optString("line", "").trim()
            val rawCamera = obj.optString("camera", "").trim().uppercase()

            val isTitleCard = rawSpeaker == "TITLE" ||
                rawCamera == CameraFraming.TIME_SKIP_CARD.name ||
                (rawLine.contains("GHANTE BAAD", ignoreCase = true) && rawLine.split(Regex("\\s+")).size <= 5)

            val room = RoomSetting.entries.firstOrNull {
                it.name.equals(obj.optString("room", "").trim(), ignoreCase = true)
            } ?: RoomSetting.entries[idx % RoomSetting.entries.size]

            if (isTitleCard) {
                val cardText = rawLine.ifBlank { "3 GHANTE BAAD" }.take(32)
                rawShots.add(
                    ReelShot(
                        shotId = "shot_${UUID.randomUUID().toString().take(6)}_$idx",
                        speaker = SpeakerSlot.SILENT,
                        framing = CameraFraming.TIME_SKIP_CARD,
                        roomOverride = room,
                        transition = ShotTransition.FLASH,
                        dialogue = "",
                        speechBubble = "",
                        transitionCard = cardText,
                        moodPose = MoodPose.NEUTRAL,
                        activity = CharacterActivity.TALKING,
                        sticker = MemeSticker.NONE,
                        sfx = ComicSfx.TIME_SKIP_WHOOSH,
                        durationMs = 1400L,
                        audioSourceLabel = "Title Card"
                    )
                )
                continue
            }

            if (rawLine.isBlank()) continue

            var speaker = SpeakerSlot.fromRaw(rawSpeaker)
            if (speaker == SpeakerSlot.C && !hasThirdChar) {
                speaker = if (idx % 2 == 0) SpeakerSlot.A else SpeakerSlot.B
            }
            if (speaker == SpeakerSlot.SILENT) {
                speaker = if (idx % 2 == 0) SpeakerSlot.A else SpeakerSlot.B
            }

            val rawBubble = obj.optString("bubble", "").trim()
            val bubble = if (isLast && rawBubble.isBlank()) {
                derivePunchlineBubble(rawLine)
            } else {
                trimMaxWords(rawBubble, 4)
            }

            var framing = CameraFraming.entries.firstOrNull {
                it.name.equals(rawCamera, ignoreCase = true)
            } ?: CameraFraming.MEDIUM_TWO_SHOT
            if (framing == CameraFraming.TIME_SKIP_CARD) {
                framing = CameraFraming.MEDIUM_TWO_SHOT
            }

            val activity = CharacterActivity.entries.firstOrNull {
                it.name.equals(obj.optString("activity", "").trim(), ignoreCase = true)
            } ?: CharacterActivity.TALKING

            val mood = MoodPose.entries.firstOrNull {
                it.name.equals(obj.optString("mood", "").trim(), ignoreCase = true)
            } ?: MoodPose.NEUTRAL

            val optionalSfx = ComicSfx.entries.firstOrNull {
                it.name.equals(obj.optString("sfx", "").trim(), ignoreCase = true)
            }
            val inferredSfx = optionalSfx ?: inferSfxForBeat(
                idx = idx,
                isLast = isLast,
                framing = framing,
                mood = mood
            )

            val wordCount = rawLine.split(Regex("\\s+")).count { it.isNotBlank() }
            val estDurationMs = (wordCount * 310L + 1150L).coerceIn(1900L, 3900L)
            val transition = when {
                idx == 0 -> ShotTransition.CUT
                isLast -> ShotTransition.ZOOM_PUNCH
                else -> transitions[idx % transitions.size]
            }
            val sticker = if (isLast) MemeSticker.MIND_BLOWN else MemeSticker.NONE

            rawShots.add(
                ReelShot(
                    shotId = "shot_${UUID.randomUUID().toString().take(6)}_$idx",
                    speaker = speaker,
                    framing = framing,
                    roomOverride = room,
                    transition = transition,
                    dialogue = rawLine,
                    speechBubble = bubble,
                    moodPose = mood,
                    activity = activity,
                    sticker = sticker,
                    sfx = inferredSfx,
                    durationMs = estDurationMs,
                    audioSourceLabel = "Not Voiced"
                )
            )
        }

        val diversified = enforceComedySceneDiversity(
            shots = rawShots.ifEmpty { ReelProject.defaultShots() },
            fallbackSetting = baseProject.setting
        )

        val firstRoom = diversified.firstOrNull()?.roomOverride ?: baseProject.setting
        val hookBanner = trimMaxWords(diversified.firstOrNull()?.speechBubble?.ifBlank { title } ?: title, 4)
            .uppercase()
            .ifBlank { "WAIT FOR THE END!" }
            .take(48)

        val bgmMood = when (firstRoom) {
            RoomSetting.GYM, RoomSetting.STREET_PARK -> BgmMood.CHAOTIC_CHASE
            RoomSetting.INTERVIEW_ROOM, RoomSetting.HOSPITAL -> BgmMood.DRAMATIC_SUSPENSE
            else -> BgmMood.QUIRKY_COMEDY
        }

        val lastPunchline = diversified.lastOrNull { it.framing != CameraFraming.TIME_SKIP_CARD }?.dialogue.orEmpty()
        recordGenerated(usedTopic, title, lastPunchline)

        return baseProject.copy(
            id = "",
            title = title,
            introHookBanner = hookBanner,
            socialCaption = cleanCaption.ifBlank { title },
            hashtags = extractedTags.take(4),
            niche = niche.ifBlank { usedTopic },
            twistStructure = twist,
            language = language,
            setting = firstRoom,
            bgmMood = bgmMood,
            clonedStyleSummary = clonedSummary,
            shots = diversified,
            createdAtMs = System.currentTimeMillis(),
            updatedAtMs = System.currentTimeMillis()
        )
    }

    private fun inferSfxForBeat(
        idx: Int,
        isLast: Boolean,
        framing: CameraFraming,
        mood: MoodPose
    ): ComicSfx = when {
        isLast -> ComicSfx.PUNCHLINE_BOOM
        framing == CameraFraming.DESK_SLAM -> ComicSfx.DESK_SLAM
        framing == CameraFraming.WIDE_DOORWAY && idx == 0 -> ComicSfx.DOOR_OPEN
        framing == CameraFraming.PROP_DROP -> ComicSfx.CLIPBOARD_DROP
        framing == CameraFraming.SLAPSTICK_CHASE -> ComicSfx.CARTOON_RUN
        framing == CameraFraming.BONK_ENDING -> ComicSfx.CARTOON_BONK
        mood == MoodPose.SHOCKED -> ComicSfx.DRAMATIC_STING
        mood == MoodPose.SWEATING || mood == MoodPose.NERVOUS -> ComicSfx.NERVOUS_RATTLE
        else -> ComicSfx.NONE
    }

    /**
     * Ensures consecutive scenes change:
     * - roomOverride
     * - camera framing
     * - character activity
     * - mood pose
     * Plus guarantees at least 4 distinct rooms across the reel (when >= 4 scenes exist),
     * while preserving TIME_SKIP_CARD ("3 GHANTE BAAD") title cards.
     */
    internal fun enforceComedySceneDiversity(
        shots: List<ReelShot>,
        fallbackSetting: RoomSetting
    ): List<ReelShot> {
        if (shots.isEmpty()) return shots
        val allRooms = RoomSetting.entries.toList()
        val allFramings = listOf(
            CameraFraming.WIDE_DOORWAY,
            CameraFraming.MEDIUM_TWO_SHOT,
            CameraFraming.CLOSE_UP_A,
            CameraFraming.CLOSE_UP_B,
            CameraFraming.OVER_THE_SHOULDER,
            CameraFraming.DESK_SLAM,
            CameraFraming.SITTING_AT_DESK,
            CameraFraming.SPLIT_PHONE_CALL,
            CameraFraming.PROP_DROP,
            CameraFraming.CLOSE_UP_C,
            CameraFraming.SLAPSTICK_CHASE
        )
        val physicalActivities = CharacterActivity.entries.filter { it != CharacterActivity.TALKING }
        val allActivities = physicalActivities + CharacterActivity.TALKING
        val allMoods = MoodPose.entries.filter { it != MoodPose.NEUTRAL } + MoodPose.NEUTRAL

        val fixed = shots.toMutableList()
        val usedRooms = mutableSetOf<RoomSetting>()
        var prevSpoken: ReelShot? = null
        var talkingCount = 0

        for (i in fixed.indices) {
            val s = fixed[i]
            val prev = prevSpoken
            val prevRoom = prev?.roomOverride
            var room = s.roomOverride ?: fallbackSetting
            if (room == prevRoom) {
                room = allRooms.firstOrNull { it != prevRoom && it !in usedRooms }
                    ?: allRooms[(allRooms.indexOf(room) + 1) % allRooms.size]
            }
            usedRooms.add(room)

            if (s.framing == CameraFraming.TIME_SKIP_CARD) {
                fixed[i] = s.copy(roomOverride = room)
                continue
            }

            var framing = s.framing
            if (prev != null && framing == prev.framing) {
                framing = allFramings.firstOrNull { it != prev.framing } ?: framing
            }

            var activity = s.activity
            if (activity == CharacterActivity.TALKING && talkingCount >= 1) {
                activity = physicalActivities[i % physicalActivities.size]
            }
            if (prev != null && activity == prev.activity) {
                activity = allActivities.firstOrNull { it != prev.activity && (it != CharacterActivity.TALKING || talkingCount == 0) }
                    ?: activity
            }
            if (activity == CharacterActivity.TALKING) {
                talkingCount++
            }

            var mood = s.moodPose
            if (prev != null && mood == prev.moodPose) {
                mood = allMoods.firstOrNull { it != prev.moodPose } ?: mood
            }

            val updated = s.copy(
                roomOverride = room,
                framing = framing,
                activity = activity,
                moodPose = mood
            )
            fixed[i] = updated
            prevSpoken = updated
        }

        // Guarantee >= 4 distinct rooms across the whole reel when >= 4 scenes exist
        val targetMinRooms = minOf(4, fixed.size)
        var currentRooms = fixed.mapNotNull { it.roomOverride }.toSet()
        if (currentRooms.size < targetMinRooms) {
            for (i in fixed.indices) {
                if (currentRooms.size >= targetMinRooms) break
                val prevRoom = fixed.getOrNull(i - 1)?.roomOverride
                val nextRoom = fixed.getOrNull(i + 1)?.roomOverride
                val unusedRoom = allRooms.firstOrNull { r ->
                    r !in currentRooms && r != prevRoom && r != nextRoom
                } ?: continue
                val thisRoom = fixed[i].roomOverride
                if (thisRoom != null && fixed.count { it.roomOverride == thisRoom } > 1) {
                    fixed[i] = fixed[i].copy(roomOverride = unusedRoom)
                    currentRooms = fixed.mapNotNull { it.roomOverride }.toSet()
                }
            }
        }

        return fixed
    }

    internal fun trimMaxWords(text: String, maxWords: Int): String {
        val words = text.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        return if (words.size <= maxWords) {
            words.joinToString(" ")
        } else {
            words.take(maxWords).joinToString(" ")
        }
    }

    internal fun derivePunchlineBubble(line: String): String {
        val cleaned = line.replace(Regex("[^A-Za-z0-9'?!\\s-]"), " ")
            .trim()
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() }
        return if (cleaned.isEmpty()) {
            "PLOT TWIST!"
        } else {
            cleaned.takeLast(minOf(3, cleaned.size)).joinToString(" ").uppercase()
        }
    }
}
