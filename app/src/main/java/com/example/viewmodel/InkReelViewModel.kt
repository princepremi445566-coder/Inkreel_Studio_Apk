package com.example.viewmodel

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.ai.GeminiInkReelService
import com.example.audio.CustomAudioImporter
import com.example.audio.DeviceTtsSynthesizer
import com.example.audio.LiveReelAudioPlayer
import com.example.audio.MicVoiceRecorder
import com.example.audio.ProceduralAudioEngine
import com.example.data.ReelRepository
import com.example.export.ComicStripExporter
import com.example.export.ExportResult
import com.example.export.Mp4Exporter
import com.example.model.AmbientBed
import com.example.model.BgmMood
import com.example.model.CameraFraming
import com.example.model.CaptionFontStyle
import com.example.model.CaptionPosition
import com.example.model.CaptionStyle
import com.example.model.CastPreset
import com.example.model.CharacterActivity
import com.example.model.CharacterGender
import com.example.model.CharacterProfile
import com.example.model.ClonedStyleBlueprint
import com.example.model.ComicSfx
import com.example.model.ExportResolution
import com.example.model.MoodPose
import com.example.model.NichePreset
import com.example.model.ReelColorTheme
import com.example.model.ReelLanguage
import com.example.model.ReelProject
import com.example.model.ReelShot
import com.example.model.ReelStarterTemplate
import com.example.model.RoomSetting
import com.example.model.SampleScripts
import com.example.model.ShotTransition
import com.example.model.SocialMetaSuggestion
import com.example.model.SpeakerSlot
import com.example.model.TwistStructure
import com.example.model.WatermarkPosition
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.ArrayDeque
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.sin

enum class StageViewMode { YOUR_REEL, REFERENCE_CLIP }

data class CreateDraftState(
    val modeIndex: Int = 0,
    val selectedNiche: String = NichePreset.SCHOOL_TEACHER.label,
    val useCustomNiche: Boolean = false,
    val customNicheText: String = "",
    val selectedTwist: TwistStructure = TwistStructure.RULE_OF_3,
    val selectedLanguage: ReelLanguage = ReelLanguage.ENGLISH,
    val hookInput: String = "",
    val scriptText: String = SampleScripts.englishSample,
    val scriptSettingHint: RoomSetting? = null
)

data class InkReelStudioState(
    val project: ReelProject = ReelProject(),
    val selectedShotId: String = project.shots.firstOrNull()?.shotId.orEmpty(),
    val hasGeneratedReel: Boolean = false,
    val showApiKeyDialog: Boolean = false,
    val isPlaying: Boolean = false,
    val isMuted: Boolean = false,
    val showCaptions: Boolean = true,
    val showSpeechBubbles: Boolean = true,
    val showSafeZones: Boolean = false,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    val stageViewMode: StageViewMode = StageViewMode.YOUR_REEL,
    val referenceVideoUri: Uri? = null,
    val draft: CreateDraftState = CreateDraftState(),
    val brainstormedHooks: List<String> = emptyList(),
    val socialSuggestions: List<SocialMetaSuggestion> = emptyList(),
    val isBrainstorming: Boolean = false,
    val isRewritingSocial: Boolean = false,
    val isGeneratingReel: Boolean = false,
    val isDubbingLanguage: Boolean = false,
    val isSynthesizingAllVoices: Boolean = false,
    val isImportingBgm: Boolean = false,
    val isExportingComicStrip: Boolean = false,
    val voicingShotIds: Set<String> = emptySet(),
    val voiceProgressCurrent: Int = 0,
    val voiceProgressTotal: Int = 0,
    val failedVoiceShotIds: Map<String, String> = emptyMap(),
    val isAnalyzingStyle: Boolean = false,
    val clonedStyleBlueprint: ClonedStyleBlueprint? = null,
    val recordingShotId: String? = null,
    val isSavingReel: Boolean = false,
    val isExportingMp4: Boolean = false,
    val exportProgress: Float = 0f,
    val lastExportResult: ExportResult? = null,
    val lastMp4Export: ExportResult? = null,
    val statusBanner: String? = null,
    val errorBanner: String? = null
)

class InkReelViewModel(
    private val repository: ReelRepository,
    private val geminiService: GeminiInkReelService,
    private val deviceTts: DeviceTtsSynthesizer,
    private val savedStateHandle: SavedStateHandle = SavedStateHandle()
) : ViewModel() {

    private val initialProject: ReelProject = restoreProjectFromHandle()
    private val _playheadMs = MutableStateFlow(
        (savedStateHandle.get<Long>(KEY_PLAYHEAD_MS) ?: 0L).coerceIn(0L, initialProject.totalDurationMs)
    )
    val playheadMs: StateFlow<Long> = _playheadMs.asStateFlow()

    private val _micLevel = MutableStateFlow(0f)
    val micLevel: StateFlow<Float> = _micLevel.asStateFlow()

    private val _micCountdownSec = MutableStateFlow(0)
    val micCountdownSec: StateFlow<Int> = _micCountdownSec.asStateFlow()

    private val _uiState = MutableStateFlow(restoreInitialStudioState(initialProject))
    val uiState: StateFlow<InkReelStudioState> = _uiState.asStateFlow()

    val apiKey: StateFlow<String> = repository.apiKeyFlow
    val savedReels: StateFlow<List<ReelProject>> = repository.savedReels
    val castPresets: StateFlow<List<CastPreset>> = repository.castPresets

    private val shotAudioMap = ConcurrentHashMap<String, ByteArray>()
    private val shotEnvelopeMap = ConcurrentHashMap<String, FloatArray>()
    @Volatile
    private var customBgmPcm: ByteArray? = null

    private val undoStack = ArrayDeque<ReelProject>()
    private val redoStack = ArrayDeque<ReelProject>()
    private var lastUndoPushMs = 0L

    private val livePlayer = LiveReelAudioPlayer()
    private val micRecorder = MicVoiceRecorder()
    private var playLoopJob: Job? = null
    private var playbackLaunchJob: Job? = null
    private var voiceJob: Job? = null
    private var micCountdownJob: Job? = null

    init {
        voiceJob = viewModelScope.launch {
            restoreCachedAudioForCurrentProject()
            deviceTts.warmUp()
            ensureMissingVoicesInternal(preferGeminiIfKeySet = false)
        }
    }

    private fun restoreProjectFromHandle(): ReelProject {
        val rawJson = savedStateHandle.get<String>(KEY_PROJECT_JSON)
        if (!rawJson.isNullOrBlank()) {
            runCatching {
                return ReelProject.fromJson(JSONObject(rawJson))
            }
        }
        return ReelProject(watermarkHandle = repository.getWatermarkHandle())
    }

    private fun restoreBlueprintFromHandle(): ClonedStyleBlueprint? {
        val raw = savedStateHandle.get<String>(KEY_BLUEPRINT_JSON) ?: return null
        return runCatching {
            val obj = JSONObject(raw)
            val arr = obj.optJSONArray("recommendedFramings") ?: JSONArray()
            val framings = (0 until arr.length()).map { arr.getString(it) }
            ClonedStyleBlueprint(
                summary = obj.optString("summary"),
                averageShotDurationMs = obj.optLong("averageShotDurationMs", 2200L),
                pacingStyle = obj.optString("pacingStyle", "Rapid Escalation"),
                recommendedFramings = framings,
                comedyFormula = obj.optString("comedyFormula", "")
            )
        }.getOrNull()
    }

    private fun restoreInitialStudioState(project: ReelProject): InkReelStudioState {
        val savedShotId = savedStateHandle.get<String>(KEY_SELECTED_SHOT_ID)
        val validShotId = if (project.shots.any { it.shotId == savedShotId }) {
            savedShotId!!
        } else {
            project.shots.firstOrNull()?.shotId.orEmpty()
        }
        val refUri = savedStateHandle.get<String>(KEY_REF_VIDEO_URI)?.let { runCatching { Uri.parse(it) }.getOrNull() }
        val stageMode = savedStateHandle.get<String>(KEY_STAGE_VIEW_MODE)
            ?.let { name -> StageViewMode.entries.firstOrNull { it.name == name } }
            ?.takeIf { it == StageViewMode.YOUR_REEL || refUri != null }
            ?: StageViewMode.YOUR_REEL

        val draft = CreateDraftState(
            modeIndex = savedStateHandle.get<Int>(KEY_DRAFT_MODE) ?: 0,
            selectedNiche = savedStateHandle.get<String>(KEY_DRAFT_NICHE) ?: NichePreset.SCHOOL_TEACHER.label,
            useCustomNiche = savedStateHandle.get<Boolean>(KEY_DRAFT_CUSTOM_ON) ?: false,
            customNicheText = savedStateHandle.get<String>(KEY_DRAFT_CUSTOM_TEXT) ?: "",
            selectedTwist = savedStateHandle.get<String>(KEY_DRAFT_TWIST)
                ?.let { n -> TwistStructure.entries.firstOrNull { it.name == n } }
                ?: TwistStructure.RULE_OF_3,
            selectedLanguage = savedStateHandle.get<String>(KEY_DRAFT_LANG)
                ?.let { n -> ReelLanguage.entries.firstOrNull { it.name == n } }
                ?: ReelLanguage.ENGLISH,
            hookInput = savedStateHandle.get<String>(KEY_DRAFT_HOOK) ?: "",
            scriptText = savedStateHandle.get<String>(KEY_DRAFT_SCRIPT) ?: SampleScripts.englishSample,
            scriptSettingHint = savedStateHandle.get<String>(KEY_DRAFT_SETTING_HINT)
                ?.let { n -> RoomSetting.entries.firstOrNull { it.name == n } }
        )

        return InkReelStudioState(
            project = project,
            selectedShotId = validShotId,
            hasGeneratedReel = savedStateHandle.get<Boolean>(KEY_HAS_GENERATED) ?: false,
            isMuted = savedStateHandle.get<Boolean>(KEY_IS_MUTED) ?: false,
            showCaptions = savedStateHandle.get<Boolean>(KEY_SHOW_CAPTIONS) ?: true,
            showSpeechBubbles = savedStateHandle.get<Boolean>(KEY_SHOW_BUBBLES) ?: true,
            showSafeZones = savedStateHandle.get<Boolean>(KEY_SHOW_SAFE_ZONES) ?: false,
            stageViewMode = stageMode,
            referenceVideoUri = refUri,
            draft = draft,
            clonedStyleBlueprint = restoreBlueprintFromHandle()
        )
    }

    private suspend fun restoreCachedAudioForCurrentProject() {
        val proj = _uiState.value.project
        val key = proj.id.ifBlank { "draft" }
        var loadedAny = false
        for (s in proj.shots) {
            if (shotAudioMap.containsKey(s.shotId)) continue
            val pcm = repository.loadShotAudioPcm(key, s.shotId)
            if (pcm != null) {
                val env = withContext(Dispatchers.Default) { repository.computeEnvelope30Fps(pcm) }
                shotAudioMap[s.shotId] = pcm
                shotEnvelopeMap[s.shotId] = env
                loadedAny = true
            }
        }
        if (proj.customBgmName.isNotBlank()) {
            customBgmPcm = repository.loadCustomBgmPcm(key)
        }
        if (loadedAny) {
            _uiState.update { st ->
                val patched = st.project.shots.map { s ->
                    if (shotAudioMap.containsKey(s.shotId) && s.audioSourceLabel == "Not Voiced") {
                        s.copy(audioSourceLabel = "Cached Audio")
                    } else s
                }
                st.copy(project = st.project.copy(shots = patched))
            }
        }
    }

    private fun saveStateToHandle(st: InkReelStudioState, playhead: Long = _playheadMs.value) {
        runCatching {
            savedStateHandle[KEY_PROJECT_JSON] = st.project.toJson().toString()
            savedStateHandle[KEY_SELECTED_SHOT_ID] = st.selectedShotId
            savedStateHandle[KEY_HAS_GENERATED] = st.hasGeneratedReel
            savedStateHandle[KEY_PLAYHEAD_MS] = playhead
            savedStateHandle[KEY_IS_MUTED] = st.isMuted
            savedStateHandle[KEY_SHOW_CAPTIONS] = st.showCaptions
            savedStateHandle[KEY_SHOW_BUBBLES] = st.showSpeechBubbles
            savedStateHandle[KEY_SHOW_SAFE_ZONES] = st.showSafeZones
            savedStateHandle[KEY_STAGE_VIEW_MODE] = st.stageViewMode.name
            savedStateHandle[KEY_REF_VIDEO_URI] = st.referenceVideoUri?.toString()
            savedStateHandle[KEY_BLUEPRINT_JSON] = st.clonedStyleBlueprint?.let { bp ->
                JSONObject().apply {
                    put("summary", bp.summary)
                    put("averageShotDurationMs", bp.averageShotDurationMs)
                    put("pacingStyle", bp.pacingStyle)
                    put("recommendedFramings", JSONArray(bp.recommendedFramings))
                    put("comedyFormula", bp.comedyFormula)
                }.toString()
            }
        }
    }

    private inline fun updateStudioState(transform: (InkReelStudioState) -> InkReelStudioState) {
        var latest: InkReelStudioState? = null
        _uiState.update { cur ->
            transform(cur).also { latest = it }
        }
        latest?.let { saveStateToHandle(it) }
    }

    private fun pushUndoSnapshot(snapshot: ReelProject, debounce: Boolean = false) {
        val now = SystemClock.elapsedRealtime()
        if (debounce && undoStack.isNotEmpty() && (now - lastUndoPushMs) < 600L) {
            return
        }
        if (undoStack.peekLast() != snapshot) {
            undoStack.addLast(snapshot)
            while (undoStack.size > 25) {
                undoStack.removeFirst()
            }
            redoStack.clear()
            lastUndoPushMs = now
        }
    }

    private fun mutateProjectWithUndo(
        debounce: Boolean = false,
        transform: (InkReelStudioState) -> InkReelStudioState
    ) {
        val beforeProject = _uiState.value.project
        pushUndoSnapshot(beforeProject, debounce = debounce)
        updateStudioState { cur ->
            transform(cur).copy(
                canUndo = undoStack.isNotEmpty(),
                canRedo = redoStack.isNotEmpty()
            )
        }
    }

    fun undo() {
        val prev = if (undoStack.isNotEmpty()) undoStack.removeLast() else return
        pausePlayback()
        redoStack.addLast(_uiState.value.project)
        updateStudioState { st ->
            val validShot = if (prev.shots.any { it.shotId == st.selectedShotId }) {
                st.selectedShotId
            } else {
                prev.shots.firstOrNull()?.shotId.orEmpty()
            }
            st.copy(
                project = prev,
                selectedShotId = validShot,
                lastMp4Export = null,
                canUndo = undoStack.isNotEmpty(),
                canRedo = redoStack.isNotEmpty(),
                statusBanner = "Undid last edit."
            )
        }
        val missingVoice = prev.shots.any {
            it.speaker != SpeakerSlot.SILENT && it.dialogue.isNotBlank() && !shotAudioMap.containsKey(it.shotId)
        }
        if (missingVoice) {
            synthesizeAllSpokenShots()
        }
    }

    fun redo() {
        val next = if (redoStack.isNotEmpty()) redoStack.removeLast() else return
        pausePlayback()
        undoStack.addLast(_uiState.value.project)
        updateStudioState { st ->
            val validShot = if (next.shots.any { it.shotId == st.selectedShotId }) {
                st.selectedShotId
            } else {
                next.shots.firstOrNull()?.shotId.orEmpty()
            }
            st.copy(
                project = next,
                selectedShotId = validShot,
                lastMp4Export = null,
                canUndo = undoStack.isNotEmpty(),
                canRedo = redoStack.isNotEmpty(),
                statusBanner = "Redid edit."
            )
        }
        val missingVoice = next.shots.any {
            it.speaker != SpeakerSlot.SILENT && it.dialogue.isNotBlank() && !shotAudioMap.containsKey(it.shotId)
        }
        if (missingVoice) {
            synthesizeAllSpokenShots()
        }
    }

    // --- Draft Create Panel Actions (Survived across Tab Switches, Rotation & Process Death) ---

    fun updateCreateMode(index: Int) {
        savedStateHandle[KEY_DRAFT_MODE] = index
        _uiState.update { it.copy(draft = it.draft.copy(modeIndex = index)) }
    }

    fun selectNichePreset(label: String) {
        savedStateHandle[KEY_DRAFT_CUSTOM_ON] = false
        savedStateHandle[KEY_DRAFT_NICHE] = label
        _uiState.update { it.copy(draft = it.draft.copy(useCustomNiche = false, selectedNiche = label)) }
    }

    fun setUseCustomNiche(enabled: Boolean) {
        savedStateHandle[KEY_DRAFT_CUSTOM_ON] = enabled
        _uiState.update { it.copy(draft = it.draft.copy(useCustomNiche = enabled)) }
    }

    fun updateCustomNicheText(text: String) {
        savedStateHandle[KEY_DRAFT_CUSTOM_TEXT] = text
        _uiState.update { it.copy(draft = it.draft.copy(customNicheText = text)) }
    }

    fun selectTwist(twist: TwistStructure) {
        savedStateHandle[KEY_DRAFT_TWIST] = twist.name
        _uiState.update { it.copy(draft = it.draft.copy(selectedTwist = twist)) }
    }

    fun selectLanguage(lang: ReelLanguage) {
        savedStateHandle[KEY_DRAFT_LANG] = lang.name
        _uiState.update { it.copy(draft = it.draft.copy(selectedLanguage = lang)) }
    }

    fun updateHookInput(text: String) {
        savedStateHandle[KEY_DRAFT_HOOK] = text
        _uiState.update { it.copy(draft = it.draft.copy(hookInput = text)) }
    }

    fun updateScriptText(text: String) {
        savedStateHandle[KEY_DRAFT_SCRIPT] = text
        _uiState.update { it.copy(draft = it.draft.copy(scriptText = text)) }
    }

    fun updateScriptSettingHint(setting: RoomSetting?) {
        savedStateHandle[KEY_DRAFT_SETTING_HINT] = setting?.name
        _uiState.update { it.copy(draft = it.draft.copy(scriptSettingHint = setting)) }
    }

    fun loadSampleScript(isHinglish: Boolean) {
        val text = if (isHinglish) SampleScripts.hinglishSample else SampleScripts.englishSample
        val lang = if (isHinglish) ReelLanguage.HINGLISH else ReelLanguage.ENGLISH
        savedStateHandle[KEY_DRAFT_SCRIPT] = text
        savedStateHandle[KEY_DRAFT_LANG] = lang.name
        _uiState.update {
            it.copy(draft = it.draft.copy(scriptText = text, selectedLanguage = lang))
        }
    }

    fun updateWatermarkUsername(rawUsername: String) {
        val cleaned = rawUsername.take(36)
        repository.saveWatermarkHandle(cleaned)
        updateStudioState { st ->
            st.copy(
                lastMp4Export = null,
                project = st.project.copy(watermarkHandle = cleaned)
            )
        }
    }

    fun openApiKeyDialog() {
        _uiState.update { it.copy(showApiKeyDialog = true) }
    }

    fun dismissApiKeyDialog() {
        _uiState.update { it.copy(showApiKeyDialog = false) }
    }

    fun saveApiKey(rawKey: String) {
        val trimmed = rawKey.trim()
        if (trimmed.isBlank()) {
            _uiState.update { it.copy(errorBanner = "API key cannot be empty.") }
            return
        }
        repository.saveApiKey(trimmed)
        _uiState.update {
            it.copy(
                showApiKeyDialog = false,
                errorBanner = null,
                statusBanner = "Gemini API key saved on this device."
            )
        }
    }

    fun clearApiKey() {
        repository.clearApiKey()
        val msg = if (repository.hasBuildConfigApiKey()) {
            "Custom key cleared; using pre-configured app Gemini API key."
        } else {
            "Gemini API key cleared."
        }
        _uiState.update {
            it.copy(
                showApiKeyDialog = false,
                statusBanner = msg
            )
        }
    }

    fun hasBuildConfigApiKey(): Boolean = repository.hasBuildConfigApiKey()

    fun reportError(message: String) {
        _uiState.update { it.copy(errorBanner = message) }
    }

    private fun requireApiKey(): String? {
        val key = repository.getApiKey()
        if (key.isBlank()) {
            _uiState.update {
                it.copy(
                    showApiKeyDialog = true,
                    errorBanner = "Please enter your Gemini API key first (or set GEMINI_API_KEY in AI Studio Secrets)."
                )
            }
            return null
        }
        return key
    }

    fun currentLipAmplitude(atPlayheadMs: Long = _playheadMs.value): Float {
        val state = _uiState.value
        if (!state.isPlaying) return 0f
        val proj = state.project
        if (atPlayheadMs >= proj.shotsDurationMs) return 0f
        var acc = 0L
        for (shot in proj.shots) {
            val end = acc + shot.durationMs
            if (atPlayheadMs < end) {
                if (shot.speaker == SpeakerSlot.SILENT) return 0f
                val localMs = (atPlayheadMs - acc).coerceAtLeast(0L)
                val env = shotEnvelopeMap[shot.shotId]
                if (env != null && env.isNotEmpty()) {
                    val speed = proj.characterFor(shot.speaker)?.voiceSpeed ?: 1.0f
                    val frameIdx = ((localMs * 30L * speed) / 1000f).toInt()
                    if (frameIdx < 0 || frameIdx > env.lastIndex) return 0f
                    return env[frameIdx]
                }
                return if (shot.dialogue.isNotBlank()) {
                    (0.45f + 0.45f * sin((localMs / 1000f) * 24f)).coerceIn(0f, 1f)
                } else 0f
            }
            acc = end
        }
        return 0f
    }

    fun dismissBanners() {
        _uiState.update { it.copy(statusBanner = null, errorBanner = null) }
    }

    fun togglePlayPause() {
        if (_uiState.value.isPlaying) pausePlayback() else startPlayback()
    }

    fun startPlayback() {
        playbackLaunchJob?.cancel()
        playbackLaunchJob = viewModelScope.launch {
            try {
                val hasMissingVoices = _uiState.value.project.shots.any {
                    it.speaker != SpeakerSlot.SILENT && it.dialogue.isNotBlank() && !shotAudioMap.containsKey(it.shotId)
                }
                if (hasMissingVoices) {
                    voiceJob?.cancel()
                    ensureMissingVoicesInternal(preferGeminiIfKeySet = false)
                }

                val state = _uiState.value
                val total = state.project.totalDurationMs
                val currentPos = _playheadMs.value
                val startFrom = if (currentPos >= total - 50L) 0L else currentPos
                _playheadMs.value = startFrom
                _uiState.update { it.copy(isPlaying = true, errorBanner = null) }

                val mixedPcm = withContext(Dispatchers.Default) {
                    ProceduralAudioEngine.mixReelAudio(
                        project = state.project,
                        shotAudioMap = shotAudioMap.toMap(),
                        isMuted = state.isMuted,
                        customBgmPcm = customBgmPcm
                    )
                }
                livePlayer.playFromMs(viewModelScope, mixedPcm, startFrom)

                val startWallMs = SystemClock.elapsedRealtime() - startFrom
                playLoopJob?.cancel()
                playLoopJob = viewModelScope.launch {
                    while (isActive) {
                        val elapsedMs = SystemClock.elapsedRealtime() - startWallMs
                        if (elapsedMs >= total) {
                            _playheadMs.value = 0L
                            savedStateHandle[KEY_PLAYHEAD_MS] = 0L
                            _uiState.update { it.copy(isPlaying = false) }
                            livePlayer.stop(flushImmediate = false)
                            break
                        } else {
                            _playheadMs.value = elapsedMs
                        }
                        delay(33L)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                livePlayer.stop()
                _uiState.update {
                    it.copy(
                        isPlaying = false,
                        errorBanner = e.localizedMessage ?: "Audio playback failed."
                    )
                }
            }
        }
    }

    fun pausePlayback() {
        playbackLaunchJob?.cancel()
        playbackLaunchJob = null
        playLoopJob?.cancel()
        playLoopJob = null
        livePlayer.stop()
        savedStateHandle[KEY_PLAYHEAD_MS] = _playheadMs.value
        _uiState.update { it.copy(isPlaying = false) }
    }

    fun restartPlayback() {
        pausePlayback()
        _playheadMs.value = 0L
        savedStateHandle[KEY_PLAYHEAD_MS] = 0L
        startPlayback()
    }

    fun seekTo(ms: Long) {
        val wasPlaying = _uiState.value.isPlaying
        pausePlayback()
        val clamped = ms.coerceIn(0L, _uiState.value.project.totalDurationMs)
        _playheadMs.value = clamped
        savedStateHandle[KEY_PLAYHEAD_MS] = clamped
        if (wasPlaying) startPlayback()
    }

    fun toggleMute() {
        val nextMute = !_uiState.value.isMuted
        updateStudioState { it.copy(isMuted = nextMute) }
        if (_uiState.value.isPlaying) {
            pausePlayback()
            startPlayback()
        }
    }

    fun toggleCaptions() = updateStudioState { it.copy(showCaptions = !it.showCaptions) }
    fun toggleSpeechBubbles() = updateStudioState { it.copy(showSpeechBubbles = !it.showSpeechBubbles) }
    fun toggleSafeZones() = updateStudioState { it.copy(showSafeZones = !it.showSafeZones) }

    fun setStageViewMode(mode: StageViewMode) {
        if (mode == StageViewMode.YOUR_REEL || _uiState.value.referenceVideoUri != null) {
            updateStudioState { it.copy(stageViewMode = mode) }
        }
    }

    fun selectShot(shotId: String) {
        val shots = _uiState.value.project.shots
        var acc = 0L
        for (s in shots) {
            if (s.shotId == shotId) {
                pausePlayback()
                val newPos = (acc + 120L).coerceAtMost(_uiState.value.project.totalDurationMs)
                _playheadMs.value = newPos
                savedStateHandle[KEY_PLAYHEAD_MS] = newPos
                updateStudioState { it.copy(selectedShotId = shotId) }
                return
            }
            acc += s.durationMs
        }
    }

    // --- AI Creation Workflows ---

    fun brainstormHooks(niche: String, twist: String, language: String) {
        val key = requireApiKey() ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(isBrainstorming = true, errorBanner = null) }
            try {
                val result = geminiService.brainstormHooks(key, niche, twist, language)
                result.onSuccess { outcome ->
                    _uiState.update {
                        it.copy(
                            isBrainstorming = false,
                            brainstormedHooks = outcome.data,
                            statusBanner = if (outcome.usedFallback) {
                                "${outcome.fallbackReason} — loaded ${outcome.data.size} instant comedy hooks."
                            } else {
                                "Generated ${outcome.data.size} viral comedy hooks."
                            }
                        )
                    }
                }.onFailure { err ->
                    _uiState.update {
                        it.copy(
                            isBrainstorming = false,
                            errorBanner = err.localizedMessage ?: "Failed to brainstorm hooks."
                        )
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(isBrainstorming = false, errorBanner = e.localizedMessage ?: "Hook brainstorm failed.")
                }
            }
        }
    }

    fun generateReelFromNiche(niche: String, twist: String, language: String, hook: String) {
        val key = requireApiKey() ?: return
        pausePlayback()
        voiceJob?.cancel()
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isGeneratingReel = true,
                    errorBanner = null,
                    statusBanner = "Writing stickman comedy storyboard with Gemini..."
                )
            }
            try {
                val result = geminiService.generateStoryboardFromNiche(
                    apiKey = key,
                    niche = niche,
                    twist = twist,
                    language = language,
                    hook = hook,
                    clonedBlueprint = _uiState.value.clonedStyleBlueprint,
                    baseProject = _uiState.value.project
                )
                result.onSuccess { outcome ->
                    val newProject = outcome.data
                    shotAudioMap.clear()
                    shotEnvelopeMap.clear()
                    _playheadMs.value = 0L
                    savedStateHandle[KEY_PLAYHEAD_MS] = 0L
                    updateStudioState {
                        it.copy(
                            project = newProject,
                            selectedShotId = newProject.shots.firstOrNull()?.shotId.orEmpty(),
                            hasGeneratedReel = true,
                            lastMp4Export = null,
                            stageViewMode = StageViewMode.YOUR_REEL,
                            isGeneratingReel = false,
                            errorBanner = null,
                            statusBanner = if (outcome.usedFallback) {
                                "${outcome.fallbackReason} — generated instant comedy reel! Preparing cast voices..."
                            } else {
                                "Video storyboard ready! Synthesizing cast voices..."
                            }
                        )
                    }
                    synthesizeAllSpokenShots()
                }.onFailure { err ->
                    _uiState.update {
                        it.copy(
                            isGeneratingReel = false,
                            errorBanner = err.localizedMessage ?: "Storyboard generation failed."
                        )
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isGeneratingReel = false,
                        errorBanner = e.localizedMessage ?: "Storyboard generation failed."
                    )
                }
            }
        }
    }

    fun convertScriptToReel(scriptText: String, settingHint: RoomSetting?, language: String) {
        if (scriptText.isBlank()) {
            _uiState.update { it.copy(errorBanner = "Paste or load a dialogue script first.") }
            return
        }
        val key = repository.getApiKey()
        pausePlayback()
        voiceJob?.cancel()
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isGeneratingReel = true,
                    errorBanner = null,
                    statusBanner = if (key.isNotBlank()) "Converting full script into shots..." else "Parsing full script offline into shots..."
                )
            }
            try {
                val result = geminiService.convertScriptToStoryboard(
                    apiKey = key,
                    scriptText = scriptText,
                    settingHint = settingHint,
                    language = language,
                    baseProject = _uiState.value.project
                )
                result.onSuccess { newProject ->
                    shotAudioMap.clear()
                    shotEnvelopeMap.clear()
                    _playheadMs.value = 0L
                    savedStateHandle[KEY_PLAYHEAD_MS] = 0L
                    updateStudioState {
                        it.copy(
                            project = newProject,
                            selectedShotId = newProject.shots.firstOrNull()?.shotId.orEmpty(),
                            hasGeneratedReel = true,
                            lastMp4Export = null,
                            stageViewMode = StageViewMode.YOUR_REEL,
                            isGeneratingReel = false,
                            statusBanner = "All ${newProject.shots.size} shots ready! Voicing full script..."
                        )
                    }
                    synthesizeAllSpokenShots()
                }.onFailure { err ->
                    _uiState.update {
                        it.copy(
                            isGeneratingReel = false,
                            errorBanner = err.localizedMessage ?: "Script conversion failed."
                        )
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isGeneratingReel = false,
                        errorBanner = e.localizedMessage ?: "Script conversion failed."
                    )
                }
            }
        }
    }

    fun applyStarterTemplate(template: ReelStarterTemplate) {
        pausePlayback()
        voiceJob?.cancel()
        val base = _uiState.value.project
        val generated = geminiService.buildFallbackNicheStoryboard(
            niche = template.niche,
            twist = template.twist,
            language = template.language,
            hook = "",
            clonedSummary = "",
            baseProject = base
        ).copy(setting = template.setting)

        pushUndoSnapshot(base, debounce = false)
        _playheadMs.value = 0L
        savedStateHandle[KEY_PLAYHEAD_MS] = 0L
        updateStudioState {
            it.copy(
                project = generated,
                selectedShotId = generated.shots.firstOrNull()?.shotId.orEmpty(),
                hasGeneratedReel = true,
                lastMp4Export = null,
                canUndo = undoStack.isNotEmpty(),
                canRedo = redoStack.isNotEmpty(),
                stageViewMode = StageViewMode.YOUR_REEL,
                statusBanner = "Loaded \"${template.label}\" template! Synthesizing voices..."
            )
        }
        synthesizeAllSpokenShots()
    }

    fun generatePart2Sequel() {
        val key = repository.getApiKey()
        pausePlayback()
        voiceJob?.cancel()
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isGeneratingReel = true,
                    errorBanner = null,
                    statusBanner = if (key.isNotBlank()) "Writing Part 2 sequel with Gemini..." else "Generating instant Part 2 sequel..."
                )
            }
            try {
                val res = geminiService.generatePart2Sequel(key, _uiState.value.project)
                res.onSuccess { outcome ->
                    val sequel = outcome.data
                    shotAudioMap.clear()
                    shotEnvelopeMap.clear()
                    undoStack.clear()
                    redoStack.clear()
                    _playheadMs.value = 0L
                    savedStateHandle[KEY_PLAYHEAD_MS] = 0L
                    updateStudioState {
                        it.copy(
                            project = sequel,
                            selectedShotId = sequel.shots.firstOrNull()?.shotId.orEmpty(),
                            hasGeneratedReel = true,
                            lastMp4Export = null,
                            canUndo = false,
                            canRedo = false,
                            stageViewMode = StageViewMode.YOUR_REEL,
                            isGeneratingReel = false,
                            statusBanner = if (outcome.usedFallback) {
                                "${outcome.fallbackReason} — Part 2 ready! Voicing characters..."
                            } else {
                                "Part 2 sequel ready! Synthesizing cast voices..."
                            }
                        )
                    }
                    synthesizeAllSpokenShots()
                }.onFailure { err ->
                    _uiState.update {
                        it.copy(
                            isGeneratingReel = false,
                            errorBanner = err.localizedMessage ?: "Failed to generate Part 2 sequel."
                        )
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isGeneratingReel = false,
                        errorBanner = e.localizedMessage ?: "Failed to generate Part 2 sequel."
                    )
                }
            }
        }
    }

    fun rewriteSocialCaptions() {
        val key = repository.getApiKey()
        viewModelScope.launch {
            _uiState.update { it.copy(isRewritingSocial = true, errorBanner = null) }
            try {
                val res = geminiService.rewriteSocialMeta(key, _uiState.value.project)
                res.onSuccess { outcome ->
                    _uiState.update {
                        it.copy(
                            isRewritingSocial = false,
                            socialSuggestions = outcome.data,
                            statusBanner = if (outcome.usedFallback) {
                                "${outcome.fallbackReason} — tap any suggestion below to apply."
                            } else {
                                "3 viral title + caption + hashtag packs ready!"
                            }
                        )
                    }
                }.onFailure { err ->
                    _uiState.update {
                        it.copy(
                            isRewritingSocial = false,
                            errorBanner = err.localizedMessage ?: "Failed to rewrite social captions."
                        )
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isRewritingSocial = false,
                        errorBanner = e.localizedMessage ?: "Failed to rewrite social captions."
                    )
                }
            }
        }
    }

    fun applySocialSuggestion(suggestion: SocialMetaSuggestion) {
        mutateProjectWithUndo { st ->
            st.copy(
                lastMp4Export = null,
                socialSuggestions = emptyList(),
                project = st.project.copy(
                    title = suggestion.title,
                    socialCaption = suggestion.caption,
                    hashtags = suggestion.hashtags
                ),
                statusBanner = "Applied viral title, caption & hashtags!"
            )
        }
    }

    fun importCustomBgm(context: Context, uri: Uri) {
        pausePlayback()
        viewModelScope.launch {
            _uiState.update { it.copy(isImportingBgm = true, errorBanner = null) }
            try {
                val res = CustomAudioImporter.importAs24kMonoPcm(context.applicationContext, uri)
                res.onSuccess { imported ->
                    customBgmPcm = imported.pcm24kMono
                    val projKey = _uiState.value.project.id.ifBlank { "draft" }
                    repository.saveCustomBgmPcm(projKey, imported.pcm24kMono)
                    mutateProjectWithUndo { st ->
                        st.copy(
                            isImportingBgm = false,
                            lastMp4Export = null,
                            project = st.project.copy(customBgmName = imported.displayName),
                            statusBanner = "Imported BGM \"${imported.displayName}\" (auto-ducks under voice)."
                        )
                    }
                }.onFailure { err ->
                    _uiState.update {
                        it.copy(
                            isImportingBgm = false,
                            errorBanner = err.localizedMessage ?: "Could not import selected audio track."
                        )
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isImportingBgm = false,
                        errorBanner = e.localizedMessage ?: "Audio import failed."
                    )
                }
            }
        }
    }

    fun clearCustomBgm() {
        pausePlayback()
        val projKey = _uiState.value.project.id.ifBlank { "draft" }
        customBgmPcm = null
        viewModelScope.launch {
            repository.deleteCustomBgmPcm(projKey)
        }
        mutateProjectWithUndo { st ->
            st.copy(
                lastMp4Export = null,
                project = st.project.copy(customBgmName = ""),
                statusBanner = "Switched back to built-in procedural BGM."
            )
        }
    }

    fun analyzeReferenceClip(context: Context, uri: Uri) {
        val key = requireApiKey() ?: return
        updateStudioState { it.copy(referenceVideoUri = uri, isAnalyzingStyle = true, errorBanner = null) }
        viewModelScope.launch {
            try {
                val result = geminiService.analyzeReferenceVideoStyle(key, context.applicationContext, uri)
                result.onSuccess { blueprint ->
                    updateStudioState {
                        it.copy(
                            isAnalyzingStyle = false,
                            clonedStyleBlueprint = blueprint,
                            project = it.project.copy(clonedStyleSummary = blueprint.summary),
                            statusBanner = "Style blueprint extracted! Ready to apply in Niche Mode."
                        )
                    }
                }.onFailure { err ->
                    updateStudioState {
                        it.copy(
                            isAnalyzingStyle = false,
                            errorBanner = err.localizedMessage ?: "Video style analysis failed."
                        )
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                updateStudioState {
                    it.copy(
                        isAnalyzingStyle = false,
                        errorBanner = e.localizedMessage ?: "Video style analysis failed."
                    )
                }
            }
        }
    }

    // --- Voice Synthesis (Gemini TTS -> Device TTS -> Expressive Cartoon Synth) & Mic Dubbing ---

    fun synthesizeAllSpokenShots() {
        voiceJob?.cancel()
        voiceJob = viewModelScope.launch {
            val key = repository.getApiKey()
            val spokenShots = _uiState.value.project.shots.filter {
                it.speaker != SpeakerSlot.SILENT && it.dialogue.isNotBlank()
            }
            if (spokenShots.isEmpty()) {
                _uiState.update {
                    it.copy(
                        isSynthesizingAllVoices = false,
                        voiceProgressCurrent = 0,
                        voiceProgressTotal = 0
                    )
                }
                return@launch
            }

            deviceTts.clearCooldown()
            _uiState.update {
                it.copy(
                    isSynthesizingAllVoices = true,
                    voiceProgressCurrent = 0,
                    voiceProgressTotal = spokenShots.size,
                    failedVoiceShotIds = emptyMap(),
                    errorBanner = null
                )
            }
            val geminiCount = AtomicInteger(0)
            val deviceCount = AtomicInteger(0)
            val synthCount = AtomicInteger(0)
            val completedCount = AtomicInteger(0)
            val geminiAllowed = AtomicBoolean(key.isNotBlank())
            val limiter = Semaphore(permits = 2)

            try {
                coroutineScope {
                    spokenShots.map { shot ->
                        async {
                            if (!isActive) return@async
                            limiter.withPermit {
                                if (!isActive) return@withPermit
                                when (synthesizeShotWithFallback(
                                    apiKey = if (geminiAllowed.get()) key else "",
                                    shotId = shot.shotId,
                                    geminiTtsAllowed = geminiAllowed
                                )) {
                                    VoiceSourceResult.GEMINI -> geminiCount.incrementAndGet()
                                    VoiceSourceResult.DEVICE -> deviceCount.incrementAndGet()
                                    VoiceSourceResult.CARTOON_SYNTH -> synthCount.incrementAndGet()
                                    VoiceSourceResult.FAILED -> Unit
                                }
                                val done = completedCount.incrementAndGet()
                                _uiState.update { st ->
                                    st.copy(voiceProgressCurrent = done.coerceAtMost(spokenShots.size))
                                }
                            }
                        }
                    }.awaitAll()
                }

                val gCount = geminiCount.get()
                val dCount = deviceCount.get()
                val cCount = synthCount.get()
                val totalVoiced = gCount + dCount + cCount
                val failedMap = _uiState.value.failedVoiceShotIds
                updateStudioState {
                    it.copy(
                        hasGeneratedReel = true,
                        errorBanner = if (key.isNotBlank() && failedMap.isNotEmpty()) {
                            "${failedMap.size} line(s) used local backup voice (${failedMap.values.first()}). Tap Retry on highlighted lines."
                        } else {
                            null
                        },
                        statusBanner = when {
                            gCount > 0 && dCount == 0 && cCount == 0 ->
                                "Voiced all $gCount shots with Gemini AI Voice! Tap Play or Download MP4."
                            totalVoiced > 0 ->
                                "Voiced all $totalVoiced shots ($gCount Gemini, $dCount Device, $cCount Synth). Ready to Play or Download!"
                            else -> it.statusBanner
                        }
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(errorBanner = e.localizedMessage ?: "Voice synthesis failed.")
                }
            } finally {
                _uiState.update { it.copy(isSynthesizingAllVoices = false) }
            }
        }
    }

    fun revoiceShot(shotId: String) {
        viewModelScope.launch {
            try {
                val key = repository.getApiKey()
                val outcome = synthesizeShotWithFallback(
                    apiKey = key,
                    shotId = shotId,
                    geminiTtsAllowed = AtomicBoolean(key.isNotBlank()),
                    forceRetryGemini = key.isNotBlank()
                )
                if (outcome != VoiceSourceResult.FAILED) {
                    val label = when (outcome) {
                        VoiceSourceResult.GEMINI -> "Gemini AI Voice"
                        VoiceSourceResult.DEVICE -> "Device Voice"
                        VoiceSourceResult.CARTOON_SYNTH -> "Expressive Cartoon Synth"
                        VoiceSourceResult.FAILED -> ""
                    }
                    val remainingFailure = _uiState.value.failedVoiceShotIds[shotId]
                    updateStudioState {
                        it.copy(
                            lastMp4Export = null,
                            errorBanner = remainingFailure,
                            statusBanner = if (remainingFailure == null) {
                                "Shot voiced ($label) & lip-synced."
                            } else {
                                "Backup voice applied ($label)."
                            }
                        )
                    }
                } else {
                    _uiState.update { it.copy(errorBanner = "Shot has no spoken dialogue or speaker is Silent Beat.") }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update { it.copy(errorBanner = e.localizedMessage ?: "Voice synthesis failed.") }
            }
        }
    }

    private suspend fun ensureMissingVoicesInternal(preferGeminiIfKeySet: Boolean) {
        val key = if (preferGeminiIfKeySet) repository.getApiKey() else ""
        val shots = _uiState.value.project.shots
        val missing = shots.filter {
            it.speaker != SpeakerSlot.SILENT && it.dialogue.isNotBlank() && !shotAudioMap.containsKey(it.shotId)
        }
        if (missing.isEmpty()) {
            _uiState.update { it.copy(isSynthesizingAllVoices = false) }
            return
        }

        _uiState.update {
            it.copy(
                isSynthesizingAllVoices = true,
                voiceProgressCurrent = 0,
                voiceProgressTotal = missing.size
            )
        }
        val completedCount = AtomicInteger(0)
        val geminiAllowed = AtomicBoolean(key.isNotBlank())
        val limiter = Semaphore(permits = 2)
        try {
            coroutineScope {
                missing.map { shot ->
                    async {
                        limiter.withPermit {
                            synthesizeShotWithFallback(
                                apiKey = if (geminiAllowed.get()) key else "",
                                shotId = shot.shotId,
                                geminiTtsAllowed = geminiAllowed
                            )
                            val done = completedCount.incrementAndGet()
                            _uiState.update { st ->
                                st.copy(voiceProgressCurrent = done.coerceAtMost(missing.size))
                            }
                        }
                    }
                }.awaitAll()
            }
        } finally {
            _uiState.update { it.copy(isSynthesizingAllVoices = false) }
        }
    }

    private enum class VoiceSourceResult { GEMINI, DEVICE, CARTOON_SYNTH, FAILED }

    private fun isNonRecoverableAuthOrOfflineError(message: String?): Boolean {
        val msg = message.orEmpty()
        return msg.contains("No internet connection", ignoreCase = true) ||
            msg.contains("HTTP 400", ignoreCase = true) ||
            msg.contains("HTTP 401", ignoreCase = true) ||
            msg.contains("HTTP 403", ignoreCase = true) ||
            msg.contains("API key", ignoreCase = true) ||
            msg.contains("PERMISSION_DENIED", ignoreCase = true)
    }

    private suspend fun synthesizeShotWithFallback(
        apiKey: String,
        shotId: String,
        geminiTtsAllowed: AtomicBoolean,
        forceRetryGemini: Boolean = false
    ): VoiceSourceResult {
        val current = _uiState.value.project
        val shot = current.shots.firstOrNull { it.shotId == shotId } ?: return VoiceSourceResult.FAILED
        if (shot.speaker == SpeakerSlot.SILENT || shot.dialogue.isBlank()) return VoiceSourceResult.FAILED

        val charProfile = current.characterFor(shot.speaker)
        val voiceName = charProfile?.voiceName ?: "Puck"
        val gender = charProfile?.gender ?: CharacterGender.MALE
        val speed = charProfile?.voiceSpeed ?: 1.0f

        _uiState.update { it.copy(voicingShotIds = it.voicingShotIds + shotId) }

        val fullInputText = shot.phoneticHint.ifBlank { shot.dialogue }
        val chunks = geminiService.splitTextIntoTtsChunks(fullInputText, maxChunkChars = 250)
            .ifEmpty { listOf(shot.dialogue.trim()) }
        val chunkPcms = mutableListOf<ByteArray>()
        var geminiChunks = 0
        var deviceChunks = 0
        var synthChunks = 0
        var geminiFailureReason: String? = null

        var resultSource = VoiceSourceResult.FAILED
        try {
            for (chunk in chunks) {
                var chunkPcm: ByteArray? = null

                if (apiKey.isNotBlank() && (geminiTtsAllowed.get() || forceRetryGemini)) {
                    val geminiRes = geminiService.synthesizeSpeechChunkGemini(
                        apiKey = apiKey,
                        chunkText = chunk,
                        voiceName = voiceName,
                        moodPose = shot.moodPose
                    )
                    geminiRes.onSuccess { synth ->
                        chunkPcm = synth.pcmBytes
                        geminiChunks++
                    }.onFailure { err ->
                        val reason = err.localizedMessage ?: "Gemini TTS unavailable"
                        geminiFailureReason = reason
                        if (isNonRecoverableAuthOrOfflineError(reason)) {
                            geminiTtsAllowed.set(false)
                        }
                    }
                }

                if (chunkPcm == null) {
                    val deviceRes = deviceTts.synthesizeShotVoice(
                        dialogue = chunk,
                        phoneticHint = if (shot.phoneticHint.isNotBlank() && chunks.size == 1) shot.phoneticHint else "",
                        gender = gender,
                        language = current.language,
                        moodPose = shot.moodPose
                    )
                    deviceRes.onSuccess { synth ->
                        chunkPcm = synth.pcmBytes
                        deviceChunks++
                    }
                }

                if (chunkPcm == null) {
                    val fallbackSynth = withContext(Dispatchers.Default) {
                        ProceduralAudioEngine.synthesizeCartoonVoiceFallback(
                            text = chunk,
                            gender = gender,
                            moodPose = shot.moodPose
                        )
                    }
                    chunkPcm = fallbackSynth.pcmBytes
                    synthChunks++
                }

                chunkPcms.add(chunkPcm)
            }

            val combinedPcm = ProceduralAudioEngine.concatenatePcm24kMono(chunkPcms, gapMs = 200L)
            if (combinedPcm.size >= 2) {
                val combinedDurationMs = ((combinedPcm.size.toDouble() / 48000.0) * 1000.0).toLong().coerceAtLeast(700L)
                val label = when {
                    geminiChunks == chunks.size -> "Gemini TTS ($voiceName)"
                    geminiChunks > 0 -> "Gemini + Local ($voiceName)"
                    deviceChunks > 0 -> if (gender == CharacterGender.FEMALE) "Device Voice (Girl)" else "Device Voice (Boy)"
                    else -> if (gender == CharacterGender.FEMALE) "Cartoon Synth (Girl)" else "Cartoon Synth (Boy)"
                }
                applyShotPcm(shotId, combinedPcm, combinedDurationMs, speed, label)
                resultSource = when {
                    geminiChunks > 0 && deviceChunks == 0 && synthChunks == 0 -> VoiceSourceResult.GEMINI
                    deviceChunks > 0 -> VoiceSourceResult.DEVICE
                    else -> VoiceSourceResult.CARTOON_SYNTH
                }
            }

            _uiState.update { st ->
                val updatedFailures = if (apiKey.isNotBlank() && geminiFailureReason != null) {
                    st.failedVoiceShotIds + (shotId to geminiFailureReason)
                } else {
                    st.failedVoiceShotIds - shotId
                }
                st.copy(failedVoiceShotIds = updatedFailures)
            }
        } finally {
            _uiState.update { it.copy(voicingShotIds = it.voicingShotIds - shotId) }
        }
        return resultSource
    }

    private suspend fun applyShotPcm(
        shotId: String,
        pcmBytes: ByteArray,
        rawDurationMs: Long,
        speed: Float,
        sourceLabel: String
    ) {
        val current = _uiState.value.project
        val env = withContext(Dispatchers.Default) {
            repository.computeEnvelope30Fps(pcmBytes)
        }
        shotAudioMap[shotId] = pcmBytes
        shotEnvelopeMap[shotId] = env

        val projKey = current.id.ifBlank { "draft" }
        viewModelScope.launch(Dispatchers.IO) {
            repository.saveShotAudioPcm(projKey, shotId, pcmBytes)
        }

        // Exact duration from real synthesized audio + brief breathing margin (never truncated at 30s)
        val adjustedMs = ((rawDurationMs / speed).toLong() + 220L).coerceAtLeast(1100L)
        updateStudioState { st ->
            val updatedShots = st.project.shots.map {
                if (it.shotId == shotId) {
                    it.copy(durationMs = adjustedMs, audioSourceLabel = sourceLabel)
                } else it
            }
            st.copy(project = st.project.copy(shots = updatedShots))
        }
    }

    fun startMicRecording(shotId: String) {
        pausePlayback()
        micCountdownJob?.cancel()
        _micLevel.value = 0f
        _uiState.update { it.copy(recordingShotId = shotId, errorBanner = null) }
        micCountdownJob = viewModelScope.launch {
            for (sec in 3 downTo 1) {
                _micCountdownSec.value = sec
                delay(420L)
            }
            _micCountdownSec.value = 0
            micRecorder.startRecording(
                scope = viewModelScope,
                onLevel = { lvl -> _micLevel.value = lvl },
                onError = { msg ->
                    _micLevel.value = 0f
                    _uiState.update { it.copy(recordingShotId = null, errorBanner = msg) }
                }
            )
        }
    }

    fun stopMicRecording(shotId: String) {
        if (_micCountdownSec.value > 0) {
            micCountdownJob?.cancel()
            micCountdownJob = null
            _micCountdownSec.value = 0
            _micLevel.value = 0f
            _uiState.update { it.copy(recordingShotId = null, statusBanner = "Mic countdown cancelled.") }
            return
        }
        micCountdownJob?.cancel()
        micCountdownJob = null
        _micCountdownSec.value = 0
        _micLevel.value = 0f
        val pcm = micRecorder.stopRecording()
        _uiState.update { it.copy(recordingShotId = null) }
        if (pcm.size < 1600) {
            _uiState.update { it.copy(errorBanner = "Recording was too short. Speak clearly and try again.") }
            return
        }
        viewModelScope.launch {
            try {
                val env = withContext(Dispatchers.Default) {
                    repository.computeEnvelope30Fps(pcm)
                }
                shotAudioMap[shotId] = pcm
                shotEnvelopeMap[shotId] = env
                val current = _uiState.value.project
                val saveRes = repository.saveShotAudioPcm(current.id.ifBlank { "draft" }, shotId, pcm)
                if (saveRes.isFailure) {
                    _uiState.update {
                        it.copy(errorBanner = "Recorded mic dub, but failed to cache audio on disk.")
                    }
                }
                val durMs = ((pcm.size.toDouble() / 48000.0) * 1000.0).toLong().coerceIn(900L, 30000L)
                mutateProjectWithUndo { st ->
                    val updated = st.project.shots.map {
                        if (it.shotId == shotId) it.copy(durationMs = durMs + 250L, audioSourceLabel = "Mic Dub") else it
                    }
                    st.copy(
                        project = st.project.copy(shots = updated),
                        lastMp4Export = null,
                        statusBanner = "Mic dub recorded & lip-synced (${String.format(Locale.US, "%.1fs", durMs / 1000f)})."
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(errorBanner = e.localizedMessage ?: "Failed to process mic recording.")
                }
            }
        }
    }

    fun previewSfx(sfx: ComicSfx) {
        if (sfx == ComicSfx.NONE) return
        viewModelScope.launch(Dispatchers.Default) {
            runCatching {
                livePlayer.playSfxPreview(viewModelScope, sfx)
            }.onFailure { err ->
                _uiState.update { it.copy(errorBanner = err.localizedMessage ?: "Failed to play SFX preview.") }
            }
        }
    }

    // --- Shot & Cast Mutations ---

    fun updateShot(updated: ReelShot) {
        val oldShot = _uiState.value.project.shots.firstOrNull { it.shotId == updated.shotId }
        if (oldShot != null && (oldShot.dialogue != updated.dialogue || oldShot.phoneticHint != updated.phoneticHint || oldShot.speaker != updated.speaker)) {
            shotAudioMap.remove(updated.shotId)
            shotEnvelopeMap.remove(updated.shotId)
        }
        mutateProjectWithUndo(debounce = true) { st ->
            st.copy(
                lastMp4Export = null,
                project = st.project.copy(
                    shots = st.project.shots.map { if (it.shotId == updated.shotId) updated else it }
                )
            )
        }
    }

    fun addShot() {
        val current = _uiState.value.project
        if (current.shots.size >= ReelProject.MAX_SHOTS) {
            _uiState.update { it.copy(errorBanner = "Maximum ${ReelProject.MAX_SHOTS} shots per reel.") }
            return
        }
        val prev = current.shots.lastOrNull()
        val idx = current.shots.size
        val nextSpeaker = when (prev?.speaker) {
            SpeakerSlot.A -> SpeakerSlot.B
            SpeakerSlot.B -> SpeakerSlot.A
            else -> SpeakerSlot.B
        }
        val nextRoom = RoomSetting.entries.firstOrNull { it != (prev?.roomOverride ?: current.setting) } ?: current.setting
        val framings = listOf(
            CameraFraming.CLOSE_UP_A,
            CameraFraming.CLOSE_UP_B,
            CameraFraming.OVER_THE_SHOULDER,
            CameraFraming.WIDE_DOORWAY,
            CameraFraming.SITTING_AT_DESK,
            CameraFraming.SPLIT_PHONE_CALL
        )
        val nextFraming = framings[idx % framings.size].let {
            if (it == prev?.framing) framings[(idx + 1) % framings.size] else it
        }
        val nextActivity = CharacterActivity.entries[idx % CharacterActivity.entries.size].let {
            if (it == prev?.activity) CharacterActivity.entries[(idx + 1) % CharacterActivity.entries.size] else it
        }
        val nextMood = MoodPose.entries[idx % MoodPose.entries.size].let {
            if (it == prev?.moodPose) MoodPose.entries[(idx + 1) % MoodPose.entries.size] else it
        }
        val transitions = listOf(ShotTransition.WHIP_PAN, ShotTransition.ZOOM_PUNCH, ShotTransition.FLASH, ShotTransition.CUT)
        val nextTransition = transitions[idx % transitions.size].let {
            if (it == prev?.transition) transitions[(idx + 1) % transitions.size] else it
        }
        val newShot = ReelShot(
            shotId = "shot_${UUID.randomUUID().toString().take(6)}",
            speaker = nextSpeaker,
            framing = nextFraming,
            roomOverride = nextRoom,
            transition = nextTransition,
            activity = nextActivity,
            moodPose = nextMood,
            dialogue = "Wait, seriously?!",
            speechBubble = "SERIOUSLY?!"
        )
        mutateProjectWithUndo { st ->
            st.copy(
                lastMp4Export = null,
                project = st.project.copy(shots = st.project.shots + newShot),
                selectedShotId = newShot.shotId
            )
        }
    }

    fun duplicateShot(shotId: String) {
        val current = _uiState.value.project
        if (current.shots.size >= ReelProject.MAX_SHOTS) {
            _uiState.update { it.copy(errorBanner = "Maximum ${ReelProject.MAX_SHOTS} shots per reel.") }
            return
        }
        val idx = current.shots.indexOfFirst { it.shotId == shotId }
        if (idx < 0) return
        val source = current.shots[idx]
        val newId = "shot_${UUID.randomUUID().toString().take(6)}"
        val copy = source.copy(shotId = newId)
        shotAudioMap[source.shotId]?.let { pcm ->
            shotAudioMap[newId] = pcm
            shotEnvelopeMap[source.shotId]?.let { env -> shotEnvelopeMap[newId] = env }
            val projKey = current.id.ifBlank { "draft" }
            viewModelScope.launch(Dispatchers.IO) {
                repository.saveShotAudioPcm(projKey, newId, pcm)
            }
        }
        mutateProjectWithUndo { st ->
            val mutable = st.project.shots.toMutableList()
            val insertAt = (mutable.indexOfFirst { it.shotId == shotId } + 1).coerceIn(0, mutable.size)
            mutable.add(insertAt, copy)
            st.copy(
                lastMp4Export = null,
                project = st.project.copy(shots = mutable),
                selectedShotId = newId,
                statusBanner = "Duplicated shot #${idx + 1}."
            )
        }
    }

    fun moveShotUp(shotId: String) {
        val current = _uiState.value.project
        val idx = current.shots.indexOfFirst { it.shotId == shotId }
        if (idx <= 0) return
        mutateProjectWithUndo { st ->
            val mutable = st.project.shots.toMutableList()
            val tmp = mutable[idx - 1]
            mutable[idx - 1] = mutable[idx]
            mutable[idx] = tmp
            st.copy(
                lastMp4Export = null,
                project = st.project.copy(shots = mutable)
            )
        }
    }

    fun moveShotDown(shotId: String) {
        val current = _uiState.value.project
        val idx = current.shots.indexOfFirst { it.shotId == shotId }
        if (idx < 0 || idx >= current.shots.lastIndex) return
        mutateProjectWithUndo { st ->
            val mutable = st.project.shots.toMutableList()
            val tmp = mutable[idx + 1]
            mutable[idx + 1] = mutable[idx]
            mutable[idx] = tmp
            st.copy(
                lastMp4Export = null,
                project = st.project.copy(shots = mutable)
            )
        }
    }

    fun deleteShot(shotId: String) {
        val current = _uiState.value.project
        if (current.shots.size <= 1) return
        shotAudioMap.remove(shotId)
        shotEnvelopeMap.remove(shotId)
        val remaining = current.shots.filterNot { it.shotId == shotId }
        mutateProjectWithUndo { st ->
            val nextSelected = if (st.selectedShotId == shotId) {
                remaining.firstOrNull()?.shotId.orEmpty()
            } else st.selectedShotId
            st.copy(
                lastMp4Export = null,
                project = st.project.copy(shots = remaining),
                selectedShotId = nextSelected
            )
        }
    }

    fun updateCharacter(index: Int, profile: CharacterProfile) {
        val oldChar = _uiState.value.project.characters.getOrNull(index)
        var patchedShots = _uiState.value.project.shots
        if (oldChar != null) {
            if (oldChar.gender != profile.gender || oldChar.voiceName != profile.voiceName) {
                patchedShots.filter { it.speaker == profile.slot }.forEach { s ->
                    shotAudioMap.remove(s.shotId)
                    shotEnvelopeMap.remove(s.shotId)
                }
            } else if (oldChar.voiceSpeed != profile.voiceSpeed) {
                patchedShots = patchedShots.map { s ->
                    if (s.speaker == profile.slot) {
                        val pcm = shotAudioMap[s.shotId]
                        if (pcm != null) {
                            val rawMs = ((pcm.size.toDouble() / 48000.0) * 1000.0).toLong()
                            val newDur = ((rawMs / profile.voiceSpeed).toLong() + 220L).coerceAtLeast(1100L)
                            s.copy(durationMs = newDur)
                        } else s
                    } else s
                }
            }
        }
        mutateProjectWithUndo(debounce = true) { st ->
            val chars = st.project.characters.toMutableList()
            if (index in chars.indices) {
                chars[index] = profile
            }
            st.copy(lastMp4Export = null, project = st.project.copy(characters = chars, shots = patchedShots))
        }
    }

    fun updateProjectSettings(
        title: String? = null,
        setting: RoomSetting? = null,
        colorTheme: ReelColorTheme? = null,
        bgmMood: BgmMood? = null,
        bgmVolume: Float? = null,
        captionStyle: CaptionStyle? = null,
        captionFontStyle: CaptionFontStyle? = null,
        captionPosition: CaptionPosition? = null,
        introHookBanner: String? = null,
        enableOutro: Boolean? = null,
        outroText: String? = null,
        watermarkHandle: String? = null,
        watermarkPosition: WatermarkPosition? = null,
        exportResolution: ExportResolution? = null,
        ambientBed: AmbientBed? = null,
        ambientVolume: Float? = null
    ) {
        if (watermarkHandle != null) {
            repository.saveWatermarkHandle(watermarkHandle)
        }
        mutateProjectWithUndo(debounce = true) { st ->
            val p = st.project
            st.copy(
                lastMp4Export = null,
                project = p.copy(
                    title = title ?: p.title,
                    setting = setting ?: p.setting,
                    colorTheme = colorTheme ?: p.colorTheme,
                    bgmMood = bgmMood ?: p.bgmMood,
                    bgmVolume = bgmVolume ?: p.bgmVolume,
                    captionStyle = captionStyle ?: p.captionStyle,
                    captionFontStyle = captionFontStyle ?: p.captionFontStyle,
                    captionPosition = captionPosition ?: p.captionPosition,
                    introHookBanner = introHookBanner ?: p.introHookBanner,
                    enableOutro = enableOutro ?: p.enableOutro,
                    outroText = outroText ?: p.outroText,
                    watermarkHandle = watermarkHandle?.take(36) ?: p.watermarkHandle,
                    watermarkPosition = watermarkPosition ?: p.watermarkPosition,
                    exportResolution = exportResolution ?: p.exportResolution,
                    ambientBed = ambientBed ?: p.ambientBed,
                    ambientVolume = ambientVolume ?: p.ambientVolume
                )
            )
        }
    }

    // --- Reusable Cast Presets, Viral Pacing Tightener & 1-Tap Language Dub ---

    fun saveCharacterAsPreset(profile: CharacterProfile) {
        viewModelScope.launch {
            val res = repository.saveCastPreset(profile)
            res.onSuccess { preset ->
                _uiState.update {
                    it.copy(statusBanner = "Saved \"${preset.presetLabel}\" to Cast Library.")
                }
            }.onFailure { err ->
                _uiState.update {
                    it.copy(errorBanner = err.localizedMessage ?: "Failed to save cast preset.")
                }
            }
        }
    }

    fun applyCastPresetToSlot(slotIndex: Int, preset: CastPreset) {
        val current = _uiState.value.project.characters.getOrNull(slotIndex) ?: return
        val adapted = preset.profile.copy(slot = current.slot)
        updateCharacter(slotIndex, adapted)
        _uiState.update {
            it.copy(statusBanner = "Applied \"${preset.presetLabel}\" to Character ${current.slot.name}.")
        }
    }

    fun deleteCastPreset(presetId: String) {
        viewModelScope.launch {
            repository.deleteCastPreset(presetId)
        }
    }

    fun applyViralPacingTightener() {
        val proj = _uiState.value.project
        if (proj.shots.isEmpty()) return
        mutateProjectWithUndo(debounce = false) { st ->
            val tightened = st.project.shots.map { shot ->
                when {
                    shot.framing == CameraFraming.TIME_SKIP_CARD ->
                        shot.copy(durationMs = 1050L)
                    shot.speaker == SpeakerSlot.SILENT || shot.dialogue.isBlank() ->
                        shot.copy(durationMs = shot.durationMs.coerceAtMost(1200L).coerceAtLeast(800L))
                    else -> {
                        val pcm = shotAudioMap[shot.shotId]
                        if (pcm != null && pcm.size >= 4800) {
                            val charSpeed = (st.project.characterFor(shot.speaker)?.voiceSpeed ?: 1.0f).coerceIn(0.85f, 1.30f)
                            val voiceMs = (((pcm.size / 2) * 1000L / ProceduralAudioEngine.SAMPLE_RATE) / charSpeed).toLong()
                            val snappyMs = (voiceMs + 160L).coerceIn(900L, 12000L)
                            shot.copy(durationMs = snappyMs)
                        } else {
                            shot.copy(durationMs = (shot.durationMs * 0.80f).toLong().coerceIn(950L, 12000L))
                        }
                    }
                }
            }
            val updatedProj = st.project.copy(shots = tightened)
            st.copy(
                lastMp4Export = null,
                project = updatedProj,
                statusBanner = "Applied Snappy 1.2x Viral Cut! Total length: ${String.format(Locale.US, "%.1fs", updatedProj.totalDurationMs / 1000f)}"
            )
        }
    }

    fun dubProjectToLanguage(targetLanguage: ReelLanguage) {
        val key = repository.getApiKey()
        pausePlayback()
        voiceJob?.cancel()
        viewModelScope.launch {
            val beforeProject = _uiState.value.project
            _uiState.update {
                it.copy(
                    isDubbingLanguage = true,
                    errorBanner = null,
                    statusBanner = "Dubbing entire reel to ${targetLanguage.label}..."
                )
            }
            try {
                val res = geminiService.dubProjectLanguage(key, beforeProject, targetLanguage)
                res.onSuccess { outcome ->
                    pushUndoSnapshot(beforeProject, debounce = false)
                    shotAudioMap.clear()
                    shotEnvelopeMap.clear()
                    _playheadMs.value = 0L
                    savedStateHandle[KEY_PLAYHEAD_MS] = 0L
                    updateStudioState {
                        it.copy(
                            project = outcome.data,
                            canUndo = undoStack.isNotEmpty(),
                            canRedo = redoStack.isNotEmpty(),
                            lastMp4Export = null,
                            isDubbingLanguage = false,
                            statusBanner = if (outcome.usedFallback) {
                                "${outcome.fallbackReason} (${targetLanguage.label}) — re-voicing cast..."
                            } else {
                                "Dubbed reel to ${targetLanguage.label}! Re-voicing cast..."
                            }
                        )
                    }
                    synthesizeAllSpokenShots()
                }.onFailure { err ->
                    _uiState.update {
                        it.copy(
                            isDubbingLanguage = false,
                            errorBanner = err.localizedMessage ?: "Failed to dub reel."
                        )
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isDubbingLanguage = false,
                        errorBanner = e.localizedMessage ?: "Failed to dub reel."
                    )
                }
            }
        }
    }

    // --- Local Saved Reels Persistence ---

    fun saveCurrentReel() {
        viewModelScope.launch {
            _uiState.update { it.copy(isSavingReel = true, errorBanner = null) }
            val proj = _uiState.value.project
            val res = repository.saveReel(proj)
            res.onSuccess { savedId ->
                // Copy any draft shot PCMs & custom BGM into saved project directory so loading preserves audio
                val oldKey = proj.id.ifBlank { "draft" }
                if (oldKey != savedId) {
                    withContext(Dispatchers.IO) {
                        shotAudioMap.forEach { (shotId, bytes) ->
                            repository.saveShotAudioPcm(savedId, shotId, bytes)
                        }
                        customBgmPcm?.let { bgmBytes ->
                            repository.saveCustomBgmPcm(savedId, bgmBytes)
                        }
                    }
                }
                updateStudioState {
                    it.copy(
                        isSavingReel = false,
                        project = it.project.copy(id = savedId),
                        statusBanner = "Saved to your Saved Reels library."
                    )
                }
            }.onFailure { err ->
                _uiState.update {
                    it.copy(
                        isSavingReel = false,
                        errorBanner = err.localizedMessage ?: "Failed to save reel."
                    )
                }
            }
        }
    }

    fun loadSavedReel(reel: ReelProject) {
        pausePlayback()
        voiceJob?.cancel()
        viewModelScope.launch {
            try {
                shotAudioMap.clear()
                shotEnvelopeMap.clear()
                undoStack.clear()
                redoStack.clear()
                val key = reel.id.ifBlank { "draft" }
                for (s in reel.shots) {
                    val pcm = repository.loadShotAudioPcm(key, s.shotId)
                    if (pcm != null) {
                        val env = withContext(Dispatchers.Default) {
                            repository.computeEnvelope30Fps(pcm)
                        }
                        shotAudioMap[s.shotId] = pcm
                        shotEnvelopeMap[s.shotId] = env
                    }
                }
                customBgmPcm = if (reel.customBgmName.isNotBlank()) repository.loadCustomBgmPcm(key) else null
                val patchedShots = reel.shots.map { s ->
                    if (shotAudioMap.containsKey(s.shotId)) s.copy(audioSourceLabel = "Cached Audio") else s
                }
                _playheadMs.value = 0L
                savedStateHandle[KEY_PLAYHEAD_MS] = 0L
                updateStudioState {
                    it.copy(
                        project = reel.copy(shots = patchedShots),
                        selectedShotId = patchedShots.firstOrNull()?.shotId.orEmpty(),
                        hasGeneratedReel = true,
                        lastMp4Export = null,
                        canUndo = false,
                        canRedo = false,
                        stageViewMode = StageViewMode.YOUR_REEL,
                        statusBanner = "Loaded \"${reel.title}\"."
                    )
                }
                ensureMissingVoicesInternal(preferGeminiIfKeySet = false)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update { it.copy(errorBanner = e.localizedMessage ?: "Failed to load saved reel.") }
            }
        }
    }

    fun renameSavedReel(reelId: String, newTitle: String) {
        viewModelScope.launch {
            val res = repository.renameReel(reelId, newTitle)
            res.onSuccess { updated ->
                updateStudioState { st ->
                    val updatedCurrent = if (st.project.id == reelId) {
                        st.project.copy(title = updated.title, updatedAtMs = updated.updatedAtMs)
                    } else st.project
                    st.copy(
                        project = updatedCurrent,
                        errorBanner = null,
                        statusBanner = "Renamed reel to \"${updated.title}\"."
                    )
                }
            }.onFailure { err ->
                _uiState.update { it.copy(errorBanner = err.localizedMessage ?: "Rename failed.") }
            }
        }
    }

    fun duplicateSavedReel(reel: ReelProject) {
        viewModelScope.launch {
            val res = repository.duplicateReel(reel)
            res.onSuccess {
                _uiState.update { it.copy(statusBanner = "Duplicated \"${reel.title}\".") }
            }.onFailure { err ->
                _uiState.update { it.copy(errorBanner = err.localizedMessage ?: "Duplicate failed.") }
            }
        }
    }

    fun deleteSavedReel(reelId: String) {
        viewModelScope.launch {
            val res = repository.deleteReel(reelId)
            res.onSuccess {
                updateStudioState {
                    val clearedCurrent = if (it.project.id == reelId) it.project.copy(id = "") else it.project
                    it.copy(project = clearedCurrent, statusBanner = "Deleted saved reel.")
                }
            }.onFailure { err ->
                _uiState.update { it.copy(errorBanner = err.localizedMessage ?: "Delete failed.") }
            }
        }
    }

    // --- Export MP4 & PNG Thumbnail ---

    fun exportMp4(context: Context) {
        pausePlayback()
        viewModelScope.launch {
            _uiState.update { it.copy(isExportingMp4 = true, exportProgress = 0.02f, errorBanner = null) }
            try {
                voiceJob?.cancel()
                ensureMissingVoicesInternal(preferGeminiIfKeySet = false)

                val state = _uiState.value
                val res = Mp4Exporter.exportMp4(
                    context = context.applicationContext,
                    project = state.project,
                    shotAudioMap = shotAudioMap.toMap(),
                    isMuted = state.isMuted,
                    showCaptions = state.showCaptions,
                    showSpeechBubbles = state.showSpeechBubbles,
                    customBgmPcm = customBgmPcm,
                    onProgress = { p -> _uiState.update { it.copy(exportProgress = p) } }
                )
                res.onSuccess { exp ->
                    _uiState.update {
                        it.copy(
                            exportProgress = 1f,
                            lastExportResult = exp,
                            lastMp4Export = exp,
                            statusBanner = "Video downloaded to ${exp.displayPath}"
                        )
                    }
                }.onFailure { err ->
                    _uiState.update {
                        it.copy(
                            exportProgress = 0f,
                            errorBanner = err.localizedMessage ?: "MP4 export failed."
                        )
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        exportProgress = 0f,
                        errorBanner = e.localizedMessage ?: "MP4 export failed."
                    )
                }
            } finally {
                _uiState.update { it.copy(isExportingMp4 = false) }
            }
        }
    }

    fun saveThumbnailPng(context: Context) {
        val state = _uiState.value
        val curPlayhead = _playheadMs.value
        val amp = currentLipAmplitude(curPlayhead)
        viewModelScope.launch {
            try {
                val res = Mp4Exporter.savePngThumbnail(
                    context = context.applicationContext,
                    project = state.project,
                    playheadMs = curPlayhead,
                    lipAmplitude = amp
                )
                res.onSuccess { exp ->
                    _uiState.update {
                        it.copy(
                            lastExportResult = exp,
                            statusBanner = "PNG thumbnail saved to ${exp.displayPath}"
                        )
                    }
                }.onFailure { err ->
                    _uiState.update { it.copy(errorBanner = err.localizedMessage ?: "PNG save failed.") }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update { it.copy(errorBanner = e.localizedMessage ?: "PNG save failed.") }
            }
        }
    }

    fun exportComicStripPng(context: Context) {
        val state = _uiState.value
        viewModelScope.launch {
            _uiState.update { it.copy(isExportingComicStrip = true, errorBanner = null) }
            try {
                val res = ComicStripExporter.saveComicStripPng(
                    context = context.applicationContext,
                    project = state.project
                )
                res.onSuccess { exp ->
                    _uiState.update {
                        it.copy(
                            isExportingComicStrip = false,
                            lastExportResult = exp,
                            statusBanner = "4-Panel Comic Strip saved to ${exp.displayPath}"
                        )
                    }
                }.onFailure { err ->
                    _uiState.update {
                        it.copy(
                            isExportingComicStrip = false,
                            errorBanner = err.localizedMessage ?: "Comic strip export failed."
                        )
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isExportingComicStrip = false,
                        errorBanner = e.localizedMessage ?: "Comic strip export failed."
                    )
                }
            }
        }
    }

    fun exportProjectJsonToUri(context: Context, uri: Uri) {
        val proj = _uiState.value.project
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val os = context.applicationContext.contentResolver.openOutputStream(uri)
                        ?: throw IllegalStateException("Could not open backup destination.")
                    os.use { out ->
                        out.write(proj.toJson().toString(2).toByteArray(Charsets.UTF_8))
                    }
                }
                _uiState.update {
                    it.copy(statusBanner = "Backed up \"${proj.title}\" as InkReel JSON!")
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(errorBanner = e.localizedMessage ?: "Failed to export project backup.")
                }
            }
        }
    }

    fun importProjectJsonFromUri(context: Context, uri: Uri) {
        viewModelScope.launch {
            try {
                val imported = withContext(Dispatchers.IO) {
                    val ins = context.applicationContext.contentResolver.openInputStream(uri)
                        ?: throw IllegalStateException("Could not read selected backup file.")
                    val raw = ins.use { it.readBytes().toString(Charsets.UTF_8) }
                    ReelProject.fromJson(JSONObject(raw)).copy(id = "")
                }
                loadSavedReel(imported)
                _uiState.update {
                    it.copy(statusBanner = "Imported backup \"${imported.title}\"! Generating voices...")
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(errorBanner = "Invalid InkReel backup file: ${e.localizedMessage ?: "parse error"}")
                }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        voiceJob?.cancel()
        playbackLaunchJob?.cancel()
        playLoopJob?.cancel()
        micCountdownJob?.cancel()
        livePlayer.stop()
        micRecorder.stopRecording()
        deviceTts.shutdown()
    }

    companion object {
        private const val KEY_PROJECT_JSON = "state_project_json"
        private const val KEY_SELECTED_SHOT_ID = "state_selected_shot_id"
        private const val KEY_HAS_GENERATED = "state_has_generated"
        private const val KEY_PLAYHEAD_MS = "state_playhead_ms"
        private const val KEY_IS_MUTED = "state_is_muted"
        private const val KEY_SHOW_CAPTIONS = "state_show_captions"
        private const val KEY_SHOW_BUBBLES = "state_show_bubbles"
        private const val KEY_SHOW_SAFE_ZONES = "state_show_safe_zones"
        private const val KEY_STAGE_VIEW_MODE = "state_stage_mode"
        private const val KEY_REF_VIDEO_URI = "state_ref_uri"
        private const val KEY_BLUEPRINT_JSON = "state_blueprint_json"

        private const val KEY_DRAFT_MODE = "draft_mode"
        private const val KEY_DRAFT_NICHE = "draft_niche"
        private const val KEY_DRAFT_CUSTOM_ON = "draft_custom_on"
        private const val KEY_DRAFT_CUSTOM_TEXT = "draft_custom_text"
        private const val KEY_DRAFT_TWIST = "draft_twist"
        private const val KEY_DRAFT_LANG = "draft_lang"
        private const val KEY_DRAFT_HOOK = "draft_hook"
        private const val KEY_DRAFT_SCRIPT = "draft_script"
        private const val KEY_DRAFT_SETTING_HINT = "draft_setting_hint"
    }
}
