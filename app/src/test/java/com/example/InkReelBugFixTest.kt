package com.example

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import org.robolectric.Shadows
import com.example.ai.ComedyScriptGenerator
import com.example.ai.GeminiHttpException
import com.example.ai.GeminiInkReelService
import com.example.ai.GeminiModels
import com.example.audio.DeviceTtsSynthesizer
import com.example.audio.HinglishPhoneticTuner
import com.example.audio.ProceduralAudioEngine
import com.example.canvas.StickmanRenderer
import com.example.data.ReelRepository
import com.example.export.ComicStripExporter
import com.example.export.Mp4Exporter
import com.example.model.AmbientBed
import com.example.model.CameraFraming
import com.example.model.CastPreset
import com.example.model.CharacterActivity
import com.example.model.CharacterGender
import com.example.model.CharacterProfile
import com.example.model.ComicSfx
import com.example.model.MemeSticker
import com.example.model.MoodPose
import com.example.model.NichePreset
import com.example.model.OutfitColorOption
import com.example.model.ParticleOverlay
import com.example.model.ReelColorTheme
import com.example.model.ReelLanguage
import com.example.model.ReelProject
import com.example.model.ReelShot
import com.example.model.ReelStarterTemplate
import com.example.model.RoomSetting
import com.example.model.SampleScripts
import com.example.model.SfxTiming
import com.example.model.ShotTransition
import com.example.model.SpeakerSlot
import com.example.model.VoiceEffect
import com.example.model.WatermarkPosition
import com.example.viewmodel.InkReelViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class InkReelBugFixTest {

    @Test
    fun geminiService_stripsJsonFencesCleanly() {
        val service = GeminiInkReelService()
        val rawFenced = "```json\n{\"title\":\"Test\"}\n```"
        assertEquals("{\"title\":\"Test\"}", service.stripJsonCodeFences(rawFenced))
        assertEquals("{\"ok\":true}", service.stripJsonCodeFences("{\"ok\":true}"))
    }

    @Test
    fun geminiService_offlineHooksAndFallbackStoryboardNeverEmpty() {
        val service = GeminiInkReelService()
        val hooks1 = service.buildOfflineHooks("Gym & Trainer", "Rule of 3", "Hinglish")
        val hooks2 = service.buildOfflineHooks("Gym & Trainer", "Rule of 3", "Hinglish")
        assertEquals(5, hooks1.size)
        assertTrue(hooks1.all { it.isNotBlank() })
        assertTrue("Repeated hook brainstorming should rotate hooks", hooks1.first() != hooks2.first())

        val base = ReelProject(watermarkHandle = "@creator_prince")
        NichePreset.entries.forEach { preset ->
            val first = service.buildFallbackNicheStoryboard(
                niche = preset.label,
                twist = "Plot Twist",
                language = "Hinglish",
                hook = "",
                clonedSummary = "",
                baseProject = base
            )
            val second = service.buildFallbackNicheStoryboard(
                niche = preset.label,
                twist = "Plot Twist",
                language = "Hinglish",
                hook = "",
                clonedSummary = "",
                baseProject = base
            )
            assertTrue("Shots should be >= 6 for ${preset.label}", first.shots.size >= 6)
            assertEquals("@creator_prince", first.watermarkHandle)
            assertTrue(first.characters.any { it.gender == CharacterGender.FEMALE })
            assertTrue(first.shots.any { it.activity != CharacterActivity.TALKING })
            assertTrue(
                "Consecutive generations for ${preset.label} should vary titles (${first.title} vs ${second.title})",
                first.title != second.title
            )
            assertTrue(
                "Consecutive generations for ${preset.label} should vary opening line",
                first.shots.first().dialogue != second.shots.first().dialogue
            )
        }
    }

    @Test
    fun geminiService_doctorPatientUsesHospitalCastAndNeverRepeatsOfficeClientScript() {
        val service = GeminiInkReelService()
        val base = ReelProject()
        val docRun1 = service.buildFallbackNicheStoryboard(
            niche = "Doctor & Patient",
            twist = "Plot Twist",
            language = "Hinglish",
            hook = "",
            clonedSummary = "",
            baseProject = base
        )
        val docRun2 = service.buildFallbackNicheStoryboard(
            niche = "Doctor & Patient",
            twist = "Plot Twist",
            language = "Hinglish",
            hook = "",
            clonedSummary = "",
            baseProject = base
        )

        assertEquals(RoomSetting.HOSPITAL, docRun1.setting)
        assertEquals("Dr. Mehta", docRun1.characters.first().name)
        assertTrue(docRun1.title != docRun2.title)
        assertFalse(docRun1.shots.any { it.dialogue.contains("client ko mera reel", ignoreCase = true) })
        assertFalse(docRun2.shots.any { it.dialogue.contains("client ko mera reel", ignoreCase = true) })
    }

    @Test
    fun localScriptParser_preservesAllLinesAndDetectsTimeSkipAndFemaleCast() {
        val service = GeminiInkReelService()
        val proj = service.parseScriptLocally(
            scriptText = SampleScripts.hinglishSample,
            settingHint = null,
            language = "Hinglish",
            baseProject = ReelProject()
        )
        // SampleScripts.hinglishSample has 7 non-empty lines including [Day 3]
        assertEquals(7, proj.shots.size)
        assertEquals(CameraFraming.TIME_SKIP_CARD, proj.shots[3].framing)
        assertEquals(SpeakerSlot.SILENT, proj.shots[3].speaker)
        assertEquals(CharacterGender.FEMALE, proj.characters[1].gender)
    }

    @Test
    fun audioEngine_cartoonVoiceFallbackAndMixNeverEmpty() {
        val maleSynth = ProceduralAudioEngine.synthesizeCartoonVoiceFallback(
            text = "Arre bhai homework kahan gaya?",
            gender = CharacterGender.MALE,
            moodPose = MoodPose.ANGRY
        )
        val femaleSynth = ProceduralAudioEngine.synthesizeCartoonVoiceFallback(
            text = "Sir dog ne PDF download kar liya!",
            gender = CharacterGender.FEMALE,
            moodPose = MoodPose.SMUG
        )
        assertTrue(maleSynth.pcmBytes.size >= 48000)
        assertTrue(femaleSynth.pcmBytes.size >= 48000)
        assertTrue(maleSynth.rawDurationMs >= 1100L)

        val tuned = HinglishPhoneticTuner.tune("Kyun nahi aaye?", "", "Hinglish")
        assertTrue(tuned.contains("kyoon", ignoreCase = true))

        val proj = ReelProject()
        val audioMap = proj.shots
            .filter { it.speaker != SpeakerSlot.SILENT && it.dialogue.isNotBlank() }
            .associate { it.shotId to femaleSynth.pcmBytes }
        val mixed = ProceduralAudioEngine.mixReelAudio(proj, audioMap, isMuted = false)
        assertTrue(mixed.size > 48000)
        assertTrue(mixed.any { it != 0.toByte() })

        // Verify spoken voice stays stronger than background SFX on spoken shots
        val loudVoicePcm = ByteArray(24000 * 2) // 1s steady 0.6 amplitude wave
        for (i in 0 until 24000) {
            val v = (kotlin.math.sin(2.0 * kotlin.math.PI * 220.0 * i / 24000.0) * 0.6 * 32767.0).toInt()
            loudVoicePcm[i * 2] = (v and 0xFF).toByte()
            loudVoicePcm[i * 2 + 1] = ((v shr 8) and 0xFF).toByte()
        }
        val singleShotProj = ReelProject(
            bgmVolume = 0.25f,
            enableOutro = false,
            shots = listOf(
                proj.shots.first().copy(
                    shotId = "voice_test",
                    speaker = SpeakerSlot.B,
                    sfx = com.example.model.ComicSfx.SAD_VIOLIN,
                    durationMs = 1500L
                )
            )
        )
        val sfxOnly = ProceduralAudioEngine.mixReelAudio(singleShotProj, emptyMap(), isMuted = false)
        val withVoice = ProceduralAudioEngine.mixReelAudio(singleShotProj, mapOf("voice_test" to loudVoicePcm), isMuted = false)

        fun rmsPeak(pcm: ByteArray, startSample: Int, endSample: Int): Double {
            var sumSq = 0.0
            for (i in startSample until endSample) {
                val lo = pcm[i * 2].toInt() and 0xFF
                val hi = pcm[i * 2 + 1].toInt()
                val s = ((hi shl 8) or lo).toShort().toDouble() / 32768.0
                sumSq += s * s
            }
            return kotlin.math.sqrt(sumSq / (endSample - startSample).coerceAtLeast(1))
        }
        val voiceRms = rmsPeak(withVoice, 1000, 20000)
        val bgOnlyRms = rmsPeak(sfxOnly, 1000, 20000)
        assertTrue("Spoken voice ($voiceRms) must clearly dominate over SFX/BGM ($bgOnlyRms)", voiceRms > bgOnlyRms * 2.0)
    }

    @Test
    fun audioEngine_voiceSpeedResamplingHandlesMinAndMaxBoundsCleanly() {
        val rawSamples = 24000 // 1.0s
        val rawVoicePcm = ByteArray(rawSamples * 2)
        for (i in 0 until rawSamples) {
            val v = (kotlin.math.sin(2.0 * kotlin.math.PI * 190.0 * i / 24000.0) * 0.55 * 32767.0).toInt()
            rawVoicePcm[i * 2] = (v and 0xFF).toByte()
            rawVoicePcm[i * 2 + 1] = ((v shr 8) and 0xFF).toByte()
        }

        listOf(0.85f, 1.30f).forEach { speed ->
            val cast = listOf(
                CharacterProfile(slot = SpeakerSlot.A, name = "Alex", voiceSpeed = speed)
            )
            val proj = ReelProject(
                bgmVolume = 0f,
                enableOutro = false,
                characters = cast,
                shots = listOf(
                    ReelShot(
                        shotId = "spd_$speed",
                        speaker = SpeakerSlot.A,
                        dialogue = "Testing speed",
                        sfx = com.example.model.ComicSfx.NONE,
                        durationMs = 1800L
                    )
                )
            )
            val mixed = ProceduralAudioEngine.mixReelAudio(
                project = proj,
                shotAudioMap = mapOf("spd_$speed" to rawVoicePcm),
                isMuted = false
            )
            assertEquals(((1800.0 / 1000.0) * 24000).toInt() * 2, mixed.size)
            assertTrue("Output must contain valid audio for speed $speed", mixed.any { it != 0.toByte() })

            // Ensure no full-scale +/-1.0 spike/clipping and tail after sped-up speech is silent
            val expectedVoiceEndSample = ((rawSamples / speed).toInt() + 200).coerceAtMost(mixed.size / 2)
            for (i in expectedVoiceEndSample until (mixed.size / 2)) {
                val lo = mixed[i * 2].toInt() and 0xFF
                val hi = mixed[i * 2 + 1].toInt()
                val s = ((hi shl 8) or lo).toShort().toInt()
                assertEquals("Trailing tail at sample $i should be silent", 0, s)
            }
        }
    }

    @Test
    fun stickmanRenderer_rendersAllActivitiesFramingsAndThemesWithoutClipBleed() {
        val width = 360
        val height = 640
        // Oversized parent canvas to ensure bounded renderFrame never paints outside (0..width, 0..height)
        val outerBmp = Bitmap.createBitmap(width + 40, height + 40, Bitmap.Config.ARGB_8888)
        outerBmp.eraseColor(android.graphics.Color.MAGENTA)
        val canvas = Canvas(outerBmp)

        val base = ReelProject(watermarkHandle = "prince_reels")
        CameraFraming.entries.forEachIndexed { idx, framing ->
            val activity = CharacterActivity.entries[idx % CharacterActivity.entries.size]
            val theme = ReelColorTheme.entries[idx % ReelColorTheme.entries.size]
            val setting = RoomSetting.entries[idx % RoomSetting.entries.size]
            val sticker = MemeSticker.entries[idx % MemeSticker.entries.size]
            val shot = base.shots.first().copy(
                framing = framing,
                activity = activity,
                sticker = sticker,
                speaker = when (framing) {
                    CameraFraming.TIME_SKIP_CARD -> SpeakerSlot.SILENT
                    CameraFraming.CLOSE_UP_C -> SpeakerSlot.C
                    CameraFraming.OVER_THE_SHOULDER -> SpeakerSlot.A
                    else -> SpeakerSlot.B
                },
                dialogue = "Long comic line that checks text auto-fitting and speech bubble boundaries cleanly!"
            )
            val testProj = base.copy(
                colorTheme = theme,
                setting = setting,
                shots = listOf(shot)
            )
            canvas.save()
            canvas.clipRect(0, 0, width, height)
            StickmanRenderer.renderFrame(
                canvas = canvas,
                width = width.toFloat(),
                height = height.toFloat(),
                project = testProj,
                playheadMs = 450L,
                lipAmplitude = 0.65f,
                showCaptions = true,
                showSpeechBubbles = true
            )
            canvas.restore()
            // Pixel outside (width, height) must remain untouched MAGENTA
            assertEquals(android.graphics.Color.MAGENTA, outerBmp.getPixel(width + 20, height + 20))
        }

        // Explicitly verify MIDNIGHT_NOIR Over-The-Shoulder + BONK_ENDING with sticker + Outro CTA
        val bonkNoir = base.copy(
            colorTheme = ReelColorTheme.MIDNIGHT_NOIR,
            enableOutro = true,
            outroText = "PART 2? FOLLOW FOR DAILY STICKMAN CHAOS!",
            shots = listOf(
                ReelShot(
                    shotId = "ots_a",
                    speaker = SpeakerSlot.A,
                    framing = CameraFraming.OVER_THE_SHOULDER,
                    dialogue = "Over the shoulder dark noir check!",
                    speechBubble = "CAUGHT YOU!",
                    durationMs = 1500L
                ),
                ReelShot(
                    shotId = "bonk_s",
                    speaker = SpeakerSlot.SILENT,
                    framing = CameraFraming.BONK_ENDING,
                    dialogue = "BONK!",
                    sticker = MemeSticker.MIND_BLOWN,
                    durationMs = 1500L
                )
            )
        )
        listOf(300L, 1800L, bonkNoir.shotsDurationMs + 300L).forEach { playMs ->
            canvas.save()
            canvas.clipRect(0, 0, width, height)
            StickmanRenderer.renderFrame(
                canvas = canvas,
                width = width.toFloat(),
                height = height.toFloat(),
                project = bonkNoir,
                playheadMs = playMs,
                lipAmplitude = 0.5f,
                showCaptions = true,
                showSpeechBubbles = true
            )
            canvas.restore()
            assertEquals(android.graphics.Color.MAGENTA, outerBmp.getPixel(width + 20, height + 20))
        }

        // Also test unbounded canvas clear does NOT overwrite outside (0..width, 0..height)
        outerBmp.eraseColor(android.graphics.Color.MAGENTA)
        StickmanRenderer.renderFrame(
            canvas = canvas,
            width = width.toFloat(),
            height = height.toFloat(),
            project = base,
            playheadMs = 100L,
            lipAmplitude = 0.5f,
            showCaptions = true,
            showSpeechBubbles = true
        )
        assertEquals(android.graphics.Color.MAGENTA, outerBmp.getPixel(width + 20, height + 20))
        outerBmp.recycle()
    }

    @Test
    fun repository_renameReelUpdatesDiskAndRejectsBlankTitle() = runBlocking<Unit> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val repo = ReelRepository(context)
        val project = ReelProject(title = "Original Hostel Chaos")
        val savedId = repo.saveReel(project).getOrThrow()

        val blankRes = repo.renameReel(savedId, "   ")
        assertTrue("Blank title must be rejected", blankRes.isFailure)

        val renameRes = repo.renameReel(savedId, "Updated Hostel Chaos")
        assertTrue(renameRes.isSuccess)
        assertEquals("Updated Hostel Chaos", renameRes.getOrNull()?.title)

        repo.refreshSavedReels()
        val loaded = repo.savedReels.value.firstOrNull { it.id == savedId }
        assertNotNull(loaded)
        assertEquals("Updated Hostel Chaos", loaded?.title)

        repo.deleteReel(savedId)
    }

    @Test
    fun viewModel_savedStateHandleRestoresStateAcrossProcessDeathAndLipSyncClosesAfterAudio() = runBlocking<Unit> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val repo = ReelRepository(context)
        val service = GeminiInkReelService()
        val tts = DeviceTtsSynthesizer(context, initTimeoutMs = 100L, synthTimeoutMs = 100L)
        val handle = SavedStateHandle()

        val vm1 = InkReelViewModel(repo, service, tts, handle)
        vm1.updateCreateMode(1)
        vm1.selectLanguage(ReelLanguage.HINGLISH)
        vm1.updateScriptText("A (Boss): Hello?\nB (Dev): Bye!")
        vm1.updateProjectSettings(title = "Process Death Survivor")
        val targetShotId = vm1.uiState.value.project.shots.last().shotId
        vm1.selectShot(targetShotId)

        // Recreate ViewModel from same SavedStateHandle (simulates process death / rotation recovery)
        val vm2 = InkReelViewModel(repo, service, tts, handle)
        assertEquals("Process Death Survivor", vm2.uiState.value.project.title)
        assertEquals(targetShotId, vm2.uiState.value.selectedShotId)
        assertEquals(1, vm2.uiState.value.draft.modeIndex)
        assertEquals(ReelLanguage.HINGLISH, vm2.uiState.value.draft.selectedLanguage)
        assertTrue(vm2.uiState.value.draft.scriptText.contains("Boss"))

        // Verify lip sync closes cleanly (0f) past actual PCM duration even before shot ends
        vm2.synthesizeAllSpokenShots()
        var wait = 0
        while (wait < 30) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            if (!vm2.uiState.value.isSynthesizingAllVoices) break
            delay(100)
            wait++
        }
        val firstShot = vm2.uiState.value.project.shots.first()
        vm2.startPlayback()
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        // Wait for playback coroutine launch without finishing the shot
        delay(50)
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        val pastAudioMs = (firstShot.durationMs - 40L).coerceAtLeast(0L)
        assertEquals(0f, vm2.currentLipAmplitude(pastAudioMs), 0.0001f)
        vm2.pausePlayback()
    }

    @Test
    fun repositoryAndViewModel_watermarkAndVoiceFallbackNeverLeaveErrorOrStuckProgress() = runBlocking<Unit> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val repo = ReelRepository(context)
        val service = GeminiInkReelService()
        val tts = DeviceTtsSynthesizer(context, initTimeoutMs = 120L, synthTimeoutMs = 120L)
        val vm = InkReelViewModel(repo, service, tts)

        vm.updateWatermarkUsername("prince_comedy")
        assertEquals("prince_comedy", repo.getWatermarkHandle())
        assertEquals("prince_comedy", vm.uiState.value.project.watermarkHandle)

        // Pump Main looper so init warmUp + Dispatchers.Main coroutines advance cleanly
        var waited = 0
        while (waited < 40) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            if (!vm.uiState.value.isSynthesizingAllVoices &&
                vm.uiState.value.project.shots.filter { it.speaker != SpeakerSlot.SILENT && it.dialogue.isNotBlank() }
                    .all { it.audioSourceLabel != "Not Voiced" }
            ) {
                break
            }
            delay(100)
            waited++
        }

        // Trigger explicit full re-voice (also tests cancelling/restarting voiceJob) and wait cleanly
        vm.synthesizeAllSpokenShots()
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        var revoiceWait = 0
        while (revoiceWait < 40) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            if (!vm.uiState.value.isSynthesizingAllVoices &&
                vm.uiState.value.project.shots.filter { it.speaker != SpeakerSlot.SILENT && it.dialogue.isNotBlank() }
                    .all { it.audioSourceLabel != "Not Voiced" }
            ) {
                break
            }
            delay(100)
            revoiceWait++
        }
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        val state = vm.uiState.value
        assertFalse("Voice prep should never get stuck true", state.isSynthesizingAllVoices)
        assertNull("Voice synthesis should never show Device TTS empty audio error", state.errorBanner)
        assertTrue(state.voicingShotIds.isEmpty())

        val voicedShots = state.project.shots.filter { it.speaker != SpeakerSlot.SILENT && it.dialogue.isNotBlank() }
        assertTrue(voicedShots.all { it.audioSourceLabel != "Not Voiced" })

        val saveRes = repo.saveReel(state.project)
        assertTrue(saveRes.isSuccess)
        assertNotNull(saveRes.getOrNull())
    }

    @Test
    fun serialization_roundTripsNewCreatorFieldsAndRemainsBackwardCompatible() {
        val proj = ReelProject(
            title = "Upgrade Serialization Test",
            customBgmName = "lofi_beat.mp3",
            watermarkPosition = WatermarkPosition.SAFE_BOTTOM,
            characters = listOf(
                CharacterProfile(
                    slot = SpeakerSlot.A,
                    name = "Rohan",
                    voicePitchSemitones = 4f,
                    voiceEffect = VoiceEffect.CHIPMUNK,
                    heightScale = 1.12f,
                    outfitColorHex = OutfitColorOption.COBALT.hex
                )
            ),
            shots = listOf(
                ReelShot(
                    shotId = "s_upgrade",
                    speaker = SpeakerSlot.A,
                    roomOverride = RoomSetting.GAMING_ROOM,
                    transition = ShotTransition.ZOOM_PUNCH,
                    cameraShake = true,
                    impactCallout = "PLOT TWIST!",
                    dialogue = "Wait what?!"
                )
            )
        )
        val restored = ReelProject.fromJson(proj.toJson())
        assertEquals("lofi_beat.mp3", restored.customBgmName)
        assertEquals(WatermarkPosition.SAFE_BOTTOM, restored.watermarkPosition)
        assertEquals(4f, restored.characters.first().voicePitchSemitones, 0.01f)
        assertEquals(VoiceEffect.CHIPMUNK, restored.characters.first().voiceEffect)
        assertEquals(1.12f, restored.characters.first().heightScale, 0.01f)
        assertEquals(OutfitColorOption.COBALT.hex, restored.characters.first().outfitColorHex)
        assertEquals(RoomSetting.GAMING_ROOM, restored.shots.first().roomOverride)
        assertEquals(ShotTransition.ZOOM_PUNCH, restored.shots.first().transition)
        assertTrue(restored.shots.first().cameraShake)
        assertEquals("PLOT TWIST!", restored.shots.first().impactCallout)

        // Legacy JSON missing all new keys still parses cleanly with defaults
        val legacyJson = org.json.JSONObject().apply {
            put("title", "Legacy Project")
        }
        val legacyRestored = ReelProject.fromJson(legacyJson)
        assertEquals(WatermarkPosition.BOTTOM_RIGHT, legacyRestored.watermarkPosition)
        assertEquals(VoiceEffect.NONE, legacyRestored.characters.first().voiceEffect)
        assertNull(legacyRestored.shots.first().roomOverride)
    }

    @Test
    fun audioEngine_voiceEffectsPitchShiftAndCustomBgmMixCleanly() {
        val rawSamples = 24000 // 1.0s at 24kHz
        val rawVoicePcm = ByteArray(rawSamples * 2)
        val customBgm = ByteArray(12000 * 2) // 0.5s loopable custom track
        for (i in 0 until rawSamples) {
            val v = (kotlin.math.sin(2.0 * kotlin.math.PI * 210.0 * i / 24000.0) * 0.55 * 32767.0).toInt()
            rawVoicePcm[i * 2] = (v and 0xFF).toByte()
            rawVoicePcm[i * 2 + 1] = ((v shr 8) and 0xFF).toByte()
        }
        for (i in 0 until 12000) {
            val b = (kotlin.math.sin(2.0 * kotlin.math.PI * 110.0 * i / 24000.0) * 0.4 * 32767.0).toInt()
            customBgm[i * 2] = (b and 0xFF).toByte()
            customBgm[i * 2 + 1] = ((b shr 8) and 0xFF).toByte()
        }

        VoiceEffect.entries.forEach { effect ->
            val proj = ReelProject(
                bgmVolume = 0.5f,
                customBgmName = "custom_loop.mp3",
                enableOutro = false,
                characters = listOf(
                    CharacterProfile(
                        slot = SpeakerSlot.A,
                        voicePitchSemitones = if (effect == VoiceEffect.NONE) 3f else 0f,
                        voiceEffect = effect
                    )
                ),
                shots = listOf(
                    ReelShot(
                        shotId = "fx_${effect.name}",
                        speaker = SpeakerSlot.A,
                        dialogue = "Effect check",
                        durationMs = 1500L
                    )
                )
            )
            val mixed = ProceduralAudioEngine.mixReelAudio(
                project = proj,
                shotAudioMap = mapOf("fx_${effect.name}" to rawVoicePcm),
                isMuted = false,
                customBgmPcm = customBgm
            )
            assertEquals(((1500.0 / 1000.0) * 24000).toInt() * 2, mixed.size)
            assertTrue("Effect ${effect.name} should emit non-zero PCM", mixed.any { it != 0.toByte() })
        }
    }

    @Test
    fun stickmanRenderer_rendersTransitionsOutfitsCalloutWatermarksAndSafeZonesWithoutClipBleed() {
        val width = 360
        val height = 640
        val outerBmp = Bitmap.createBitmap(width + 40, height + 40, Bitmap.Config.ARGB_8888)
        outerBmp.eraseColor(android.graphics.Color.MAGENTA)
        val canvas = Canvas(outerBmp)

        WatermarkPosition.entries.forEachIndexed { idx, wmPos ->
            val trans = ShotTransition.entries[idx % ShotTransition.entries.size]
            val proj = ReelProject(
                watermarkHandle = "@inkreel_pro",
                watermarkPosition = wmPos,
                characters = listOf(
                    CharacterProfile(
                        slot = SpeakerSlot.A,
                        heightScale = 1.15f,
                        outfitColorHex = OutfitColorOption.CRIMSON.hex
                    ),
                    CharacterProfile(
                        slot = SpeakerSlot.B,
                        gender = CharacterGender.FEMALE,
                        heightScale = 0.85f,
                        outfitColorHex = OutfitColorOption.EMERALD.hex
                    )
                ),
                shots = listOf(
                    ReelShot(
                        shotId = "tr_$idx",
                        speaker = SpeakerSlot.A,
                        roomOverride = RoomSetting.GAMING_ROOM,
                        transition = trans,
                        cameraShake = true,
                        impactCallout = "BOOM!",
                        dialogue = "Testing safe zones & transitions!"
                    )
                )
            )
            canvas.save()
            canvas.clipRect(0, 0, width, height)
            StickmanRenderer.renderFrame(
                canvas = canvas,
                width = width.toFloat(),
                height = height.toFloat(),
                project = proj,
                playheadMs = 140L, // Inside entry transition + shake window
                lipAmplitude = 0.8f,
                showCaptions = true,
                showSpeechBubbles = true,
                showSafeZoneOverlay = true
            )
            canvas.restore()
            assertEquals(android.graphics.Color.MAGENTA, outerBmp.getPixel(width + 20, height + 20))
        }
        outerBmp.recycle()
    }

    @Test
    fun viewModel_undoRedoReorderDuplicateTemplatesPart2AndSocialRewriteWorkOffline() = runBlocking<Unit> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val repo = ReelRepository(context)
        val service = GeminiInkReelService()
        val tts = DeviceTtsSynthesizer(context, initTimeoutMs = 100L, synthTimeoutMs = 100L)
        val vm = InkReelViewModel(repo, service, tts)

        // 1. Starter Template
        vm.applyStarterTemplate(ReelStarterTemplate.TWO_FRIEND_ROAST)
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertEquals(RoomSetting.COFFEE_SHOP, vm.uiState.value.project.setting)
        assertTrue(vm.uiState.value.canUndo)

        // 2. Undo & Redo
        val titleAfterTemplate = vm.uiState.value.project.title
        vm.undo()
        assertFalse(vm.uiState.value.project.title == titleAfterTemplate && vm.uiState.value.project.setting == RoomSetting.COFFEE_SHOP)
        assertTrue(vm.uiState.value.canRedo)
        vm.redo()
        assertEquals(titleAfterTemplate, vm.uiState.value.project.title)

        // 3. Duplicate & Reorder Shots
        val beforeCount = vm.uiState.value.project.shots.size
        val firstId = vm.uiState.value.project.shots.first().shotId
        vm.duplicateShot(firstId)
        assertEquals(beforeCount + 1, vm.uiState.value.project.shots.size)
        val dupId = vm.uiState.value.project.shots[1].shotId
        vm.moveShotUp(dupId)
        assertEquals(dupId, vm.uiState.value.project.shots.first().shotId)
        vm.moveShotDown(dupId)
        assertEquals(dupId, vm.uiState.value.project.shots[1].shotId)

        // 4. Generate Part 2 Sequel (works offline)
        val prevTitle = vm.uiState.value.project.title
        vm.generatePart2Sequel()
        var wait = 0
        while (wait < 30) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            if (!vm.uiState.value.isGeneratingReel) break
            delay(50)
            wait++
        }
        assertTrue(vm.uiState.value.project.title != prevTitle)
        assertTrue(vm.uiState.value.project.title.contains("Part 2", ignoreCase = true))

        // 5. AI Social Rewrite (offline fallback packs) & apply pack
        vm.rewriteSocialCaptions()
        var rwWait = 0
        while (rwWait < 20) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            if (!vm.uiState.value.isRewritingSocial) break
            delay(50)
            rwWait++
        }
        assertEquals(3, vm.uiState.value.socialSuggestions.size)
        val pack = vm.uiState.value.socialSuggestions.first()
        vm.applySocialSuggestion(pack)
        assertEquals(pack.title, vm.uiState.value.project.title)
        assertEquals(pack.caption, vm.uiState.value.project.socialCaption)
    }

    @Test
    fun wave2_serializationRoundTripsParticlesSfxTimingPipAmbientAndCastPreset() {
        val proj = ReelProject(
            title = "Wave 2 Serialization",
            ambientBed = AmbientBed.CROWD_LAUGH,
            ambientVolume = 0.38f,
            shots = listOf(
                ReelShot(
                    shotId = "w2_shot",
                    speaker = SpeakerSlot.A,
                    dialogue = "Bhai marks dekhe?",
                    sfx = ComicSfx.PUNCHLINE_BOOM,
                    sfxTiming = SfxTiming.PUNCHLINE_END,
                    particles = ParticleOverlay.CONFETTI,
                    reactionCamSlot = SpeakerSlot.C
                )
            )
        )
        val restored = ReelProject.fromJson(proj.toJson())
        assertEquals(AmbientBed.CROWD_LAUGH, restored.ambientBed)
        assertEquals(0.38f, restored.ambientVolume, 0.01f)
        val shot = restored.shots.first()
        assertEquals(SfxTiming.PUNCHLINE_END, shot.sfxTiming)
        assertEquals(ParticleOverlay.CONFETTI, shot.particles)
        assertEquals(SpeakerSlot.C, shot.reactionCamSlot)

        val preset = CastPreset(
            id = "cp_1",
            presetLabel = "Sharma Ji (Firm)",
            profile = CharacterProfile(
                slot = SpeakerSlot.B,
                name = "Sharma Ji",
                role = "Strict Neighbor",
                voiceEffect = VoiceEffect.DEEP_BOSS,
                outfitColorHex = OutfitColorOption.AMBER.hex
            )
        )
        val restoredPreset = CastPreset.fromJson(preset.toJson())
        assertEquals("cp_1", restoredPreset.id)
        assertEquals("Sharma Ji (Firm)", restoredPreset.presetLabel)
        assertEquals(VoiceEffect.DEEP_BOSS, restoredPreset.profile.voiceEffect)
        assertEquals(OutfitColorOption.AMBER.hex, restoredPreset.profile.outfitColorHex)

        // Legacy compatibility: missing Wave 2 fields default cleanly
        val legacy = ReelProject.fromJson(org.json.JSONObject().apply { put("title", "Old") })
        assertEquals(AmbientBed.NONE, legacy.ambientBed)
        assertEquals(SfxTiming.START, legacy.shots.first().sfxTiming)
        assertEquals(ParticleOverlay.NONE, legacy.shots.first().particles)
        assertNull(legacy.shots.first().reactionCamSlot)
    }

    @Test
    fun wave2_audioEngineMixesSfxTimingAndAllAmbientBedsCleanly() {
        val voicePcm = ByteArray(24000 * 2)
        for (i in 0 until 24000) {
            val v = (kotlin.math.sin(2.0 * kotlin.math.PI * 200.0 * i / 24000.0) * 0.5 * 32767.0).toInt()
            voicePcm[i * 2] = (v and 0xFF).toByte()
            voicePcm[i * 2 + 1] = ((v shr 8) and 0xFF).toByte()
        }

        AmbientBed.entries.forEach { bed ->
            val timing = if (bed == AmbientBed.NIGHT_CRICKETS) SfxTiming.PUNCHLINE_END else SfxTiming.MIDDLE
            val proj = ReelProject(
                bgmVolume = 0.0f,
                ambientBed = bed,
                ambientVolume = 0.45f,
                enableOutro = false,
                shots = listOf(
                    ReelShot(
                        shotId = "amb_${bed.name}",
                        speaker = SpeakerSlot.A,
                        dialogue = "Testing ambient bed",
                        sfx = ComicSfx.DRAMATIC_STING,
                        durationMs = 2000L,
                        sfxTiming = timing
                    )
                )
            )
            val mixed = ProceduralAudioEngine.mixReelAudio(
                project = proj,
                shotAudioMap = mapOf("amb_${bed.name}" to voicePcm),
                isMuted = false
            )
            assertEquals(((2000.0 / 1000.0) * 24000).toInt() * 2, mixed.size)
            assertTrue("Mix should contain audible audio for ${bed.name}", mixed.any { it != 0.toByte() })
        }
    }

    @Test
    fun wave2_stickmanRendererDrawsAllParticlesAndReactionPipWithoutClipBleed() {
        val width = 360
        val height = 640
        val outerBmp = Bitmap.createBitmap(width + 40, height + 40, Bitmap.Config.ARGB_8888)
        outerBmp.eraseColor(android.graphics.Color.MAGENTA)
        val canvas = Canvas(outerBmp)

        ParticleOverlay.entries.forEachIndexed { idx, overlay ->
            val proj = ReelProject(
                watermarkPosition = if (idx % 2 == 0) WatermarkPosition.TOP_LEFT else WatermarkPosition.BOTTOM_RIGHT,
                shots = listOf(
                    ReelShot(
                        shotId = "p_$idx",
                        speaker = SpeakerSlot.A,
                        dialogue = "Particle & PiP frame!",
                        particles = overlay,
                        reactionCamSlot = SpeakerSlot.C
                    )
                )
            )
            canvas.save()
            canvas.clipRect(0, 0, width, height)
            StickmanRenderer.renderFrame(
                canvas = canvas,
                width = width.toFloat(),
                height = height.toFloat(),
                project = proj,
                playheadMs = 550L,
                lipAmplitude = 0.6f,
                showCaptions = true,
                showSpeechBubbles = true
            )
            canvas.restore()
            assertEquals(android.graphics.Color.MAGENTA, outerBmp.getPixel(width + 20, height + 20))
        }
        outerBmp.recycle()
    }

    @Test
    fun wave2_comicStripExporterAndCastPresetsAndViralCutAndAutoDubWork() = runBlocking<Unit> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val repo = ReelRepository(context)
        val service = GeminiInkReelService()
        val tts = DeviceTtsSynthesizer(context, initTimeoutMs = 100L, synthTimeoutMs = 100L)

        // 1. ComicStripExporter renders 1080x1350 PNG & returns FileProvider share Uri
        val sampleProj = ReelProject(title = "4 Panel Test Reel")
        val comicRes = ComicStripExporter.saveComicStripPng(context, sampleProj)
        assertTrue("Comic strip export should succeed: ${comicRes.exceptionOrNull()}", comicRes.isSuccess)
        val exp = comicRes.getOrThrow()
        assertTrue("Exported comic strip must be non-empty", exp.fileSizeBytes > 5_000L)
        assertNotNull(exp.shareUri)

        // 2. Repository CastPreset save & delete
        val savedPreset = repo.saveCastPreset(
            CharacterProfile(slot = SpeakerSlot.A, name = "Principal Verma", role = "Principal", voiceEffect = VoiceEffect.DEEP_BOSS)
        ).getOrThrow()
        assertTrue(repo.castPresets.value.any { it.id == savedPreset.id })

        // 3. ViewModel CastPreset apply + Viral Pacing Tightener + Auto-Dub offline
        val vm = InkReelViewModel(repo, service, tts)
        vm.applyCastPresetToSlot(1, savedPreset)
        assertEquals("Principal Verma", vm.uiState.value.project.characters[1].name)
        assertEquals(SpeakerSlot.B, vm.uiState.value.project.characters[1].slot)
        assertEquals(VoiceEffect.DEEP_BOSS, vm.uiState.value.project.characters[1].voiceEffect)

        val longShots = vm.uiState.value.project.shots.map { it.copy(durationMs = 4200L) }
        vm.updateProjectSettings(ambientBed = AmbientBed.CAFE_CHATTER, ambientVolume = 0.3f)
        assertEquals(AmbientBed.CAFE_CHATTER, vm.uiState.value.project.ambientBed)

        // Ensure viral tightener trims oversized shot durations cleanly
        val longTarget = longShots.first()
        vm.updateShot(longTarget)
        val beforeTotal = vm.uiState.value.project.totalDurationMs
        vm.applyViralPacingTightener()
        assertTrue(
            "Viral pacing tightener should reduce duration ($beforeTotal -> ${vm.uiState.value.project.totalDurationMs})",
            vm.uiState.value.project.totalDurationMs < beforeTotal
        )

        // 1-Tap Auto-Dub to Hinglish works cleanly offline
        vm.dubProjectToLanguage(ReelLanguage.HINGLISH)
        var dubWait = 0
        while (dubWait < 40) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            if (!vm.uiState.value.isDubbingLanguage) break
            delay(50)
            dubWait++
        }
        assertFalse(vm.uiState.value.isDubbingLanguage)
        assertTrue(vm.uiState.value.project.shots.isNotEmpty())
        assertTrue(vm.uiState.value.canUndo)

        repo.deleteCastPreset(savedPreset.id)
        assertFalse(repo.castPresets.value.any { it.id == savedPreset.id })
    }

    @Test
    fun bug1_geminiModelsDiscoverySkipsRetiredModelsAndFormatsFriendlyErrors() {
        GeminiModels.clearCachedModels()
        try {
            assertEquals("gemini-3.5-flash-lite", GeminiModels.PRIMARY_TEXT_MODEL)
            assertEquals("gemini-3.5-flash-lite", GeminiModels.PRIMARY_VISION_MODEL)
            assertEquals("gemini-3.8-flash-tts", GeminiModels.PRIMARY_TTS_MODEL)

            val modelsArray = org.json.JSONArray().apply {
                put(org.json.JSONObject().apply {
                    put("name", "models/gemini-2.5-flash-lite")
                    put("supportedGenerationMethods", org.json.JSONArray(listOf("generateContent")))
                })
                put(org.json.JSONObject().apply {
                    put("name", "models/gemini-2.5-flash")
                    put("supportedGenerationMethods", org.json.JSONArray(listOf("generateContent")))
                })
                put(org.json.JSONObject().apply {
                    put("name", "models/gemini-3.5-flash")
                    put("supportedGenerationMethods", org.json.JSONArray(listOf("generateContent")))
                })
                put(org.json.JSONObject().apply {
                    put("name", "models/gemini-3.5-flash-lite")
                    put("supportedGenerationMethods", org.json.JSONArray(listOf("generateContent")))
                })
                put(org.json.JSONObject().apply {
                    put("name", "models/gemini-3.8-flash-tts")
                    put("supportedGenerationMethods", org.json.JSONArray(listOf("generateContent")))
                })
                put(org.json.JSONObject().apply {
                    put("name", "models/text-embedding-004")
                    put("supportedGenerationMethods", org.json.JSONArray(listOf("embedContent")))
                })
            }
            val listJson = org.json.JSONObject().put("models", modelsArray)

            assertEquals(
                "gemini-3.5-flash-lite",
                GeminiModels.pickBestModelFromListModels(listJson, GeminiModels.ModelKind.TEXT)
            )
            assertEquals(
                "gemini-3.5-flash-lite",
                GeminiModels.pickBestModelFromListModels(listJson, GeminiModels.ModelKind.VISION)
            )
            assertEquals(
                "gemini-3.8-flash-tts",
                GeminiModels.pickBestModelFromListModels(listJson, GeminiModels.ModelKind.TTS)
            )

            // Retired model is never selected even when newer text models are excluded
            val fallbackText = GeminiModels.pickBestModelFromListModels(
                listModelsJson = listJson,
                kind = GeminiModels.ModelKind.TEXT,
                excludeModels = setOf("gemini-3.5-flash-lite", "gemini-3.5-flash")
            )
            assertEquals("gemini-2.5-flash", fallbackText)

            // Cached model becomes top priority
            GeminiModels.cacheModel(GeminiModels.ModelKind.TEXT, "gemini-3.5-flash")
            assertEquals("gemini-3.5-flash", GeminiModels.candidatesFor(GeminiModels.ModelKind.TEXT).first())

            // Friendly error mapping strips raw HTTP / JSON details
            val service = GeminiInkReelService()
            val friendly404 = service.toUserFriendlyException(
                GeminiHttpException(404, "{\"error\":{\"code\":404,\"message\":\"models/gemini-2.5-flash-lite is no longer available\"}}")
            )
            assertFalse(friendly404.message!!.contains("{\"error\""))
            assertTrue(friendly404.message!!.contains("404") || friendly404.message!!.contains("unavailable", ignoreCase = true))

            val friendly429 = service.toUserFriendlyException(
                GeminiHttpException(429, "Quota exceeded details...")
            )
            assertFalse(friendly429.message!!.contains("details..."))
            assertTrue(friendly429.message!!.contains("429"))
        } finally {
            GeminiModels.clearCachedModels()
        }
    }

    @Test
    fun bug2_ttsChunkingAndPcmConcatenationAndUnrestrictedDurationWork() {
        val service = GeminiInkReelService()
        val longDialogue = buildString {
            append("Line one: Arre yaar yeh meeting kab khatam hogi, mera chai thanda ho raha hai! ")
            append("Line two: Boss bol rahe hain ek aur quick alignment call chahiye lunch ke baad, ")
            append("aur phir client ko pitch deck bhejna hai shaam ko paanch baje se pehle bilkul ready karke! ")
            append("Line three: Chalo koi baat nahi, coffee peete hain aur phir turbo speed mein sab finish kar dete hain.\n")
            append("Second paragraph line: Phir weekend pe full aaram karenge bina kisi laptop ke!")
        }
        val chunks = service.splitTextIntoTtsChunks(longDialogue, maxChunkChars = 250)
        assertTrue("Long dialogue should split into >= 3 chunks", chunks.size >= 3)
        assertTrue("Every chunk must be <= 250 chars", chunks.all { it.length in 1..250 })
        assertTrue("Order must preserve final line at end", chunks.last().contains("weekend"))

        // Concatenate 3 non-zero 24kHz PCM chunks with 200ms gap
        val chunkA = ByteArray(4800 * 2) { if (it % 2 == 0) 0x20 else 0x05 }
        val chunkB = ByteArray(2400 * 2) { if (it % 2 == 0) 0x40 else 0x0A }
        val chunkC = ByteArray(3600 * 2) { if (it % 2 == 0) 0x60 else 0x0F }
        val gapBytes = ((ProceduralAudioEngine.SAMPLE_RATE * 200L) / 1000L).toInt() * 2
        val joined = ProceduralAudioEngine.concatenatePcm24kMono(listOf(chunkA, chunkB, chunkC), gapMs = 200L)

        assertEquals(chunkA.size + gapBytes + chunkB.size + gapBytes + chunkC.size, joined.size)
        // Verify start, silence gap, middle, and final tail bytes are exact
        assertEquals(chunkA[0], joined[0])
        assertEquals(0.toByte(), joined[chunkA.size])
        assertEquals(0.toByte(), joined[chunkA.size + gapBytes - 1])
        assertEquals(chunkB[0], joined[chunkA.size + gapBytes])
        assertEquals(chunkC.last(), joined.last())

        // Long project (> 30s single shot) is not capped at 30s
        val longProject = ReelProject(
            enableOutro = true,
            outroText = "FOLLOW FOR PART 2!",
            shots = listOf(
                ReelShot(shotId = "long_1", dialogue = "Long monologue", durationMs = 45_000L),
                ReelShot(shotId = "long_2", dialogue = "Final punchline", durationMs = 18_000L)
            )
        )
        assertEquals(2000L, longProject.outroDurationMs)
        assertEquals(65_000L, longProject.totalDurationMs)
        val restored = ReelProject.fromJson(longProject.toJson())
        assertEquals(45_000L, restored.shots[0].durationMs)
        assertEquals(65_000L, restored.totalDurationMs)
    }

    @Test
    fun bug3_eightLineHinglishScriptProducesEightDistinctAnimatedScenesAndOutroFinal2s() {
        val service = GeminiInkReelService()
        val script8 = """
            Amit: Yaar aaj gym jaana hi padega, warna pet bahar aa raha hai!
            Priya: [Room: KITCHEN] [Framing: DESK_SLAM] [Mood: SHOCKED] [Activity: DANCING] [Transition: ZOOM_PUNCH] Pehle yeh samosa toh chhod de!
            Amit: Samosa warm-up tha bro, ab real workout shuru hoga.
            Priya: Kal bhi yahi bola tha aur sofa pe so gaya tha.
            Rohan: Wait guys, main protein shake leke aaya hoon... chocolate flavour!
            Amit: Arre waah, phir toh aaj leg day cancel aur cheat day confirm!
            Priya: Tum dono kabhi nahi sudhroge, main akeli ja rahi hoon!
            Rohan: Ruk ja Priya, hum bhi auto se aa rahe hain gym ke gate tak!
        """.trimIndent()

        val parsed = service.parseScriptLocally(
            scriptText = script8,
            settingHint = null,
            language = "Hinglish",
            baseProject = ReelProject(
                enableOutro = true,
                outroText = "FOLLOW FOR PART 2!"
            )
        )
        assertEquals(8, parsed.shots.size)
        assertEquals(2000L, parsed.outroDurationMs)

        // Manual overrides on shot 2 must be preserved
        val shot2 = parsed.shots[1]
        assertEquals(RoomSetting.KITCHEN, shot2.roomOverride)
        assertEquals(CameraFraming.DESK_SLAM, shot2.framing)
        assertEquals(MoodPose.SHOCKED, shot2.moodPose)
        assertEquals(CharacterActivity.DANCING, shot2.activity)
        assertEquals(ShotTransition.ZOOM_PUNCH, shot2.transition)

        // Every consecutive scene pair must differ in room, framing, activity, or mood
        for (i in 1 until parsed.shots.size) {
            val prev = parsed.shots[i - 1]
            val curr = parsed.shots[i]
            val prevRoom = prev.roomOverride ?: parsed.setting
            val currRoom = curr.roomOverride ?: parsed.setting
            val sameCombo = prevRoom == currRoom &&
                prev.framing == curr.framing &&
                prev.activity == curr.activity &&
                prev.moodPose == curr.moodPose
            assertFalse("Consecutive scenes ${i - 1} and $i should not repeat identical combo", sameCombo)
        }

        // Timeline resolution advances through all 8 scenes and enters outro ONLY in final 2000ms
        var acc = 0L
        for (i in parsed.shots.indices) {
            val mid = acc + parsed.shots[i].durationMs / 2L
            val resolved = StickmanRenderer.resolveShotAt(parsed, mid)
            assertEquals("Playhead $mid ms should resolve to shot $i", i, resolved.shotIndex)
            assertFalse("Playhead $mid ms should not be outro yet", resolved.isOutro)
            acc += parsed.shots[i].durationMs
        }
        val beforeOutro = StickmanRenderer.resolveShotAt(parsed, acc - 100L)
        assertEquals(7, beforeOutro.shotIndex)
        assertFalse(beforeOutro.isOutro)
        val insideOutro = StickmanRenderer.resolveShotAt(parsed, acc + 500L)
        assertTrue(insideOutro.isOutro)

        // Verify final scene keeps animating across timestamps (camera transforms & limb/lip draw coords change)
        val lastStart = acc - parsed.shots.last().durationMs
        val lastEarly = StickmanRenderer.resolveShotAt(parsed, lastStart + 150L)
        val lastLate = StickmanRenderer.resolveShotAt(parsed, acc - 150L)
        assertEquals(7, lastEarly.shotIndex)
        assertEquals(7, lastLate.shotIndex)
        assertTrue("Last scene progress must advance (${lastEarly.progress} -> ${lastLate.progress})", lastLate.progress > lastEarly.progress + 0.5f)

        class TraceCanvas : Canvas(Bitmap.createBitmap(180, 320, Bitmap.Config.ARGB_8888)) {
            val trace = StringBuilder()
            override fun scale(sx: Float, sy: Float) {
                trace.append("S(%.3f,%.3f);".format(sx, sy))
                super.scale(sx, sy)
            }
            override fun translate(dx: Float, dy: Float) {
                trace.append("T(%.2f,%.2f);".format(dx, dy))
                super.translate(dx, dy)
            }
            override fun drawLine(startX: Float, startY: Float, stopX: Float, stopY: Float, paint: android.graphics.Paint) {
                trace.append("L(%.1f,%.1f->%.1f,%.1f);".format(startX, startY, stopX, stopY))
                super.drawLine(startX, startY, stopX, stopY, paint)
            }
            override fun drawCircle(cx: Float, cy: Float, radius: Float, paint: android.graphics.Paint) {
                trace.append("C(%.1f,%.1f,%.1f);".format(cx, cy, radius))
                super.drawCircle(cx, cy, radius, paint)
            }
        }
        val canvasA = TraceCanvas()
        val canvasB = TraceCanvas()
        StickmanRenderer.renderFrame(
            canvas = canvasA,
            width = 180f,
            height = 320f,
            project = parsed,
            playheadMs = lastStart + 150L,
            lipAmplitude = 0.2f,
            showCaptions = true,
            showSpeechBubbles = true
        )
        StickmanRenderer.renderFrame(
            canvas = canvasB,
            width = 180f,
            height = 320f,
            project = parsed,
            playheadMs = acc - 150L,
            lipAmplitude = 0.8f,
            showCaptions = true,
            showSpeechBubbles = true
        )
        assertTrue("Canvas trace must record camera/character draw ops", canvasA.trace.isNotEmpty())
        assertTrue("Last scene draw trace must animate across timestamps", canvasA.trace.toString() != canvasB.trace.toString())

        // Lip-sync envelope stays active on last spoken scene and 0 during final 2s outro
        val voicePcm = ByteArray(24000 * 6 * 2) { idx ->
            if (idx % 2 == 0) 0x30 else 0x18
        }
        val audioMap = parsed.shots.associate { it.shotId to voicePcm }
        val totalFrames = kotlin.math.ceil((parsed.totalDurationMs * 30.0) / 1000.0).toInt()
        val env = Mp4Exporter.buildTimelineLipEnvelope(parsed, audioMap, totalFrames)
        val lastSceneMidFrame = (((lastStart + parsed.shots.last().durationMs / 2L) * 30L) / 1000L).toInt()
        val outroMidFrame = (((acc + 1000L) * 30L) / 1000L).toInt()
        assertTrue("Last spoken scene should have active lip amplitude", env[lastSceneMidFrame] > 0.05f)
        assertEquals(0f, env[outroMidFrame], 0.001f)
    }

    @Test
    fun comedyScriptGenerator_promptBannedTopicsAndLinesJsonWork() {
        ComedyScriptGenerator.clearHistory()
        try {
            assertEquals(24, ComedyScriptGenerator.TOPIC_POOL.size)
            assertEquals(14, ComedyScriptGenerator.CONFLICT_POOL.size)
            assertEquals(20, ComedyScriptGenerator.ACTION_STYLES.size)

            // Record 12 jokes -> rolling banned_topics keeps only the last 10
            for (i in 0 until 12) {
                val topic = ComedyScriptGenerator.TOPIC_POOL[i]
                ComedyScriptGenerator.recordGenerated(topic, "Title $i", "Punchline $i")
            }
            val summaries = ComedyScriptGenerator.getBannedSummaries()
            assertEquals(10, summaries.size)
            assertFalse("Oldest topic Chai tapri should have rolled out", "chai tapri" in ComedyScriptGenerator.getBannedTopics())
            assertTrue("Recent topic should be banned", "salon" in ComedyScriptGenerator.getBannedTopics())

            val base = ReelProject(
                characters = listOf(
                    CharacterProfile(slot = SpeakerSlot.A, name = "Aarav", gender = CharacterGender.MALE),
                    CharacterProfile(slot = SpeakerSlot.B, name = "Priya", gender = CharacterGender.FEMALE)
                )
            )
            val bundle = ComedyScriptGenerator.buildPromptBundle(
                niche = "",
                twist = "",
                language = "Hinglish",
                hook = "",
                clonedBlueprint = null,
                baseProject = base
            )
            assertFalse("Random topic must avoid currently banned topics", bundle.topic.lowercase() in ComedyScriptGenerator.getBannedTopics())
            assertTrue(bundle.conflict in ComedyScriptGenerator.CONFLICT_POOL)
            assertTrue(bundle.actionStyle in ComedyScriptGenerator.ACTION_STYLES)
            assertEquals("general", bundle.niche)
            assertTrue(bundle.charactersSpec.contains("A = Aarav/MALE, B = Priya/FEMALE, C = none"))
            assertTrue(bundle.systemPrompt.contains("3 GHANTE BAAD"))
            assertTrue(bundle.systemPrompt.contains("TIME_SKIP_CARD"))
            assertTrue(bundle.systemPrompt.contains("BONK_ENDING"))
            assertTrue(bundle.systemPrompt.contains("COOKING_CHAI"))
            assertTrue(bundle.systemPrompt.contains("DEADPAN"))
            assertTrue(bundle.userPrompt.contains("primary_action_style: ${bundle.actionStyle}"))

            // Second consecutive pick never repeats the same conflict or primary action style
            val secondConflict = ComedyScriptGenerator.pickRandomConflict()
            assertFalse("Consecutive conflict must differ", secondConflict == bundle.conflict)
            val secondActionStyle = ComedyScriptGenerator.pickActionStyle(bundle.topic)
            assertFalse("Consecutive action style must differ", secondActionStyle == bundle.actionStyle)

            val jsonPayload = org.json.JSONObject().apply {
                put("title", "Chai Bill Twist")
                put("caption", "Bhai 20 rupaye ki chai mein 5 star drama #hinglish #chai #stickman #comedy")
                put("lines", org.json.JSONArray().apply {
                    put(org.json.JSONObject().apply {
                        put("speaker", "A")
                        put("line", "Bhai 500 ka note deke 10 ki chai kaun mangta hai?!")
                        put("bubble", "500 KA NOTE?!")
                        put("room", "KITCHEN")
                        put("camera", "WIDE_DOORWAY")
                        put("activity", "COOKING_CHAI")
                        put("mood", "SHOCKED")
                    })
                    put(org.json.JSONObject().apply {
                        put("speaker", "B")
                        put("line", "Change nahi tha toh poori dukaan khareedne aaya hoon.")
                        put("bubble", "DUKAAN KHARIDU KYA AB")
                        put("room", "KITCHEN") // intentional consecutive duplicate -> should diversify
                        put("camera", "WIDE_DOORWAY") // intentional duplicate -> should diversify
                        put("activity", "COOKING_CHAI") // intentional duplicate -> should diversify
                        put("mood", "SHOCKED") // intentional duplicate -> should diversify
                    })
                    put(org.json.JSONObject().apply {
                        put("speaker", "A")
                        put("line", "Mazaak mat kar, UPI scanner udhar deewar pe laga hai.")
                        put("bubble", "UPI KAR BHAI")
                        put("room", "COFFEE_SHOP")
                        put("camera", "OVER_THE_SHOULDER")
                        put("activity", "WALKING_IN")
                        put("mood", "ANGRY")
                    })
                    put(org.json.JSONObject().apply {
                        put("speaker", "B")
                        put("line", "Acha idea hai, free sample bolke sabko chai baant dete hain!")
                        put("bubble", "FREE CHAI PLAN")
                        put("room", "OFFICE")
                        put("camera", "DESK_SLAM")
                        put("activity", "TALKING")
                        put("mood", "SMUG")
                    })
                    put(org.json.JSONObject().apply {
                        put("speaker", "TITLE")
                        put("line", "3 GHANTE BAAD")
                        put("bubble", "")
                        put("room", "STREET_PARK")
                        put("camera", "TIME_SKIP_CARD")
                        put("activity", "TALKING")
                        put("mood", "NEUTRAL")
                    })
                    put(org.json.JSONObject().apply {
                        put("speaker", "B")
                        put("line", "Scanner scan kiya par balance dekh ke phone hang ho gaya!")
                        put("bubble", "BALANCE ZERO!")
                        put("room", "COFFEE_SHOP")
                        put("camera", "PROP_DROP")
                        put("activity", "GAMING_PHONE")
                        put("mood", "SWEATING")
                    })
                    put(org.json.JSONObject().apply {
                        put("speaker", "A")
                        put("line", "Chhod rehne de, kal se free chai subscription le lena.")
                        put("bubble", "FREE SUBSCRIPTION!")
                        put("room", "STREET_PARK")
                        put("camera", "MEDIUM_TWO_SHOT")
                        put("activity", "CLAPPING_CHEERING")
                        put("mood", "DEADPAN")
                    })
                    put(org.json.JSONObject().apply {
                        put("speaker", "B")
                        put("line", "Plot twist — main hi iss dukaan ka naya malik hoon!")
                        put("bubble", "NAYA MALIK!")
                        put("room", "LIVING_ROOM")
                        put("camera", "BONK_ENDING")
                        put("activity", "DANCING")
                        put("mood", "SMUG")
                    })
                })
            }

            val parsed = ComedyScriptGenerator.parseComedyScriptJson(
                jsonText = jsonPayload.toString(),
                niche = "general",
                twist = "Jugaad gone wrong",
                language = "Hinglish",
                clonedSummary = "",
                baseProject = base,
                fallbackTopic = "Chai tapri"
            )
            assertEquals("Chai Bill Twist", parsed.title)
            assertEquals(8, parsed.shots.size)
            assertEquals(CameraFraming.TIME_SKIP_CARD, parsed.shots[4].framing)
            assertEquals(SpeakerSlot.SILENT, parsed.shots[4].speaker)
            assertEquals("3 GHANTE BAAD", parsed.shots[4].transitionCard)
            assertTrue("Bubble must be max 4 words", parsed.shots.all {
                it.speechBubble.trim().split(Regex("\\s+")).filter { w -> w.isNotBlank() }.size <= 4
            })
            assertEquals("NAYA MALIK!", parsed.shots.last().speechBubble)
            assertEquals(4, parsed.hashtags.size)

            // Verify consecutive spoken scenes differ in room, camera, activity, and mood + >= 4 rooms
            val rooms = parsed.shots.mapNotNull { it.roomOverride }
            assertTrue("Must use at least 4 distinct rooms (got ${rooms.distinct().size})", rooms.distinct().size >= 4)
            val spoken = parsed.shots.filter { it.framing != CameraFraming.TIME_SKIP_CARD }
            for (i in 1 until spoken.size) {
                assertFalse("Consecutive spoken scenes must not repeat room", spoken[i].roomOverride == spoken[i - 1].roomOverride)
                assertFalse("Consecutive spoken scenes must not repeat camera", spoken[i].framing == spoken[i - 1].framing)
                assertFalse("Consecutive spoken scenes must not repeat activity", spoken[i].activity == spoken[i - 1].activity)
                assertFalse("Consecutive spoken scenes must not repeat mood", spoken[i].moodPose == spoken[i - 1].moodPose)
            }
            assertTrue("Must not be plain standing-and-talking only", spoken.count { it.activity == CharacterActivity.TALKING } <= 1)
            assertTrue("Generated topic added to banned history", "chai tapri" in ComedyScriptGenerator.getBannedTopics())
        } finally {
            ComedyScriptGenerator.clearHistory()
        }
    }
}
