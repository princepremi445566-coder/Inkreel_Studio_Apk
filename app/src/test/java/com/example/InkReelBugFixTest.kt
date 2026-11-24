package com.example

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import org.robolectric.Shadows
import com.example.ai.GeminiInkReelService
import com.example.audio.DeviceTtsSynthesizer
import com.example.audio.HinglishPhoneticTuner
import com.example.audio.ProceduralAudioEngine
import com.example.canvas.StickmanRenderer
import com.example.data.ReelRepository
import com.example.export.ComicStripExporter
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
}
