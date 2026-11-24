package com.example.canvas

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import com.example.model.CameraFraming
import com.example.model.CaptionFontStyle
import com.example.model.CaptionPosition
import com.example.model.CaptionStyle
import com.example.model.CharacterActivity
import com.example.model.CharacterGender
import com.example.model.CharacterProfile
import com.example.model.CharacterProp
import com.example.model.FacialHair
import com.example.model.HairStyle
import com.example.model.MemeSticker
import com.example.model.MoodPose
import com.example.model.ParticleOverlay
import com.example.model.ReelColorTheme
import com.example.model.ReelProject
import com.example.model.ReelShot
import com.example.model.ShotTransition
import com.example.model.SpeakerSlot
import com.example.model.WatermarkPosition
import java.util.EnumMap
import java.util.HashMap
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

object StickmanRenderer {

    private val boldDefaultTf: Typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    private val boldSansTf: Typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
    private val comicPunchTf: Typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD_ITALIC)
    private val monoBoldTf: Typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    private val serifBoldTf: Typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)

    private data class ThemeColors(
        val wallColor: Int,
        val floorColor: Int,
        val inkColor: Int,
        val accentColor: Int,
        val whiteFillColor: Int,
        val celShadeColor: Int,
        val shadowColor: Int,
        val bubbleStrokeColor: Int
    )

    private val themeColorCache = EnumMap<ReelColorTheme, ThemeColors>(ReelColorTheme::class.java)

    private fun colorsFor(theme: ReelColorTheme): ThemeColors {
        return themeColorCache[theme] ?: ThemeColors(
            wallColor = Color.parseColor(theme.wallHex),
            floorColor = Color.parseColor(theme.floorHex),
            inkColor = Color.parseColor(theme.inkHex),
            accentColor = Color.parseColor(theme.accentHex),
            whiteFillColor = if (theme.isDarkStage) Color.parseColor("#27272A") else Color.WHITE,
            celShadeColor = if (theme.isDarkStage) Color.parseColor("#3F3F46") else Color.parseColor("#E4E4E7"),
            shadowColor = if (theme.isDarkStage) Color.parseColor("#44000000") else Color.parseColor("#22000000"),
            bubbleStrokeColor = if (theme.isDarkStage) Color.parseColor(theme.accentHex) else Color.parseColor("#09090B")
        ).also { themeColorCache[theme] = it }
    }

    private class StickPaints {
        val inkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        val inkFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        val whiteFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        val shadowFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        val celShadeFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        val sweatFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#38BDF8")
            style = Paint.Style.FILL
        }
        val angerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
        }
        val accentFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        val captionBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#DD09090B")
            style = Paint.Style.FILL
        }
        val popWordBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.FILL
        }
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textAlign = Paint.Align.CENTER
            typeface = boldDefaultTf
        }
        val splitLeftBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        val splitRightBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        val shadesPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#09090B")
            style = Paint.Style.FILL
        }
        val glintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
        }
        val goldFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#FACC15")
            style = Paint.Style.FILL
        }
        val darkCardBg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#09090B")
            style = Paint.Style.FILL
        }
        val bannerBg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#EE09090B")
            style = Paint.Style.FILL
        }
        val pureWhiteFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.FILL
        }
        val silhouetteFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#18181B")
            style = Paint.Style.FILL
        }
        val silhouetteRim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#3F3F46")
            style = Paint.Style.STROKE
        }
        val watermarkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.RIGHT
            typeface = monoBoldTf
        }
        val outfitFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        val flashOverlayPaint = Paint().apply { style = Paint.Style.FILL; color = Color.WHITE }
        val safeZoneBgPaint = Paint().apply {
            style = Paint.Style.FILL
            color = Color.argb(46, 239, 68, 68)
        }
        val safeZoneBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = Color.argb(190, 239, 68, 68)
            pathEffect = DashPathEffect(floatArrayOf(12f, 8f), 0f)
        }
        val particleFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        val particleStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
        }
        val pathA = Path()
        val pathB = Path()
        val rect = RectF()

        // Zero-allocation scratch buffers for 30fps onDraw
        var resolvedShot: ReelShot = ReelShot()
        var resolvedProgress: Float = 0f
        var resolvedIsOutro: Boolean = false

        val wordStarts = IntArray(128)
        val wordEnds = IntArray(128)
        val chunkWidths = FloatArray(4)
        val lineStarts = IntArray(4)
        val lineEnds = IntArray(4)
        val upperScratch = StringBuilder(96)
        val watermarkScratch = StringBuilder(48)
    }

    private val outfitColorCache = HashMap<String, Int>()

    private fun parseOutfitColor(hex: String): Int {
        if (hex.isBlank()) return 0
        outfitColorCache[hex]?.let { return it }
        val parsed = runCatching { Color.parseColor(hex) }.getOrDefault(0)
        outfitColorCache[hex] = parsed
        return parsed
    }

    private val threadPaints = object : ThreadLocal<StickPaints>() {
        override fun initialValue(): StickPaints = StickPaints()
    }

    private val defaultCharA = CharacterProfile(slot = SpeakerSlot.A)
    private val defaultCharB = CharacterProfile(slot = SpeakerSlot.B)
    private val emptyShot = ReelShot()

    private val colorLabelMuted = Color.parseColor("#A1A1AA")
    private val colorHandleLight = Color.parseColor("#E4E4E7")
    private val colorBubbleText = Color.parseColor("#09090B")
    private val colorWatermarkLight = Color.parseColor("#88FFFFFF")
    private val colorWatermarkDark = Color.parseColor("#8818181B")

    data class ActiveShotState(
        val shotIndex: Int,
        val shot: ReelShot,
        val localTimeMs: Long,
        val progress: Float,
        val isOutro: Boolean = false
    )

    fun resolveShotAt(project: ReelProject, playheadMs: Long): ActiveShotState {
        val shots = project.shots
        if (shots.isEmpty()) {
            return ActiveShotState(0, emptyShot, 0L, 0f)
        }
        val shotsTotal = project.shotsDurationMs
        if (project.outroDurationMs > 0L && playheadMs >= shotsTotal) {
            val local = (playheadMs - shotsTotal).coerceIn(0L, project.outroDurationMs)
            val prog = (local.toFloat() / project.outroDurationMs.toFloat()).coerceIn(0f, 1f)
            return ActiveShotState(shots.lastIndex, shots.last(), local, prog, isOutro = true)
        }
        var acc = 0L
        for (i in shots.indices) {
            val s = shots[i]
            val end = acc + s.durationMs
            if (playheadMs < end || i == shots.lastIndex) {
                val local = (playheadMs - acc).coerceIn(0L, s.durationMs)
                val prog = (local.toFloat() / s.durationMs.coerceAtLeast(1L).toFloat()).coerceIn(0f, 1f)
                return ActiveShotState(i, s, local, prog)
            }
            acc = end
        }
        val last = shots.last()
        return ActiveShotState(shots.lastIndex, last, last.durationMs, 1f)
    }

    private fun resolveShotIntoScratch(project: ReelProject, playheadMs: Long, p: StickPaints) {
        val shots = project.shots
        if (shots.isEmpty()) {
            p.resolvedShot = emptyShot
            p.resolvedProgress = 0f
            p.resolvedIsOutro = false
            return
        }
        val shotsTotal = project.shotsDurationMs
        if (project.outroDurationMs > 0L && playheadMs >= shotsTotal) {
            val local = (playheadMs - shotsTotal).coerceIn(0L, project.outroDurationMs)
            p.resolvedShot = shots.last()
            p.resolvedProgress = (local.toFloat() / project.outroDurationMs.toFloat()).coerceIn(0f, 1f)
            p.resolvedIsOutro = true
            return
        }
        var acc = 0L
        for (i in shots.indices) {
            val s = shots[i]
            val end = acc + s.durationMs
            if (playheadMs < end || i == shots.lastIndex) {
                val local = (playheadMs - acc).coerceIn(0L, s.durationMs)
                p.resolvedShot = s
                p.resolvedProgress = (local.toFloat() / s.durationMs.coerceAtLeast(1L).toFloat()).coerceIn(0f, 1f)
                p.resolvedIsOutro = false
                return
            }
            acc = end
        }
        val last = shots.last()
        p.resolvedShot = last
        p.resolvedProgress = 1f
        p.resolvedIsOutro = false
    }

    private fun applyThemePaints(p: StickPaints, colors: ThemeColors) {
        p.inkPaint.color = colors.inkColor
        p.inkFill.color = colors.inkColor
        p.whiteFill.color = colors.whiteFillColor
        p.celShadeFill.color = colors.celShadeColor
        p.shadowFill.color = colors.shadowColor
        p.angerPaint.color = colors.accentColor
        p.accentFill.color = colors.accentColor
    }

    fun renderFrame(
        canvas: Canvas,
        width: Float,
        height: Float,
        project: ReelProject,
        playheadMs: Long,
        lipAmplitude: Float,
        showCaptions: Boolean,
        showSpeechBubbles: Boolean,
        showSafeZoneOverlay: Boolean = false
    ) {
        if (width <= 1f || height <= 1f) return
        val p = threadPaints.get()!!
        val unit = width / 720f
        val theme = project.colorTheme
        val colors = colorsFor(theme)
        applyThemePaints(p, colors)

        resolveShotIntoScratch(project, playheadMs, p)
        val shot = p.resolvedShot
        val prog = p.resolvedProgress
        val timeSec = playheadMs / 1000f
        val hasHookBanner = project.introHookBanner.isNotBlank()

        // Animated Outro Follow/Subscribe Card
        if (p.resolvedIsOutro) {
            drawOutroCard(canvas, p, colors, width, height, unit, project.outroText, project.watermarkHandle, prog)
            if (showSafeZoneOverlay) drawSafeZoneOverlay(canvas, p, width, height, unit)
            return
        }

        // Full-frame Time-Skip Card
        if (shot.framing == CameraFraming.TIME_SKIP_CARD) {
            val cardText = shot.transitionCard.ifBlank { "NEXT DAY" }
            drawTimeSkipCard(canvas, p, colors, width, height, unit, cardText, prog)
            drawWatermark(canvas, p, width, height, unit, project.watermarkHandle, project.watermarkPosition, darkBg = true, hasHookBanner = hasHookBanner)
            if (showSafeZoneOverlay) drawSafeZoneOverlay(canvas, p, width, height, unit)
            return
        }

        // Bounded stage clear inside (0, 0, width, height) so Compose RecordingCanvas never bleeds outside viewport
        p.splitLeftBg.color = colors.wallColor
        canvas.drawRect(0f, 0f, width, height, p.splitLeftBg)
        canvas.save()
        canvas.clipRect(0f, 0f, width, height)

        // Camera framing, transitions, & screen shake
        val transFrac = (prog / 0.22f).coerceIn(0f, 1f)
        val whipOffsetX = if (shot.transition == ShotTransition.WHIP_PAN && transFrac < 1f) {
            val inv = 1f - transFrac
            inv * inv * width * 0.44f
        } else 0f

        val shakeX: Float
        val shakeY: Float
        if (shot.framing == CameraFraming.DESK_SLAM && prog < 0.45f) {
            val decay = (1f - prog / 0.45f)
            shakeX = sin(timeSec * 75f) * 16f * unit * decay + whipOffsetX
            shakeY = cos(timeSec * 68f) * 14f * unit * decay
        } else if (shot.cameraShake) {
            shakeX = sin(timeSec * 62f) * 9f * unit + whipOffsetX
            shakeY = cos(timeSec * 57f) * 8f * unit
        } else {
            shakeX = whipOffsetX
            shakeY = 0f
        }

        val punchZoomBoost = if (shot.transition == ShotTransition.ZOOM_PUNCH && transFrac < 1f) {
            1f + (1f - transFrac) * 0.24f
        } else 1f

        val baseZoom: Float
        val focusX: Float
        val focusY: Float
        when (shot.framing) {
            CameraFraming.CLOSE_UP_A -> {
                baseZoom = 1.72f
                focusX = width * 0.30f
                focusY = height * 0.47f
            }
            CameraFraming.CLOSE_UP_B -> {
                baseZoom = 1.72f
                focusX = width * 0.70f
                focusY = height * 0.47f
            }
            CameraFraming.CLOSE_UP_C -> {
                baseZoom = 1.72f
                focusX = width * 0.50f
                focusY = height * 0.46f
            }
            CameraFraming.OVER_THE_SHOULDER -> {
                baseZoom = 1.36f
                focusX = if (shot.speaker == SpeakerSlot.A) width * 0.34f else width * 0.66f
                focusY = height * 0.50f
            }
            CameraFraming.DESK_SLAM -> {
                baseZoom = 1.22f
                focusX = width * 0.50f
                focusY = height * 0.54f
            }
            else -> {
                baseZoom = 1.0f
                focusX = width * 0.50f
                focusY = height * 0.50f
            }
        }
        val zoom = baseZoom * punchZoomBoost

        canvas.translate(width * 0.5f + shakeX, height * 0.5f + shakeY)
        canvas.scale(zoom, zoom)
        canvas.translate(-focusX, -focusY)

        val doorOpen = if (shot.framing == CameraFraming.WIDE_DOORWAY) {
            (prog * 2.5f).coerceIn(0f, 1f)
        } else {
            0f
        }

        val charA = project.characters.getOrNull(0) ?: defaultCharA
        val charB = project.characters.getOrNull(1) ?: defaultCharB
        val charC = project.characters.getOrNull(2)
        val floorY = height * 0.72f

        val runX = if (shot.framing == CameraFraming.SLAPSTICK_CHASE) {
            (-0.15f + prog * 1.3f).coerceIn(0.08f, 0.92f)
        } else {
            0.5f
        }
        val walkShift = if (shot.framing == CameraFraming.WIDE_DOORWAY) {
            0.14f + (prog * 1.4f).coerceAtMost(1f) * 0.17f
        } else {
            0.30f
        }

        if (shot.framing == CameraFraming.SPLIT_PHONE_CALL) {
            drawSplitPhoneCallScene(
                canvas = canvas,
                p = p,
                colors = colors,
                width = width,
                height = height,
                floorY = floorY,
                unit = unit,
                charA = charA,
                charB = charB,
                shot = shot,
                lipAmp = lipAmplitude,
                timeSec = timeSec
            )
        } else {
            val activeSetting = shot.roomOverride ?: project.setting
            RoomBackgroundRenderer.drawRoom(canvas, width, height, activeSetting, doorOpen, timeSec, theme)

            when (shot.framing) {
                CameraFraming.SLAPSTICK_CHASE -> {
                    val chaseLeadX = -0.15f + prog * 1.3f
                    drawStickman(
                        canvas = canvas,
                        p = p,
                        cx = width * chaseLeadX,
                        footY = floorY + 24f * unit,
                        rawScale = 1.0f * unit,
                        profile = charB,
                        mood = MoodPose.NERVOUS,
                        isSpeaking = shot.speaker == SpeakerSlot.B,
                        lipAmp = lipAmplitude,
                        timeSec = timeSec,
                        facingRight = true,
                        running = true
                    )
                    drawStickman(
                        canvas = canvas,
                        p = p,
                        cx = width * (chaseLeadX - 0.24f),
                        footY = floorY + 24f * unit,
                        rawScale = 1.04f * unit,
                        profile = charA,
                        mood = MoodPose.FURIOUS,
                        isSpeaking = shot.speaker == SpeakerSlot.A,
                        lipAmp = lipAmplitude,
                        timeSec = timeSec,
                        facingRight = true,
                        running = true
                    )
                    drawSpeedLines(canvas, p, width, height, unit, timeSec)
                }
                CameraFraming.BONK_ENDING -> {
                    drawBonkDustCloud(canvas, p, colors, width * 0.50f, floorY - 80f * unit, unit, prog)
                }
                else -> {
                    val ax = width * walkShift
                    val bx = width * 0.70f
                    val deskMode = shot.framing == CameraFraming.SITTING_AT_DESK || shot.framing == CameraFraming.DESK_SLAM

                    if ((shot.speaker == SpeakerSlot.C || shot.framing == CameraFraming.CLOSE_UP_C) && charC != null) {
                        drawStickman(
                            canvas = canvas,
                            p = p,
                            cx = width * 0.50f,
                            footY = floorY + 18f * unit,
                            rawScale = 1.02f * unit,
                            profile = charC,
                            mood = shot.moodPose,
                            activity = shot.activity,
                            isSpeaking = shot.speaker == SpeakerSlot.C,
                            lipAmp = lipAmplitude,
                            timeSec = timeSec,
                            facingRight = false
                        )
                    } else {
                        val moodA = if (shot.speaker == SpeakerSlot.A || shot.framing == CameraFraming.DESK_SLAM) shot.moodPose else MoodPose.NEUTRAL
                        val moodB = if (shot.speaker == SpeakerSlot.B || shot.framing == CameraFraming.PROP_DROP) shot.moodPose else MoodPose.NEUTRAL
                        val actA = if (shot.speaker == SpeakerSlot.A || shot.speaker == SpeakerSlot.SILENT) shot.activity else CharacterActivity.TALKING
                        val actB = if (shot.speaker == SpeakerSlot.B) shot.activity else CharacterActivity.TALKING

                        drawStickman(
                            canvas = canvas,
                            p = p,
                            cx = ax,
                            footY = floorY + 20f * unit,
                            rawScale = 1.03f * unit,
                            profile = charA,
                            mood = moodA,
                            activity = actA,
                            isSpeaking = shot.speaker == SpeakerSlot.A,
                            lipAmp = lipAmplitude,
                            timeSec = timeSec,
                            facingRight = true,
                            propDropped = shot.framing == CameraFraming.PROP_DROP && shot.speaker == SpeakerSlot.A,
                            propDropProgress = prog,
                            deskSlam = shot.framing == CameraFraming.DESK_SLAM
                        )

                        drawStickman(
                            canvas = canvas,
                            p = p,
                            cx = bx,
                            footY = floorY + 20f * unit,
                            rawScale = 1.0f * unit,
                            profile = charB,
                            mood = moodB,
                            activity = actB,
                            isSpeaking = shot.speaker == SpeakerSlot.B,
                            lipAmp = lipAmplitude,
                            timeSec = timeSec,
                            facingRight = false,
                            propDropped = shot.framing == CameraFraming.PROP_DROP && shot.speaker == SpeakerSlot.B,
                            propDropProgress = prog
                        )
                    }

                    if (deskMode) {
                        drawForegroundDesk(canvas, p, width, floorY, unit, shot.framing == CameraFraming.DESK_SLAM, prog)
                    }

                    if (shot.framing == CameraFraming.OVER_THE_SHOULDER) {
                        val onLeftSide = shot.speaker != SpeakerSlot.A
                        val shoulderX = if (onLeftSide) width * 0.14f else width * 0.86f
                        val headSilX = if (onLeftSide) width * 0.16f else width * 0.84f
                        canvas.drawCircle(shoulderX, height * 0.69f, 106f * unit, p.silhouetteFill)
                        canvas.drawCircle(headSilX, height * 0.52f, 62f * unit, p.silhouetteFill)
                        if (theme.isDarkStage) {
                            p.silhouetteRim.strokeWidth = 3f * unit
                            canvas.drawCircle(shoulderX, height * 0.69f, 106f * unit, p.silhouetteRim)
                            canvas.drawCircle(headSilX, height * 0.52f, 62f * unit, p.silhouetteRim)
                        }
                    }
                }
            }
        }

        // Meme Sticker Overlay on active speaker (world-space)
        val hasCenterCharC = (shot.speaker == SpeakerSlot.C || shot.framing == CameraFraming.CLOSE_UP_C) && charC != null
        val worldSpeakerX = when {
            shot.framing == CameraFraming.BONK_ENDING -> width * 0.50f
            shot.framing == CameraFraming.SPLIT_PHONE_CALL && shot.speaker == SpeakerSlot.A -> width * 0.26f
            shot.framing == CameraFraming.SPLIT_PHONE_CALL && shot.speaker == SpeakerSlot.B -> width * 0.74f
            shot.framing == CameraFraming.SLAPSTICK_CHASE -> width * runX
            hasCenterCharC -> width * 0.50f
            shot.speaker == SpeakerSlot.A -> width * walkShift
            shot.speaker == SpeakerSlot.B -> width * 0.70f
            else -> width * 0.50f
        }
        val stickerHeadY = if (shot.framing == CameraFraming.BONK_ENDING) floorY - 125f * unit else floorY - 300f * unit
        if (shot.sticker != MemeSticker.NONE) {
            drawMemeSticker(canvas, p, colors, worldSpeakerX, stickerHeadY, unit, shot.sticker, timeSec)
        }

        canvas.restore()

        // Screen-space Comic Flash transition
        if (shot.transition == ShotTransition.FLASH && prog < 0.22f) {
            val alpha = ((1f - prog / 0.22f) * 215f).toInt().coerceIn(0, 220)
            p.flashOverlayPaint.alpha = alpha
            canvas.drawRect(0f, 0f, width, height, p.flashOverlayPaint)
        }

        // Screen-space Intro Hook Banner (top viral hook strip)
        if (hasHookBanner) {
            drawIntroHookBanner(canvas, p, colors, width, unit, project.introHookBanner)
        }

        // Screen-space Speech Bubble (world-to-screen mapped & clamped so Close-Ups never clip off-screen)
        val showBubbleHere = showSpeechBubbles && shot.speechBubble.isNotBlank() &&
            !(shot.framing == CameraFraming.BONK_ENDING && shot.speechBubble.equals("BONK!", ignoreCase = true))
        if (showBubbleHere) {
            val bubbleWorldY = floorY - 415f * unit
            val rawScreenX = (worldSpeakerX - focusX) * zoom + width * 0.5f + shakeX
            val rawScreenY = (bubbleWorldY - focusY) * zoom + height * 0.5f + shakeY
            val minBubbleY = if (hasHookBanner) 128f * unit else 74f * unit
            val maxBubbleY = height * 0.44f
            val clampedScreenY = rawScreenY.coerceIn(minBubbleY, maxBubbleY)
            drawSpeechBubble(canvas, p, colors, rawScreenX, clampedScreenY, width, unit, shot.speechBubble)
        }

        // Screen-space Weather / Mood Particles (0 allocations, strictly clipped to viewport)
        if (shot.particles != ParticleOverlay.NONE) {
            canvas.save()
            canvas.clipRect(0f, 0f, width, height)
            drawParticles(canvas, p, colors, width, height, unit, shot.particles, timeSec)
            canvas.restore()
        }

        // Screen-space Picture-in-Picture (PiP) Reaction Cam
        val reactSlot = shot.reactionCamSlot
        if (reactSlot != null && reactSlot != SpeakerSlot.SILENT) {
            val reactChar = project.characterFor(reactSlot)
            if (reactChar != null) {
                drawReactionPipCam(
                    canvas = canvas,
                    p = p,
                    colors = colors,
                    w = width,
                    h = height,
                    unit = unit,
                    profile = reactChar,
                    isSpeaking = shot.speaker == reactSlot,
                    lipAmp = lipAmplitude,
                    timeSec = timeSec,
                    watermarkPosition = project.watermarkPosition,
                    hasHookBanner = hasHookBanner
                )
            }
        }

        // Screen-space Custom Comic Impact Callout Badge
        if (shot.impactCallout.isNotBlank()) {
            val calloutY = if (hasHookBanner) height * 0.165f else height * 0.11f
            drawImpactCallout(canvas, p, colors, width * 0.5f, calloutY, width, unit, shot.impactCallout, prog)
        }

        // Screen-space Captions & Watermark
        val showCaptionsHere = showCaptions && shot.dialogue.isNotBlank() &&
            !(shot.framing == CameraFraming.BONK_ENDING && shot.dialogue.equals("BONK!", ignoreCase = true))
        if (showCaptionsHere) {
            drawCaptions(
                canvas = canvas,
                p = p,
                colors = colors,
                w = width,
                h = height,
                unit = unit,
                text = shot.dialogue,
                progress = prog,
                style = project.captionStyle,
                fontStyle = project.captionFontStyle,
                position = project.captionPosition
            )
        }
        drawWatermark(canvas, p, width, height, unit, project.watermarkHandle, project.watermarkPosition, darkBg = theme.isDarkStage, hasHookBanner = hasHookBanner)
        if (showSafeZoneOverlay) {
            drawSafeZoneOverlay(canvas, p, width, height, unit)
        }
    }

    private fun drawSplitPhoneCallScene(
        canvas: Canvas,
        p: StickPaints,
        colors: ThemeColors,
        width: Float,
        height: Float,
        floorY: Float,
        unit: Float,
        charA: CharacterProfile,
        charB: CharacterProfile,
        shot: ReelShot,
        lipAmp: Float,
        timeSec: Float
    ) {
        p.splitLeftBg.color = colors.wallColor
        p.splitRightBg.color = colors.floorColor
        canvas.drawRect(0f, 0f, width * 0.5f, height, p.splitLeftBg)
        canvas.drawRect(width * 0.5f, 0f, width, height, p.splitRightBg)

        canvas.drawRect(0f, floorY, width * 0.5f, height, p.splitRightBg)
        p.inkPaint.strokeWidth = 4f * unit
        canvas.drawLine(0f, floorY, width, floorY, p.inkPaint)

        // Left Character A on Phone
        drawStickman(
            canvas = canvas,
            p = p,
            cx = width * 0.26f,
            footY = floorY + 20f * unit,
            rawScale = 0.96f * unit,
            profile = charA,
            mood = if (shot.speaker == SpeakerSlot.A) shot.moodPose else MoodPose.NEUTRAL,
            isSpeaking = shot.speaker == SpeakerSlot.A,
            lipAmp = lipAmp,
            timeSec = timeSec,
            facingRight = true,
            holdingPhoneToEar = true
        )

        // Right Character B on Phone
        drawStickman(
            canvas = canvas,
            p = p,
            cx = width * 0.74f,
            footY = floorY + 20f * unit,
            rawScale = 0.96f * unit,
            profile = charB,
            mood = if (shot.speaker == SpeakerSlot.B) shot.moodPose else MoodPose.NEUTRAL,
            isSpeaking = shot.speaker == SpeakerSlot.B,
            lipAmp = lipAmp,
            timeSec = timeSec,
            facingRight = false,
            holdingPhoneToEar = true
        )

        // Center split divider + live call badge
        p.inkPaint.strokeWidth = 7f * unit
        canvas.drawLine(width * 0.5f, 0f, width * 0.5f, height, p.inkPaint)

        val badgeRect = p.rect.apply {
            set(width * 0.5f - 68f * unit, height * 0.14f, width * 0.5f + 68f * unit, height * 0.14f + 40f * unit)
        }
        canvas.drawRoundRect(badgeRect, 20f * unit, 20f * unit, p.accentFill)
        p.textPaint.color = Color.WHITE
        p.textPaint.textSize = 20f * unit
        p.textPaint.typeface = boldDefaultTf
        canvas.drawText("ON CALL", badgeRect.centerX(), badgeRect.centerY() + 7f * unit, p.textPaint)
    }

    private fun drawStickman(
        canvas: Canvas,
        p: StickPaints,
        cx: Float,
        footY: Float,
        rawScale: Float,
        profile: CharacterProfile,
        mood: MoodPose,
        isSpeaking: Boolean,
        lipAmp: Float,
        timeSec: Float,
        facingRight: Boolean,
        running: Boolean = false,
        propDropped: Boolean = false,
        propDropProgress: Float = 0f,
        deskSlam: Boolean = false,
        holdingPhoneToEar: Boolean = false,
        activity: CharacterActivity = CharacterActivity.TALKING
    ) {
        val scale = rawScale * profile.heightScale.coerceIn(0.78f, 1.18f)
        val outfitArgb = parseOutfitColor(profile.outfitColorHex)
        val dir = if (facingRight) 1f else -1f
        val jitterX = when {
            mood == MoodPose.NERVOUS || mood == MoodPose.SWEATING -> sin(timeSec * 45f) * 3.5f * scale
            activity == CharacterActivity.DANCING -> sin(timeSec * 8f) * 14f * scale
            activity == CharacterActivity.DRIVING_RIDING -> sin(timeSec * 28f) * 4f * scale
            else -> 0f
        }
        val bounceY = when {
            running -> abs(sin(timeSec * 22f)) * 18f * scale
            activity == CharacterActivity.DANCING -> abs(sin(timeSec * 10f)) * 14f * scale
            activity == CharacterActivity.GYM_WORKOUT -> abs(sin(timeSec * 5f)) * 22f * scale
            activity == CharacterActivity.CLAPPING_CHEERING -> abs(sin(timeSec * 11f)) * 16f * scale
            activity == CharacterActivity.WALKING_IN -> abs(sin(timeSec * 10f)) * 7f * scale
            activity == CharacterActivity.DRIVING_RIDING -> sin(timeSec * 24f) * 4.5f * scale
            activity == CharacterActivity.SLEEPING_SNORING -> sin(timeSec * 2.5f) * 3f * scale
            else -> sin(timeSec * 3.5f) * 2.5f * scale
        }
        val baseX = cx + jitterX
        val squatDrop = if (activity == CharacterActivity.GYM_WORKOUT) abs(sin(timeSec * 5f)) * 20f * scale else 0f
        val hipY = footY - 125f * scale - bounceY + squatDrop
        val shoulderY = hipY - 120f * scale
        val headDroopY = if (activity == CharacterActivity.SLEEPING_SNORING) 14f * scale else 0f
        val headY = shoulderY - 76f * scale + headDroopY
        val headR = 56f * scale

        // Ground shadow
        canvas.drawOval(
            baseX - 46f * scale,
            footY - 8f * scale,
            baseX + 46f * scale,
            footY + 10f * scale,
            p.shadowFill
        )

        p.inkPaint.strokeWidth = 10f * scale

        // Legs
        val isFemale = profile.gender == CharacterGender.FEMALE
        if (running || activity == CharacterActivity.WALKING_IN) {
            val freq = if (running) 22f else 10f
            val amp = if (running) 58f else 34f
            val stride = sin(timeSec * freq) * amp * scale
            canvas.drawLine(baseX, hipY, baseX + stride, footY - 14f * scale, p.inkPaint)
            canvas.drawLine(baseX, hipY, baseX - stride, footY - 6f * scale, p.inkPaint)
        } else if (activity == CharacterActivity.DANCING) {
            val step = sin(timeSec * 8f) * 24f * scale
            canvas.drawLine(baseX, hipY, baseX - 24f * scale + step, footY, p.inkPaint)
            canvas.drawLine(baseX, hipY, baseX + 24f * scale + step, footY - abs(step) * 0.35f, p.inkPaint)
        } else if (activity == CharacterActivity.GYM_WORKOUT) {
            canvas.drawLine(baseX, hipY, baseX - 36f * scale, footY, p.inkPaint)
            canvas.drawLine(baseX, hipY, baseX + 36f * scale, footY, p.inkPaint)
        } else if (isFemale) {
            canvas.drawLine(baseX - 10f * scale, hipY + 18f * scale, baseX - 16f * scale, footY, p.inkPaint)
            canvas.drawLine(baseX + 10f * scale, hipY + 18f * scale, baseX + 16f * scale, footY, p.inkPaint)
        } else {
            canvas.drawLine(baseX, hipY, baseX - 24f * scale, footY, p.inkPaint)
            canvas.drawLine(baseX, hipY, baseX + 24f * scale, footY, p.inkPaint)
        }

        // Torso (A-line cel-shaded dress for Female, or optional colored shirt accent for Male)
        val lean = when {
            running -> 22f * scale * dir
            deskSlam -> 16f * scale * dir
            activity == CharacterActivity.DANCING -> sin(timeSec * 8f) * 14f * scale
            activity == CharacterActivity.PLAYING_CRICKET -> 12f * scale * dir
            activity == CharacterActivity.COOKING_CHAI || activity == CharacterActivity.STUDYING_WRITING -> 10f * scale * dir
            activity == CharacterActivity.CRYING_DRAMA -> -10f * scale * dir
            activity == CharacterActivity.SLEEPING_SNORING -> -14f * scale * dir
            else -> 0f
        }
        if (isFemale) {
            val shXTop = baseX + lean
            val skirtY = hipY + 26f * scale
            val waistY = hipY - 38f * scale
            val dressPath = p.pathA.apply {
                rewind()
                moveTo(shXTop - 11f * scale, shoulderY + 6f * scale)
                lineTo(shXTop + 11f * scale, shoulderY + 6f * scale)
                lineTo(baseX + 14f * scale, waistY)
                lineTo(baseX + 38f * scale, skirtY)
                quadTo(baseX, skirtY + 8f * scale, baseX - 38f * scale, skirtY)
                lineTo(baseX - 14f * scale, waistY)
                close()
            }
            canvas.drawLine(shXTop, shoulderY + 8f * scale, shXTop, shoulderY - 16f * scale, p.inkPaint)
            val dressFill = if (outfitArgb != 0) {
                p.outfitFill.color = outfitArgb
                p.outfitFill
            } else {
                p.whiteFill
            }
            canvas.drawPath(dressPath, dressFill)
            if (outfitArgb == 0) {
                canvas.save()
                canvas.clipPath(dressPath)
                canvas.drawRect(
                    baseX - 40f * scale,
                    shoulderY,
                    baseX - 4f * scale,
                    skirtY + 10f * scale,
                    p.celShadeFill
                )
                canvas.restore()
            }
            p.inkPaint.strokeWidth = 6.5f * scale
            canvas.drawPath(dressPath, p.inkPaint)
            p.inkPaint.strokeWidth = 10f * scale
        } else if (outfitArgb != 0) {
            val shXTop = baseX + lean
            val shirtPath = p.pathA.apply {
                rewind()
                moveTo(shXTop - 15f * scale, shoulderY + 4f * scale)
                lineTo(shXTop + 15f * scale, shoulderY + 4f * scale)
                lineTo(baseX + 18f * scale, hipY - 4f * scale)
                lineTo(baseX - 18f * scale, hipY - 4f * scale)
                close()
            }
            canvas.drawLine(shXTop, shoulderY + 6f * scale, shXTop, shoulderY - 16f * scale, p.inkPaint)
            p.outfitFill.color = outfitArgb
            canvas.drawPath(shirtPath, p.outfitFill)
            p.inkPaint.strokeWidth = 6.5f * scale
            canvas.drawPath(shirtPath, p.inkPaint)
            p.inkPaint.strokeWidth = 10f * scale
        } else {
            canvas.drawLine(baseX, hipY, baseX + lean, shoulderY, p.inkPaint)
        }

        // Arms & Activity Props
        val shX = baseX + lean
        val hX = shX + lean * 0.25f
        val hY = headY
        val gestureWave = if (isSpeaking) sin(timeSec * 12f) * 20f * scale else 0f
        var activityDrawsCustomProp = false

        when {
            holdingPhoneToEar -> {
                val earX = hX - 24f * scale * dir
                val earY = hY + 6f * scale
                canvas.drawLine(shX, shoulderY, shX - 48f * scale * dir, shoulderY - 12f * scale, p.inkPaint)
                canvas.drawLine(shX - 48f * scale * dir, shoulderY - 12f * scale, earX, earY, p.inkPaint)
                canvas.drawLine(shX, shoulderY, shX + 36f * scale * dir, shoulderY + 58f * scale, p.inkPaint)
                drawProp(canvas, p, earX - 6f * scale * dir, earY, scale, CharacterProp.PHONE, false)
                activityDrawsCustomProp = true
            }
            deskSlam -> {
                canvas.drawLine(shX, shoulderY, shX + 64f * scale * dir, shoulderY + 62f * scale, p.inkPaint)
                canvas.drawLine(shX, shoulderY, shX - 40f * scale * dir, shoulderY + 58f * scale, p.inkPaint)
            }
            activity == CharacterActivity.DANCING -> {
                val lWave = sin(timeSec * 10f) * 34f * scale
                val rWave = -sin(timeSec * 10f) * 34f * scale
                canvas.drawLine(shX, shoulderY, shX + 54f * scale * dir, shoulderY - 38f * scale + lWave, p.inkPaint)
                canvas.drawLine(shX, shoulderY, shX - 54f * scale * dir, shoulderY - 38f * scale + rWave, p.inkPaint)
                p.textPaint.color = p.inkFill.color
                p.textPaint.typeface = boldDefaultTf
                p.textPaint.textSize = 30f * scale
                val noteBob = sin(timeSec * 6f) * 10f * scale
                canvas.drawText("♪", hX - 66f * scale, hY - 58f * scale + noteBob, p.textPaint)
                canvas.drawText("♫", hX + 66f * scale, hY - 68f * scale - noteBob, p.textPaint)
            }
            activity == CharacterActivity.GYM_WORKOUT -> {
                activityDrawsCustomProp = true
                val curl = sin(timeSec * 7f) * 38f * scale
                val rHandX = shX + 54f * scale * dir
                val rHandY = shoulderY + 10f * scale - curl
                val lHandX = shX - 54f * scale * dir
                val lHandY = shoulderY + 10f * scale + curl * 0.8f
                canvas.drawLine(shX, shoulderY, rHandX, rHandY, p.inkPaint)
                canvas.drawLine(shX, shoulderY, lHandX, lHandY, p.inkPaint)
                drawProp(canvas, p, rHandX, rHandY, scale, CharacterProp.DUMBBELL, false)
                drawProp(canvas, p, lHandX, lHandY, scale, CharacterProp.DUMBBELL, false)
            }
            activity == CharacterActivity.PLAYING_CRICKET -> {
                activityDrawsCustomProp = true
                val swing = sin(timeSec * 8f)
                val hxPos = shX + 56f * scale * dir
                val hyPos = shoulderY + (20f + swing * 24f) * scale
                canvas.drawLine(shX, shoulderY, hxPos, hyPos, p.inkPaint)
                canvas.drawLine(shX, shoulderY, hxPos - 14f * scale * dir, hyPos + 12f * scale, p.inkPaint)
                p.inkPaint.strokeWidth = 11f * scale
                val batTipX = hxPos + (42f + swing * 32f) * scale * dir
                val batTipY = hyPos - (44f - swing * 48f) * scale
                canvas.drawLine(hxPos, hyPos, batTipX, batTipY, p.inkPaint)
                p.inkPaint.strokeWidth = 10f * scale
                val ballX = hxPos + (78f + ((timeSec * 90f) % 80f)) * scale * dir
                val ballY = shoulderY - 22f * scale - abs(sin(timeSec * 8f)) * 28f * scale
                canvas.drawCircle(ballX, ballY, 8f * scale, p.accentFill)
            }
            activity == CharacterActivity.COOKING_CHAI -> {
                activityDrawsCustomProp = true
                val stirX = sin(timeSec * 12f) * 14f * scale
                val handX = shX + 58f * scale * dir
                val handY = shoulderY + 34f * scale
                canvas.drawLine(shX, shoulderY, handX, handY, p.inkPaint)
                canvas.drawLine(shX, shoulderY, shX - 36f * scale * dir, shoulderY + 54f * scale, p.inkPaint)
                p.inkPaint.strokeWidth = 6f * scale
                canvas.drawLine(handX, handY, handX + 38f * scale * dir, handY, p.inkPaint)
                val potX1 = handX + 32f * scale * dir
                val potX2 = handX + 74f * scale * dir
                p.rect.set(minOf(potX1, potX2), handY - 12f * scale, maxOf(potX1, potX2), handY + 12f * scale)
                canvas.drawRect(p.rect, p.inkFill)
                p.inkPaint.strokeWidth = 2.8f * scale
                canvas.drawLine(handX + 46f * scale * dir, handY - 18f * scale, handX + 46f * scale * dir + stirX, handY - 46f * scale, p.inkPaint)
                canvas.drawLine(handX + 60f * scale * dir, handY - 18f * scale, handX + 60f * scale * dir - stirX, handY - 52f * scale, p.inkPaint)
                p.inkPaint.strokeWidth = 10f * scale
            }
            activity == CharacterActivity.STUDYING_WRITING -> {
                activityDrawsCustomProp = true
                val scribble = sin(timeSec * 18f) * 6f * scale
                val handX = shX + 50f * scale * dir
                val handY = shoulderY + 42f * scale
                canvas.drawLine(shX, shoulderY, handX + scribble, handY, p.inkPaint)
                canvas.drawLine(shX, shoulderY, handX - 18f * scale * dir, handY + 10f * scale, p.inkPaint)
                drawProp(canvas, p, handX, handY + 6f * scale, scale, CharacterProp.CLIPBOARD, false)
            }
            activity == CharacterActivity.GAMING_PHONE -> {
                activityDrawsCustomProp = true
                val thumbTap = sin(timeSec * 24f) * 5f * scale
                val phoneX = shX + 54f * scale * dir
                val phoneY = shoulderY + 18f * scale
                canvas.drawLine(shX, shoulderY, phoneX, phoneY + thumbTap, p.inkPaint)
                canvas.drawLine(shX, shoulderY, phoneX - 10f * scale * dir, phoneY + 14f * scale, p.inkPaint)
                drawProp(canvas, p, phoneX + 8f * scale * dir, phoneY - 6f * scale, scale, CharacterProp.PHONE, false)
            }
            activity == CharacterActivity.SLEEPING_SNORING -> {
                canvas.drawLine(shX, shoulderY, shX + 26f * scale * dir, shoulderY + 68f * scale, p.inkPaint)
                canvas.drawLine(shX, shoulderY, shX - 24f * scale * dir, shoulderY + 68f * scale, p.inkPaint)
                val zRise = ((timeSec * 36f) % 48f) * scale
                p.textPaint.color = p.inkFill.color
                p.textPaint.typeface = boldDefaultTf
                p.textPaint.textSize = 26f * scale
                canvas.drawText("Z", hX + 44f * scale * dir, hY - 40f * scale - zRise * 0.6f, p.textPaint)
                p.textPaint.textSize = 20f * scale
                canvas.drawText("z", hX + 64f * scale * dir, hY - 62f * scale - zRise, p.textPaint)
            }
            activity == CharacterActivity.CLAPPING_CHEERING -> {
                val clapGap = abs(sin(timeSec * 16f)) * 22f * scale
                val midX = shX + 42f * scale * dir
                val midY = shoulderY - 12f * scale
                canvas.drawLine(shX, shoulderY, midX + clapGap * 0.5f, midY, p.inkPaint)
                canvas.drawLine(shX, shoulderY, midX - clapGap * 0.5f, midY - 8f * scale, p.inkPaint)
                if (clapGap < 6f * scale) {
                    p.angerPaint.strokeWidth = 3f * scale
                    canvas.drawLine(midX - 18f * scale, midY - 24f * scale, midX - 28f * scale, midY - 36f * scale, p.angerPaint)
                    canvas.drawLine(midX + 18f * scale, midY - 24f * scale, midX + 28f * scale, midY - 36f * scale, p.angerPaint)
                }
            }
            activity == CharacterActivity.CRYING_DRAMA -> {
                canvas.drawLine(shX, shoulderY, hX + 18f * scale * dir, hY - 16f * scale, p.inkPaint)
                canvas.drawLine(shX, shoulderY, shX - 38f * scale * dir, shoulderY + 60f * scale, p.inkPaint)
                val tearDrop = ((timeSec * 65f) % 44f) * scale
                canvas.drawCircle(hX + 24f * scale * dir, hY + 6f * scale + tearDrop, 5.5f * scale, p.sweatFill)
                canvas.drawCircle(hX - 10f * scale * dir, hY + 12f * scale + tearDrop * 0.8f, 5f * scale, p.sweatFill)
            }
            activity == CharacterActivity.TAKING_SELFIE -> {
                activityDrawsCustomProp = true
                val camX = shX + 66f * scale * dir
                val camY = hY - 26f * scale
                canvas.drawLine(shX, shoulderY, camX, camY, p.inkPaint)
                canvas.drawLine(shX, shoulderY, shX + 36f * scale * dir, shoulderY + 34f * scale, p.inkPaint)
                drawProp(canvas, p, camX, camY, scale, CharacterProp.PHONE, false)
                if ((timeSec % 1.2f) < 0.25f) {
                    p.angerPaint.strokeWidth = 3f * scale
                    canvas.drawCircle(camX, camY - 18f * scale, 12f * scale, p.angerPaint)
                }
            }
            activity == CharacterActivity.DRIVING_RIDING -> {
                activityDrawsCustomProp = true
                val turn = sin(timeSec * 6f) * 10f * scale
                val wx = shX + 54f * scale * dir
                val wy = shoulderY + 10f * scale
                canvas.drawLine(shX, shoulderY, wx, wy - turn, p.inkPaint)
                canvas.drawLine(shX, shoulderY, wx - 6f * scale * dir, wy + 14f * scale + turn, p.inkPaint)
                p.inkPaint.strokeWidth = 5f * scale
                canvas.drawCircle(wx + 8f * scale * dir, wy + 4f * scale, 20f * scale, p.inkPaint)
                p.inkPaint.strokeWidth = 10f * scale
            }
            activity == CharacterActivity.SWEEPING_CLEANING -> {
                activityDrawsCustomProp = true
                val sweep = sin(timeSec * 9f) * 28f * scale
                val hxPos = shX + 46f * scale * dir
                val hyPos = shoulderY + 34f * scale
                canvas.drawLine(shX, shoulderY, hxPos, hyPos, p.inkPaint)
                canvas.drawLine(shX, shoulderY, shX - 30f * scale * dir, shoulderY + 56f * scale, p.inkPaint)
                p.inkPaint.strokeWidth = 5.5f * scale
                val footBroomX = baseX + 56f * scale * dir + sweep
                canvas.drawLine(hxPos - 10f * scale * dir, hyPos - 26f * scale, footBroomX, footY - 8f * scale, p.inkPaint)
                canvas.drawRect(footBroomX - 18f * scale, footY - 12f * scale, footBroomX + 18f * scale, footY, p.celShadeFill)
                canvas.drawRect(footBroomX - 18f * scale, footY - 12f * scale, footBroomX + 18f * scale, footY, p.inkPaint)
                p.inkPaint.strokeWidth = 10f * scale
            }
            mood == MoodPose.POINTING -> {
                canvas.drawLine(shX, shoulderY, shX + 86f * scale * dir, shoulderY - 12f * scale, p.inkPaint)
                canvas.drawLine(shX, shoulderY, shX - 36f * scale * dir, shoulderY + 64f * scale, p.inkPaint)
            }
            mood == MoodPose.HIDING -> {
                canvas.drawLine(shX, shoulderY, shX + 22f * scale * dir, headY + 12f * scale, p.inkPaint)
                canvas.drawLine(shX, shoulderY, shX - 22f * scale * dir, headY + 14f * scale, p.inkPaint)
            }
            else -> {
                canvas.drawLine(shX, shoulderY, shX + 54f * scale * dir, shoulderY + 46f * scale - gestureWave, p.inkPaint)
                canvas.drawLine(shX, shoulderY, shX - 42f * scale * dir, shoulderY + 56f * scale, p.inkPaint)
            }
        }

        // Prop in hand
        if (!activityDrawsCustomProp && profile.prop != CharacterProp.NONE) {
            val handX = shX + 58f * scale * dir
            val dropOffset = if (propDropped) propDropProgress * propDropProgress * 190f * scale else 0f
            val handY = shoulderY + 44f * scale + dropOffset
            drawProp(canvas, p, handX, handY, scale, profile.prop, dropOffset > 0f)
        }

        // Head with crisp cel shading
        canvas.drawCircle(hX, hY, headR, p.whiteFill)
        canvas.save()
        p.pathA.rewind()
        p.pathA.addCircle(hX, hY, headR, Path.Direction.CW)
        canvas.clipPath(p.pathA)
        canvas.drawCircle(hX + 14f * scale * dir, hY - 8f * scale, headR, p.whiteFill)
        canvas.drawCircle(hX - 38f * scale * dir, hY + 10f * scale, headR * 0.88f, p.celShadeFill)
        canvas.restore()
        p.inkPaint.strokeWidth = 6.5f * scale
        canvas.drawCircle(hX, hY, headR, p.inkPaint)

        // Hair style
        drawHair(canvas, p, hX, hY, headR, scale, dir, profile.hairStyle)

        // Facial expression + blinking + lip sync
        val effectiveMood = if (activity == CharacterActivity.SLEEPING_SNORING) MoodPose.DEADPAN else mood
        drawFace(canvas, p, hX, hY, headR, scale, dir, effectiveMood, isSpeaking, lipAmp, timeSec, profile)
    }

    private fun drawFace(
        canvas: Canvas,
        p: StickPaints,
        hx: Float,
        hy: Float,
        r: Float,
        scale: Float,
        dir: Float,
        mood: MoodPose,
        isSpeaking: Boolean,
        lipAmp: Float,
        timeSec: Float,
        profile: CharacterProfile
    ) {
        val eyeOffsetX = 18f * scale
        val lookShift = 8f * scale * dir
        val lx = hx - eyeOffsetX + lookShift
        val rx = hx + eyeOffsetX + lookShift
        val ey = hy - 8f * scale

        val blinkPhase = timeSec % 2.9f
        val isBlinking = blinkPhase > 2.77f

        p.inkPaint.strokeWidth = 4.2f * scale
        if (isBlinking || mood == MoodPose.DEADPAN) {
            val eyeW = 11f * scale
            canvas.drawLine(lx - eyeW, ey, lx + eyeW, ey, p.inkPaint)
            canvas.drawLine(rx - eyeW, ey, rx + eyeW, ey, p.inkPaint)
        } else if (mood == MoodPose.SHOCKED) {
            canvas.drawCircle(lx, ey, 12f * scale, p.whiteFill)
            canvas.drawCircle(rx, ey, 12f * scale, p.whiteFill)
            canvas.drawCircle(lx, ey, 12f * scale, p.inkPaint)
            canvas.drawCircle(rx, ey, 12f * scale, p.inkPaint)
            canvas.drawCircle(lx, ey, 3.2f * scale, p.inkFill)
            canvas.drawCircle(rx, ey, 3.2f * scale, p.inkFill)
        } else {
            canvas.drawCircle(lx, ey, 6f * scale, p.inkFill)
            canvas.drawCircle(rx, ey, 6f * scale, p.inkFill)
            if (profile.gender == CharacterGender.FEMALE) {
                p.inkPaint.strokeWidth = 3.2f * scale
                canvas.drawLine(lx - 4f * scale, ey - 5f * scale, lx - 12f * scale, ey - 10f * scale, p.inkPaint)
                canvas.drawLine(lx - 5f * scale, ey - 2f * scale, lx - 13f * scale, ey - 4f * scale, p.inkPaint)
                canvas.drawLine(rx + 4f * scale, ey - 5f * scale, rx + 12f * scale, ey - 10f * scale, p.inkPaint)
                canvas.drawLine(rx + 5f * scale, ey - 2f * scale, rx + 13f * scale, ey - 4f * scale, p.inkPaint)
            }
        }

        // Glasses
        if (profile.hasGlasses) {
            p.inkPaint.strokeWidth = 3.5f * scale
            canvas.drawRect(lx - 14f * scale, ey - 10f * scale, lx + 14f * scale, ey + 10f * scale, p.inkPaint)
            canvas.drawRect(rx - 14f * scale, ey - 10f * scale, rx + 14f * scale, ey + 10f * scale, p.inkPaint)
            canvas.drawLine(lx + 14f * scale, ey, rx - 14f * scale, ey, p.inkPaint)
        }

        // Eyebrows
        p.inkPaint.strokeWidth = 4.5f * scale
        when (mood) {
            MoodPose.ANGRY, MoodPose.FURIOUS -> {
                canvas.drawLine(lx - 12f * scale, ey - 18f * scale, lx + 10f * scale, ey - 10f * scale, p.inkPaint)
                canvas.drawLine(rx - 10f * scale, ey - 10f * scale, rx + 12f * scale, ey - 18f * scale, p.inkPaint)
            }
            MoodPose.NERVOUS, MoodPose.SWEATING -> {
                canvas.drawLine(lx - 12f * scale, ey - 12f * scale, lx + 10f * scale, ey - 18f * scale, p.inkPaint)
                canvas.drawLine(rx - 10f * scale, ey - 18f * scale, rx + 12f * scale, ey - 12f * scale, p.inkPaint)
            }
            MoodPose.SMUG -> {
                canvas.drawLine(lx - 11f * scale, ey - 13f * scale, lx + 10f * scale, ey - 13f * scale, p.inkPaint)
                canvas.drawLine(rx - 10f * scale, ey - 20f * scale, rx + 12f * scale, ey - 15f * scale, p.inkPaint)
            }
            else -> {
                canvas.drawLine(lx - 10f * scale, ey - 15f * scale, lx + 10f * scale, ey - 15f * scale, p.inkPaint)
                canvas.drawLine(rx - 10f * scale, ey - 15f * scale, rx + 10f * scale, ey - 15f * scale, p.inkPaint)
            }
        }

        // Facial hair (male models)
        val mx = hx + lookShift
        val my = hy + 22f * scale
        if (profile.gender == CharacterGender.MALE) {
            when (profile.facialHair) {
                FacialHair.MUSTACHE -> {
                    p.pathA.rewind()
                    p.pathA.moveTo(mx - 18f * scale, my - 4f * scale)
                    p.pathA.quadTo(mx, my - 13f * scale, mx + 18f * scale, my - 4f * scale)
                    p.inkPaint.strokeWidth = 6f * scale
                    canvas.drawPath(p.pathA, p.inkPaint)
                }
                FacialHair.GOATEE -> {
                    canvas.drawOval(mx - 8f * scale, my + 8f * scale, mx + 8f * scale, my + 24f * scale, p.inkFill)
                }
                FacialHair.BEARD -> {
                    p.rect.set(hx - r * 0.72f, hy + 10f * scale, hx + r * 0.72f, hy + r * 1.02f)
                    canvas.drawArc(p.rect, 0f, 180f, true, p.inkFill)
                }
                FacialHair.NONE -> Unit
            }
        }

        // Real-time lip-synced mouth
        val mouthOpen = if (isSpeaking && lipAmp > 0.04f) (lipAmp * 16f * scale).coerceIn(2.4f * scale, 18f * scale) else 0f
        p.inkPaint.strokeWidth = 4f * scale
        when {
            mouthOpen > 2.2f * scale -> {
                canvas.drawOval(mx - 13f * scale, my - mouthOpen * 0.45f, mx + 13f * scale, my + mouthOpen * 0.75f, p.inkFill)
            }
            mood == MoodPose.SHOCKED -> {
                canvas.drawCircle(mx, my + 2f * scale, 9f * scale, p.inkFill)
            }
            mood == MoodPose.SMUG -> {
                p.pathA.rewind()
                p.pathA.moveTo(mx - 14f * scale, my)
                p.pathA.quadTo(mx + 4f * scale, my + 10f * scale, mx + 16f * scale, my - 5f * scale)
                canvas.drawPath(p.pathA, p.inkPaint)
            }
            mood == MoodPose.ANGRY || mood == MoodPose.FURIOUS -> {
                p.pathA.rewind()
                p.pathA.moveTo(mx - 14f * scale, my + 5f * scale)
                p.pathA.quadTo(mx, my - 7f * scale, mx + 14f * scale, my + 5f * scale)
                canvas.drawPath(p.pathA, p.inkPaint)
            }
            else -> {
                canvas.drawLine(mx - 12f * scale, my, mx + 12f * scale, my, p.inkPaint)
            }
        }

        // Animated sweat drops
        if (mood == MoodPose.SWEATING || mood == MoodPose.NERVOUS) {
            val dropY = hy - r * 0.45f + ((timeSec * 45f) % 28f) * scale
            val dx = hx - r * 0.85f * dir
            canvas.drawCircle(dx, dropY, 5.5f * scale, p.sweatFill)
        }

        // Pulsing comic anger vein '#'
        if (mood == MoodPose.FURIOUS) {
            val vx = hx + r * 0.56f * dir
            val vy = hy - r * 0.54f
            val pulse = (1f + 0.15f * sin(timeSec * 14f)) * scale
            p.angerPaint.strokeWidth = 3.5f * scale
            canvas.drawLine(vx - 8f * pulse, vy - 3f * pulse, vx + 8f * pulse, vy - 3f * pulse, p.angerPaint)
            canvas.drawLine(vx - 8f * pulse, vy + 4f * pulse, vx + 8f * pulse, vy + 4f * pulse, p.angerPaint)
            canvas.drawLine(vx - 3f * pulse, vy - 8f * pulse, vx - 3f * pulse, vy + 8f * pulse, p.angerPaint)
            canvas.drawLine(vx + 4f * pulse, vy - 8f * pulse, vx + 4f * pulse, vy + 8f * pulse, p.angerPaint)
        }
    }

    private fun drawHair(
        canvas: Canvas,
        p: StickPaints,
        hx: Float,
        hy: Float,
        r: Float,
        scale: Float,
        dir: Float,
        hair: HairStyle
    ) {
        p.inkPaint.strokeWidth = 5.5f * scale
        when (hair) {
            HairStyle.BALD -> Unit
            HairStyle.SPIKY -> {
                p.pathA.rewind()
                p.pathA.moveTo(hx - r * 0.7f, hy - r * 0.55f)
                p.pathA.lineTo(hx - r * 0.4f, hy - r * 1.25f)
                p.pathA.lineTo(hx - r * 0.1f, hy - r * 0.85f)
                p.pathA.lineTo(hx + r * 0.15f, hy - r * 1.32f)
                p.pathA.lineTo(hx + r * 0.4f, hy - r * 0.85f)
                p.pathA.lineTo(hx + r * 0.72f, hy - r * 1.1f)
                p.pathA.lineTo(hx + r * 0.65f, hy - r * 0.55f)
                p.pathA.close()
                canvas.drawPath(p.pathA, p.inkFill)
            }
            HairStyle.PARTED -> {
                p.inkPaint.strokeWidth = 10f * scale
                p.rect.set(hx - r * 0.96f, hy - r * 1.05f, hx + r * 0.96f, hy + r * 0.25f)
                canvas.drawArc(p.rect, 195f, 150f, false, p.inkPaint)
            }
            HairStyle.BOB -> {
                p.pathA.rewind()
                p.rect.set(hx - r * 1.05f, hy - r * 1.08f, hx + r * 1.05f, hy + r * 0.8f)
                p.pathA.addArc(p.rect, 175f, 190f)
                p.inkPaint.strokeWidth = 12f * scale
                canvas.drawPath(p.pathA, p.inkPaint)
            }
            HairStyle.BUN -> {
                canvas.drawCircle(hx - r * 0.25f * dir, hy - r * 1.15f, 20f * scale, p.inkFill)
            }
            HairStyle.MESSY -> {
                for (i in -2..2) {
                    canvas.drawLine(
                        hx + i * 16f * scale,
                        hy - r * 0.8f,
                        hx + (i * 20f - 8f) * scale,
                        hy - r * 1.22f,
                        p.inkPaint
                    )
                }
            }
            HairStyle.PONYTAIL -> {
                p.inkPaint.strokeWidth = 10f * scale
                p.rect.set(hx - r * 0.98f, hy - r * 1.06f, hx + r * 0.98f, hy + r * 0.15f)
                canvas.drawArc(p.rect, 195f, 150f, false, p.inkPaint)
                p.pathA.rewind()
                p.pathA.moveTo(hx - r * 0.85f * dir, hy - r * 0.48f)
                p.pathA.quadTo(hx - r * 1.68f * dir, hy - r * 0.35f, hx - r * 1.42f * dir, hy + r * 0.65f)
                p.inkPaint.strokeWidth = 14f * scale
                canvas.drawPath(p.pathA, p.inkPaint)
            }
            HairStyle.LONG_WAVY -> {
                p.inkPaint.strokeWidth = 13f * scale
                p.rect.set(hx - r * 1.04f, hy - r * 1.08f, hx + r * 1.04f, hy + r * 0.25f)
                canvas.drawArc(p.rect, 185f, 170f, false, p.inkPaint)
                p.pathA.rewind()
                p.pathA.moveTo(hx - r * 0.96f, hy - r * 0.15f)
                p.pathA.quadTo(hx - r * 1.28f, hy + r * 0.45f, hx - r * 0.95f, hy + r * 1.25f)
                p.pathB.rewind()
                p.pathB.moveTo(hx + r * 0.96f, hy - r * 0.15f)
                p.pathB.quadTo(hx + r * 1.28f, hy + r * 0.45f, hx + r * 0.95f, hy + r * 1.25f)
                p.inkPaint.strokeWidth = 12f * scale
                canvas.drawPath(p.pathA, p.inkPaint)
                canvas.drawPath(p.pathB, p.inkPaint)
            }
            HairStyle.TWIN_BRAIDS -> {
                p.inkPaint.strokeWidth = 11f * scale
                p.rect.set(hx - r * 1.0f, hy - r * 1.06f, hx + r * 1.0f, hy + r * 0.2f)
                canvas.drawArc(p.rect, 185f, 170f, false, p.inkPaint)
                for (sIdx in 0..1) {
                    val side = if (sIdx == 0) -1f else 1f
                    val bx = hx + side * r * 0.95f
                    for (seg in 0..3) {
                        canvas.drawCircle(
                            bx + side * (seg * 3f * scale),
                            hy + (seg * 18f) * scale,
                            9.5f * scale,
                            p.inkFill
                        )
                    }
                }
            }
        }
    }

    private fun drawMemeSticker(
        canvas: Canvas,
        p: StickPaints,
        colors: ThemeColors,
        hx: Float,
        hy: Float,
        unit: Float,
        sticker: MemeSticker,
        timeSec: Float
    ) {
        val bob = sin(timeSec * 8f) * 6f * unit
        when (sticker) {
            MemeSticker.NONE -> Unit
            MemeSticker.DEAL_WITH_IT_SHADES -> {
                p.glintPaint.strokeWidth = 3f * unit
                val ey = hy - 8f * unit
                canvas.drawRect(hx - 42f * unit, ey - 12f * unit, hx - 4f * unit, ey + 10f * unit, p.shadesPaint)
                canvas.drawRect(hx + 4f * unit, ey - 12f * unit, hx + 42f * unit, ey + 10f * unit, p.shadesPaint)
                canvas.drawRect(hx - 42f * unit, ey - 10f * unit, hx + 42f * unit, ey - 4f * unit, p.shadesPaint)
                canvas.drawLine(hx - 32f * unit, ey - 8f * unit, hx - 22f * unit, ey + 4f * unit, p.glintPaint)
                canvas.drawLine(hx + 14f * unit, ey - 8f * unit, hx + 24f * unit, ey + 4f * unit, p.glintPaint)
            }
            MemeSticker.RED_CIRCLE -> {
                p.angerPaint.strokeWidth = 7f * unit
                val pulse = 1f + 0.06f * sin(timeSec * 12f)
                canvas.drawCircle(hx, hy, 84f * unit * pulse, p.angerPaint)
                canvas.drawLine(hx + 128f * unit, hy - 115f * unit, hx + 78f * unit, hy - 68f * unit, p.angerPaint)
                canvas.drawLine(hx + 78f * unit, hy - 68f * unit, hx + 104f * unit, hy - 72f * unit, p.angerPaint)
                canvas.drawLine(hx + 78f * unit, hy - 68f * unit, hx + 82f * unit, hy - 94f * unit, p.angerPaint)
            }
            MemeSticker.MIND_BLOWN -> {
                p.angerPaint.strokeWidth = 4.5f * unit
                for (i in 0..7) {
                    val ang = (i * 45.0) * PI / 180.0
                    val r1 = 68f * unit
                    val r2 = (96f + 12f * sin(timeSec * 14f + i)) * unit
                    canvas.drawLine(
                        hx + cos(ang).toFloat() * r1,
                        hy - 24f * unit + sin(ang).toFloat() * r1,
                        hx + cos(ang).toFloat() * r2,
                        hy - 24f * unit + sin(ang).toFloat() * r2,
                        p.angerPaint
                    )
                }
            }
            MemeSticker.QUESTION_MARKS -> {
                p.textPaint.color = colors.accentColor
                p.textPaint.textSize = 42f * unit
                p.textPaint.typeface = boldDefaultTf
                canvas.drawText("???", hx, hy - 86f * unit + bob, p.textPaint)
            }
            MemeSticker.CROWN -> {
                val crownY = hy - 78f * unit + bob * 0.4f
                p.pathA.rewind()
                p.pathA.moveTo(hx - 34f * unit, crownY + 22f * unit)
                p.pathA.lineTo(hx - 38f * unit, crownY - 12f * unit)
                p.pathA.lineTo(hx - 14f * unit, crownY + 6f * unit)
                p.pathA.lineTo(hx, crownY - 22f * unit)
                p.pathA.lineTo(hx + 14f * unit, crownY + 6f * unit)
                p.pathA.lineTo(hx + 38f * unit, crownY - 12f * unit)
                p.pathA.lineTo(hx + 34f * unit, crownY + 22f * unit)
                p.pathA.close()
                canvas.drawPath(p.pathA, p.goldFill)
                p.inkPaint.strokeWidth = 4f * unit
                canvas.drawPath(p.pathA, p.inkPaint)
            }
            MemeSticker.BROKEN_HEART -> {
                p.textPaint.color = colors.accentColor
                p.textPaint.textSize = 46f * unit
                p.textPaint.typeface = boldDefaultTf
                canvas.drawText("</3", hx + 68f * unit, hy - 68f * unit + bob, p.textPaint)
            }
            MemeSticker.MIC_DROP -> {
                val dropY = hy + 96f * unit + abs(sin(timeSec * 6f)) * 28f * unit
                canvas.drawCircle(hx + 84f * unit, dropY - 14f * unit, 12f * unit, p.inkFill)
                p.inkPaint.strokeWidth = 7f * unit
                canvas.drawLine(hx + 84f * unit, dropY - 4f * unit, hx + 96f * unit, dropY + 28f * unit, p.inkPaint)
            }
        }
    }

    private fun drawProp(
        canvas: Canvas,
        p: StickPaints,
        x: Float,
        y: Float,
        scale: Float,
        prop: CharacterProp,
        isFalling: Boolean
    ) {
        p.inkPaint.strokeWidth = 3.5f * scale
        when (prop) {
            CharacterProp.NONE -> Unit
            CharacterProp.CLIPBOARD, CharacterProp.FOLDER -> {
                p.rect.set(x - 16f * scale, y - 22f * scale, x + 16f * scale, y + 22f * scale)
                canvas.drawRect(p.rect, p.whiteFill)
                canvas.drawRect(p.rect, p.inkPaint)
                canvas.drawRect(x - 7f * scale, y - 26f * scale, x + 7f * scale, y - 18f * scale, p.inkFill)
            }
            CharacterProp.MUG -> {
                canvas.drawRect(x - 11f * scale, y - 12f * scale, x + 11f * scale, y + 14f * scale, p.whiteFill)
                canvas.drawRect(x - 11f * scale, y - 12f * scale, x + 11f * scale, y + 14f * scale, p.inkPaint)
            }
            CharacterProp.STETHOSCOPE -> {
                canvas.drawCircle(x, y, 12f * scale, p.inkPaint)
            }
            CharacterProp.PHONE -> {
                canvas.drawRect(x - 9f * scale, y - 16f * scale, x + 9f * scale, y + 16f * scale, p.inkFill)
            }
            CharacterProp.RULER -> {
                p.inkPaint.strokeWidth = 6f * scale
                canvas.drawLine(x, y + 8f * scale, x + 44f * scale, y - 34f * scale, p.inkPaint)
            }
            CharacterProp.DUMBBELL -> {
                canvas.drawLine(x - 18f * scale, y, x + 18f * scale, y, p.inkPaint)
                canvas.drawCircle(x - 18f * scale, y, 9f * scale, p.inkFill)
                canvas.drawCircle(x + 18f * scale, y, 9f * scale, p.inkFill)
            }
        }
        if (isFalling) {
            p.inkPaint.strokeWidth = 2.5f * scale
            canvas.drawLine(x - 12f * scale, y - 34f * scale, x - 12f * scale, y - 54f * scale, p.inkPaint)
            canvas.drawLine(x + 12f * scale, y - 34f * scale, x + 12f * scale, y - 54f * scale, p.inkPaint)
        }
    }

    private fun drawForegroundDesk(
        canvas: Canvas,
        p: StickPaints,
        w: Float,
        floorY: Float,
        unit: Float,
        hasImpact: Boolean,
        prog: Float
    ) {
        p.rect.set(w * 0.14f, floorY - 64f * unit, w * 0.86f, floorY + 26f * unit)
        canvas.drawRect(p.rect, p.celShadeFill)
        p.inkPaint.strokeWidth = 5f * unit
        canvas.drawRect(p.rect, p.inkPaint)

        if (hasImpact && prog < 0.55f) {
            val ix = w * 0.36f
            val iy = p.rect.top
            p.inkPaint.strokeWidth = 4f * unit
            for (k in 0..5) {
                val ang = (-160.0 + k * 28.0) * PI / 180.0
                val r1 = 18f * unit
                val r2 = (44f + prog * 40f) * unit
                canvas.drawLine(
                    ix + cos(ang).toFloat() * r1,
                    iy + sin(ang).toFloat() * r1,
                    ix + cos(ang).toFloat() * r2,
                    iy + sin(ang).toFloat() * r2,
                    p.inkPaint
                )
            }
        }
    }

    private fun fitTextSize(
        paint: Paint,
        text: CharSequence,
        start: Int,
        end: Int,
        preferredSize: Float,
        minSize: Float,
        maxWidth: Float
    ) {
        paint.textSize = preferredSize
        if (start >= end || maxWidth <= 0f) return
        val measured = paint.measureText(text, start, end)
        if (measured > maxWidth && measured > 0f) {
            paint.textSize = (preferredSize * (maxWidth / measured)).coerceAtLeast(minSize)
        }
    }

    private fun writeUpperInto(dst: StringBuilder, src: String, maxChars: Int) {
        dst.setLength(0)
        val trimmedStart = src.indexOfFirst { !it.isWhitespace() }
        if (trimmedStart < 0) return
        val trimmedEnd = src.indexOfLast { !it.isWhitespace() } + 1
        val limit = (trimmedStart + maxChars).coerceAtMost(trimmedEnd)
        for (i in trimmedStart until limit) {
            dst.append(src[i].uppercaseChar())
        }
    }

    private fun drawTimeSkipCard(
        canvas: Canvas,
        p: StickPaints,
        colors: ThemeColors,
        w: Float,
        h: Float,
        unit: Float,
        cardText: String,
        prog: Float
    ) {
        canvas.drawRect(0f, 0f, w, h, p.darkCardBg)
        p.angerPaint.color = colors.accentColor
        p.angerPaint.strokeWidth = 5f * unit
        canvas.drawRect(44f * unit, 44f * unit, w - 44f * unit, h - 44f * unit, p.angerPaint)

        val scalePop = 0.85f + sin(prog * PI.toFloat()) * 0.18f
        p.textPaint.color = Color.WHITE
        p.textPaint.typeface = boldDefaultTf
        writeUpperInto(p.upperScratch, cardText, 36)
        val endIdx = p.upperScratch.length
        fitTextSize(p.textPaint, p.upperScratch, 0, endIdx, 64f * unit * scalePop, 24f * unit, w - 130f * unit)
        canvas.drawText(p.upperScratch, 0, endIdx, w * 0.5f, h * 0.50f, p.textPaint)

        p.textPaint.textSize = 22f * unit
        p.textPaint.color = colorLabelMuted
        canvas.drawText("• MEANWHILE •", w * 0.5f, h * 0.56f, p.textPaint)
    }

    private fun drawOutroCard(
        canvas: Canvas,
        p: StickPaints,
        colors: ThemeColors,
        w: Float,
        h: Float,
        unit: Float,
        outroText: String,
        handle: String,
        prog: Float
    ) {
        canvas.drawRect(0f, 0f, w, h, p.darkCardBg)

        p.angerPaint.color = colors.accentColor
        p.angerPaint.strokeWidth = 6f * unit
        canvas.drawRect(36f * unit, 36f * unit, w - 36f * unit, h - 36f * unit, p.angerPaint)

        val pop = 0.90f + 0.12f * sin(prog * PI.toFloat())
        val pill = p.rect.apply { set(w * 0.10f, h * 0.40f, w * 0.90f, h * 0.55f) }
        canvas.drawRoundRect(pill, 28f * unit, 28f * unit, p.accentFill)

        p.textPaint.color = Color.WHITE
        p.textPaint.typeface = boldDefaultTf
        val len = outroText.length.coerceAtMost(32)
        fitTextSize(p.textPaint, outroText, 0, len, 40f * unit * pop, 20f * unit, pill.width() - 36f * unit)
        canvas.drawText(outroText, 0, len, w * 0.5f, pill.centerY() + 14f * unit, p.textPaint)

        val wmLen = writeWatermarkHandleInto(p.watermarkScratch, handle)
        if (wmLen > 0) {
            p.textPaint.color = colorHandleLight
            fitTextSize(p.textPaint, p.watermarkScratch, 0, wmLen, 28f * unit, 18f * unit, w - 120f * unit)
            canvas.drawText(p.watermarkScratch, 0, wmLen, w * 0.5f, h * 0.63f, p.textPaint)
        }
    }

    private fun drawIntroHookBanner(
        canvas: Canvas,
        p: StickPaints,
        colors: ThemeColors,
        w: Float,
        unit: Float,
        bannerText: String
    ) {
        val rect = p.rect.apply { set(44f * unit, 24f * unit, w - 44f * unit, 76f * unit) }
        p.angerPaint.color = colors.accentColor
        p.angerPaint.strokeWidth = 3f * unit
        canvas.drawRoundRect(rect, 16f * unit, 16f * unit, p.bannerBg)
        canvas.drawRoundRect(rect, 16f * unit, 16f * unit, p.angerPaint)

        p.textPaint.color = Color.WHITE
        p.textPaint.typeface = boldDefaultTf
        val len = bannerText.length.coerceAtMost(42)
        fitTextSize(p.textPaint, bannerText, 0, len, 24f * unit, 14f * unit, rect.width() - 28f * unit)
        canvas.drawText(bannerText, 0, len, rect.centerX(), rect.centerY() + 8f * unit, p.textPaint)
    }

    private fun drawSpeedLines(canvas: Canvas, p: StickPaints, w: Float, h: Float, unit: Float, timeSec: Float) {
        p.inkPaint.strokeWidth = 3f * unit
        for (i in 0..10) {
            val ly = h * (0.18f + i * 0.06f)
            val offset = (timeSec * 900f + i * 140f) % w
            canvas.drawLine(offset, ly, offset + 120f * unit, ly, p.inkPaint)
        }
    }

    private fun drawBonkDustCloud(
        canvas: Canvas,
        p: StickPaints,
        colors: ThemeColors,
        cx: Float,
        cy: Float,
        unit: Float,
        prog: Float
    ) {
        val pulse = 1f + 0.08f * sin(prog * 24f)
        val uPulse = unit * pulse
        canvas.drawCircle(cx - 70f * uPulse, cy + 10f * uPulse, 58f * unit, p.whiteFill)
        canvas.drawCircle(cx + 70f * uPulse, cy + 16f * uPulse, 62f * unit, p.whiteFill)
        canvas.drawCircle(cx - 25f * uPulse, cy - 45f * uPulse, 64f * unit, p.whiteFill)
        canvas.drawCircle(cx + 35f * uPulse, cy - 38f * uPulse, 60f * unit, p.whiteFill)
        canvas.drawCircle(cx, cy + 20f * uPulse, 72f * unit, p.whiteFill)

        p.inkPaint.strokeWidth = 6f * unit
        canvas.drawCircle(cx - 70f * uPulse, cy + 10f * uPulse, 58f * unit, p.inkPaint)
        canvas.drawCircle(cx + 70f * uPulse, cy + 16f * uPulse, 62f * unit, p.inkPaint)
        canvas.drawCircle(cx - 25f * uPulse, cy - 45f * uPulse, 64f * unit, p.inkPaint)
        canvas.drawCircle(cx + 35f * uPulse, cy - 38f * uPulse, 60f * unit, p.inkPaint)
        canvas.drawCircle(cx, cy + 20f * uPulse, 72f * unit, p.inkPaint)

        p.textPaint.color = colors.accentColor
        p.textPaint.typeface = boldDefaultTf
        p.textPaint.textSize = 64f * unit * pulse
        canvas.drawText("BONK!", cx, cy + 16f * unit, p.textPaint)
    }

    private fun drawSpeechBubble(
        canvas: Canvas,
        p: StickPaints,
        colors: ThemeColors,
        cx: Float,
        cy: Float,
        frameWidth: Float,
        unit: Float,
        bubble: String
    ) {
        p.textPaint.typeface = boldDefaultTf
        val trimmedLen = bubble.length.coerceAtMost(32)
        if (trimmedLen <= 0) return

        val maxBoxW = (frameWidth - 48f * unit).coerceAtMost(440f * unit)
        val padX = 28f * unit
        fitTextSize(p.textPaint, bubble, 0, trimmedLen, 25f * unit, 15f * unit, maxBoxW - padX * 2)
        val textW = p.textPaint.measureText(bubble, 0, trimmedLen).coerceAtLeast(96f * unit)
        val boxW = (textW + padX * 2).coerceAtMost(maxBoxW)
        val boxH = 64f * unit
        val maxLeft = (frameWidth - boxW - 24f * unit).coerceAtLeast(24f * unit)
        val left = (cx - boxW / 2f).coerceIn(24f * unit, maxLeft)
        val rect = p.rect.apply { set(left, cy - boxH / 2f, left + boxW, cy + boxH / 2f) }

        p.angerPaint.color = colors.bubbleStrokeColor
        p.angerPaint.strokeWidth = 4.5f * unit
        canvas.drawRoundRect(rect, 22f * unit, 22f * unit, p.pureWhiteFill)
        canvas.drawRoundRect(rect, 22f * unit, 22f * unit, p.angerPaint)

        p.textPaint.color = colorBubbleText
        canvas.drawText(bubble, 0, trimmedLen, rect.centerX(), rect.centerY() + 8f * unit, p.textPaint)
    }

    private fun tokenizeWords(text: String, p: StickPaints): Int {
        var count = 0
        var i = 0
        val n = text.length
        while (i < n && count < p.wordStarts.size) {
            while (i < n && text[i].isWhitespace()) i++
            if (i >= n) break
            val start = i
            while (i < n && !text[i].isWhitespace()) i++
            p.wordStarts[count] = start
            p.wordEnds[count] = i
            count++
        }
        return count
    }

    private fun buildWrappedLines(
        text: String,
        wordCount: Int,
        p: StickPaints,
        maxCharsPerLine: Int
    ): Int {
        var lineCount = 0
        var lineStart = -1
        var lineEnd = -1
        for (i in 0 until wordCount) {
            val ws = p.wordStarts[i]
            val we = p.wordEnds[i]
            if (lineStart < 0) {
                lineStart = ws
                lineEnd = we
            } else if ((we - lineStart) <= maxCharsPerLine) {
                lineEnd = we
            } else {
                p.lineStarts[lineCount] = lineStart
                p.lineEnds[lineCount] = lineEnd
                lineCount++
                if (lineCount >= p.lineStarts.size) return lineCount
                lineStart = ws
                lineEnd = we
            }
        }
        if (lineStart >= 0 && lineCount < p.lineStarts.size) {
            p.lineStarts[lineCount] = lineStart
            p.lineEnds[lineCount] = lineEnd
            lineCount++
        }
        return lineCount
    }

    private fun drawCaptions(
        canvas: Canvas,
        p: StickPaints,
        colors: ThemeColors,
        w: Float,
        h: Float,
        unit: Float,
        text: String,
        progress: Float,
        style: CaptionStyle,
        fontStyle: CaptionFontStyle,
        position: CaptionPosition
    ) {
        val wordCount = tokenizeWords(text, p)
        if (wordCount == 0) return

        val baseY = if (position == CaptionPosition.CENTER_POP) h * 0.56f else h * 0.81f

        p.textPaint.typeface = when (fontStyle) {
            CaptionFontStyle.BOLD_SANS -> boldSansTf
            CaptionFontStyle.COMIC_PUNCH -> comicPunchTf
            CaptionFontStyle.TYPEWRITER_MONO -> monoBoldTf
            CaptionFontStyle.SERIF_DRAMA -> serifBoldTf
        }

        if (style == CaptionStyle.CLASSIC) {
            val lineCount = buildWrappedLines(text, wordCount, p, maxCharsPerLine = 28)
            val baseFontSize = 29f * unit
            p.textPaint.textSize = baseFontSize
            val maxLineAreaW = w * 0.78f
            var minScale = 1f
            for (idx in 0 until lineCount) {
                val lw = p.textPaint.measureText(text, p.lineStarts[idx], p.lineEnds[idx])
                if (lw > maxLineAreaW && lw > 0f) {
                    val s = maxLineAreaW / lw
                    if (s < minScale) minScale = s
                }
            }
            val finalFontSize = (baseFontSize * minScale).coerceAtLeast(16f * unit)
            p.textPaint.textSize = finalFontSize
            val lineH = finalFontSize * 1.26f
            val boxH = lineCount * lineH + 24f * unit
            val maxBottom = h - 50f * unit
            val boxTop = (baseY - boxH / 2f).coerceAtMost(maxBottom - boxH).coerceAtLeast(100f * unit)
            val box = p.rect.apply { set(w * 0.07f, boxTop, w * 0.93f, boxTop + boxH) }
            canvas.drawRoundRect(box, 16f * unit, 16f * unit, p.captionBgPaint)
            p.textPaint.color = Color.WHITE
            for (idx in 0 until lineCount) {
                canvas.drawText(
                    text,
                    p.lineStarts[idx],
                    p.lineEnds[idx],
                    w * 0.5f,
                    box.top + 12f * unit + finalFontSize + idx * lineH,
                    p.textPaint
                )
            }
        } else {
            val activeIdx = (progress * wordCount).toInt().coerceIn(0, wordCount - 1)
            val windowStart = (activeIdx / 4) * 4
            val windowEnd = (windowStart + 4).coerceAtMost(wordCount)
            val chunkLen = windowEnd - windowStart
            val localActive = activeIdx - windowStart

            var fontSize = 33f * unit
            var spacing = 14f * unit
            var pillPadX = 18f * unit
            p.textPaint.textSize = fontSize

            var rawTotalW = 0f
            for (k in 0 until chunkLen) {
                val idx = windowStart + k
                val ww = p.textPaint.measureText(text, p.wordStarts[idx], p.wordEnds[idx]) + pillPadX
                p.chunkWidths[k] = ww
                rawTotalW += ww
            }
            rawTotalW += spacing * (chunkLen - 1).coerceAtLeast(0)

            val maxChunkWidth = w - 68f * unit
            if (rawTotalW > maxChunkWidth && rawTotalW > 0f) {
                val fitScale = (maxChunkWidth / rawTotalW).coerceAtLeast(0.52f)
                fontSize *= fitScale
                spacing *= fitScale
                pillPadX *= fitScale
                p.textPaint.textSize = fontSize
                rawTotalW = 0f
                for (k in 0 until chunkLen) {
                    val idx = windowStart + k
                    val ww = p.textPaint.measureText(text, p.wordStarts[idx], p.wordEnds[idx]) + pillPadX
                    p.chunkWidths[k] = ww
                    rawTotalW += ww
                }
                rawTotalW += spacing * (chunkLen - 1).coerceAtLeast(0)
            }

            var cursorX = (w - rawTotalW) * 0.5f
            val bgBox = p.rect.apply {
                set(
                    (cursorX - 16f * unit).coerceAtLeast(20f * unit),
                    baseY - 34f * unit,
                    (cursorX + rawTotalW + 16f * unit).coerceAtMost(w - 20f * unit),
                    baseY + 34f * unit
                )
            }
            canvas.drawRoundRect(bgBox, 16f * unit, 16f * unit, p.captionBgPaint)

            val isBounce = style == CaptionStyle.VIRAL_BOUNCE
            val wordDuration = 1f / wordCount.coerceAtLeast(1)
            val wordLocalProg = ((progress - activeIdx * wordDuration) / wordDuration).coerceIn(0f, 1f)
            val bounceLift = if (isBounce) sin(wordLocalProg * PI.toFloat()) * 10f * unit else 0f

            p.popWordBgPaint.color = if (isBounce) colors.accentColor else Color.WHITE

            for (k in 0 until chunkLen) {
                val idx = windowStart + k
                val ww = p.chunkWidths[k]
                val cx = cursorX + ww * 0.5f
                if (k == localActive) {
                    p.rect.set(
                        cursorX,
                        baseY - 24f * unit - bounceLift,
                        cursorX + ww,
                        baseY + 24f * unit - bounceLift
                    )
                    canvas.drawRoundRect(p.rect, 10f * unit, 10f * unit, p.popWordBgPaint)
                    p.textPaint.color = if (isBounce) Color.WHITE else colorBubbleText
                    p.textPaint.textSize = fontSize * (if (isBounce) 1.12f else 1.07f)
                    canvas.drawText(text, p.wordStarts[idx], p.wordEnds[idx], cx, baseY + 11f * unit - bounceLift, p.textPaint)
                } else {
                    p.textPaint.color = Color.WHITE
                    p.textPaint.textSize = fontSize
                    canvas.drawText(text, p.wordStarts[idx], p.wordEnds[idx], cx, baseY + 11f * unit, p.textPaint)
                }
                cursorX += ww + spacing
            }
        }
    }

    private fun drawWatermark(
        canvas: Canvas,
        p: StickPaints,
        w: Float,
        h: Float,
        unit: Float,
        handle: String,
        position: WatermarkPosition = WatermarkPosition.BOTTOM_RIGHT,
        darkBg: Boolean,
        hasHookBanner: Boolean = false
    ) {
        val len = writeWatermarkHandleInto(p.watermarkScratch, handle)
        if (len <= 0) return
        p.watermarkPaint.color = if (darkBg) colorWatermarkLight else colorWatermarkDark
        p.watermarkPaint.textSize = 22f * unit

        val yTop = if (hasHookBanner) 106f * unit else 44f * unit
        when (position) {
            WatermarkPosition.BOTTOM_RIGHT -> {
                p.watermarkPaint.textAlign = Paint.Align.RIGHT
                canvas.drawText(p.watermarkScratch, 0, len, w - 28f * unit, h - 24f * unit, p.watermarkPaint)
            }
            WatermarkPosition.TOP_RIGHT -> {
                p.watermarkPaint.textAlign = Paint.Align.RIGHT
                canvas.drawText(p.watermarkScratch, 0, len, w - 28f * unit, yTop, p.watermarkPaint)
            }
            WatermarkPosition.TOP_LEFT -> {
                p.watermarkPaint.textAlign = Paint.Align.LEFT
                canvas.drawText(p.watermarkScratch, 0, len, 28f * unit, yTop, p.watermarkPaint)
            }
            WatermarkPosition.SAFE_BOTTOM -> {
                p.watermarkPaint.textAlign = Paint.Align.CENTER
                canvas.drawText(p.watermarkScratch, 0, len, w * 0.5f, h * 0.72f, p.watermarkPaint)
            }
        }
    }

    private fun drawImpactCallout(
        canvas: Canvas,
        p: StickPaints,
        colors: ThemeColors,
        cx: Float,
        cy: Float,
        w: Float,
        unit: Float,
        rawCallout: String,
        prog: Float
    ) {
        writeUpperInto(p.upperScratch, rawCallout, 26)
        val endIdx = p.upperScratch.length
        if (endIdx <= 0) return
        val popScale = 1f + 0.10f * sin((prog * 4f).coerceAtMost(1f) * PI.toFloat())
        p.textPaint.typeface = comicPunchTf
        val maxBoxW = w * 0.78f
        fitTextSize(p.textPaint, p.upperScratch, 0, endIdx, 34f * unit * popScale, 16f * unit, maxBoxW - 40f * unit)
        val tw = p.textPaint.measureText(p.upperScratch, 0, endIdx)
        val boxW = (tw + 44f * unit).coerceAtMost(maxBoxW)
        val boxH = 54f * unit

        canvas.save()
        canvas.rotate(-4.5f, cx, cy)
        val rect = p.rect.apply { set(cx - boxW * 0.5f, cy - boxH * 0.5f, cx + boxW * 0.5f, cy + boxH * 0.5f) }
        p.accentFill.color = colors.accentColor
        canvas.drawRoundRect(rect, 14f * unit, 14f * unit, p.accentFill)
        p.angerPaint.color = Color.WHITE
        p.angerPaint.strokeWidth = 3f * unit
        canvas.drawRoundRect(rect, 14f * unit, 14f * unit, p.angerPaint)
        p.textPaint.color = Color.WHITE
        canvas.drawText(p.upperScratch, 0, endIdx, rect.centerX(), rect.centerY() + 10f * unit, p.textPaint)
        canvas.restore()
    }

    private fun drawSafeZoneOverlay(
        canvas: Canvas,
        p: StickPaints,
        w: Float,
        h: Float,
        unit: Float
    ) {
        p.safeZoneBorderPaint.strokeWidth = 2.5f * unit
        // Right action rail (Like, Comment, Share)
        p.rect.set(w * 0.84f, h * 0.45f, w - 10f * unit, h * 0.84f)
        canvas.drawRoundRect(p.rect, 12f * unit, 12f * unit, p.safeZoneBgPaint)
        canvas.drawRoundRect(p.rect, 12f * unit, 12f * unit, p.safeZoneBorderPaint)

        // Bottom UI caption / handle zone
        p.rect.set(12f * unit, h * 0.84f, w - 12f * unit, h - 12f * unit)
        canvas.drawRoundRect(p.rect, 12f * unit, 12f * unit, p.safeZoneBgPaint)
        canvas.drawRoundRect(p.rect, 12f * unit, 12f * unit, p.safeZoneBorderPaint)

        p.textPaint.color = Color.WHITE
        p.textPaint.typeface = monoBoldTf
        p.textPaint.textSize = 16f * unit
        canvas.drawText("REELS / SHORTS UI SAFE ZONE", w * 0.5f, h * 0.825f, p.textPaint)
    }

    private fun drawParticles(
        canvas: Canvas,
        p: StickPaints,
        colors: ThemeColors,
        w: Float,
        h: Float,
        unit: Float,
        particles: ParticleOverlay,
        timeSec: Float
    ) {
        val count = 24
        for (i in 0 until count) {
            val seedX = ((i * 73 + 19) % 100) / 100f
            val speedFactor = 0.25f + ((i * 37) % 10) * 0.04f
            when (particles) {
                ParticleOverlay.RAIN -> {
                    val phaseY = (timeSec * (1.25f + speedFactor) + i * 0.17f) % 1.15f
                    val px = seedX * w
                    val py = phaseY * h
                    p.particleStroke.color = Color.argb(135, 96, 165, 250)
                    p.particleStroke.strokeWidth = 2.4f * unit
                    canvas.drawLine(px, py, px - 9f * unit, py + 28f * unit, p.particleStroke)
                }
                ParticleOverlay.CONFETTI -> {
                    val phaseY = (timeSec * speedFactor + i * 0.13f) % 1.12f
                    val px = seedX * w + sin(timeSec * 3.5f + i) * 14f * unit
                    val py = phaseY * h
                    p.particleFill.color = when (i % 4) {
                        0 -> colors.accentColor
                        1 -> Color.rgb(250, 204, 21)
                        2 -> Color.rgb(59, 130, 246)
                        else -> Color.rgb(16, 185, 129)
                    }
                    canvas.save()
                    canvas.rotate((timeSec * 140f + i * 35f) % 360f, px, py)
                    p.rect.set(px - 6f * unit, py - 3.5f * unit, px + 6f * unit, py + 3.5f * unit)
                    canvas.drawRect(p.rect, p.particleFill)
                    canvas.restore()
                }
                ParticleOverlay.FALLING_CASH -> {
                    val phaseY = (timeSec * (0.28f + speedFactor * 0.5f) + i * 0.19f) % 1.12f
                    val px = seedX * w + sin(timeSec * 2.2f + i * 0.8f) * 18f * unit
                    val py = phaseY * h
                    canvas.save()
                    canvas.rotate(sin(timeSec * 2.4f + i) * 24f, px, py)
                    p.rect.set(px - 14f * unit, py - 8f * unit, px + 14f * unit, py + 8f * unit)
                    p.particleFill.color = Color.rgb(34, 197, 94)
                    canvas.drawRect(p.rect, p.particleFill)
                    p.particleStroke.color = Color.rgb(21, 128, 61)
                    p.particleStroke.strokeWidth = 2f * unit
                    canvas.drawRect(p.rect, p.particleStroke)
                    p.textPaint.color = Color.WHITE
                    p.textPaint.typeface = monoBoldTf
                    p.textPaint.textSize = 12f * unit
                    canvas.drawText("$", px, py + 4f * unit, p.textPaint)
                    canvas.restore()
                }
                ParticleOverlay.FLOATING_HEARTS -> {
                    val phaseY = 1.08f - ((timeSec * speedFactor * 0.8f + i * 0.14f) % 1.16f)
                    val px = seedX * w + sin(timeSec * 2.6f + i) * 14f * unit
                    val py = phaseY * h
                    val r = 5.5f * unit
                    p.particleFill.color = Color.rgb(244, 63, 94)
                    canvas.drawCircle(px - r * 0.65f, py - r * 0.3f, r * 0.72f, p.particleFill)
                    canvas.drawCircle(px + r * 0.65f, py - r * 0.3f, r * 0.72f, p.particleFill)
                    p.pathA.reset()
                    p.pathA.moveTo(px - r * 1.32f, py)
                    p.pathA.lineTo(px, py + r * 1.55f)
                    p.pathA.lineTo(px + r * 1.32f, py)
                    p.pathA.close()
                    canvas.drawPath(p.pathA, p.particleFill)
                }
                ParticleOverlay.SPARKLES -> {
                    val px = seedX * w
                    val py = (((i * 47 + 11) % 88) / 100f + 0.06f) * h
                    val twinkle = abs(sin(timeSec * 4.2f + i * 0.9f))
                    val arm = (4f + twinkle * 9f) * unit
                    p.particleStroke.color = colors.accentColor
                    p.particleStroke.strokeWidth = 2.6f * unit
                    canvas.drawLine(px - arm, py, px + arm, py, p.particleStroke)
                    canvas.drawLine(px, py - arm, px, py + arm, p.particleStroke)
                }
                ParticleOverlay.NONE -> Unit
            }
        }
    }

    private fun drawReactionPipCam(
        canvas: Canvas,
        p: StickPaints,
        colors: ThemeColors,
        w: Float,
        h: Float,
        unit: Float,
        profile: CharacterProfile,
        isSpeaking: Boolean,
        lipAmp: Float,
        timeSec: Float,
        watermarkPosition: WatermarkPosition,
        hasHookBanner: Boolean
    ) {
        val boxW = 148f * unit
        val boxH = 164f * unit
        val marginX = 20f * unit
        val topY = if (hasHookBanner) 138f * unit else 44f * unit
        val placeLeft = watermarkPosition == WatermarkPosition.TOP_RIGHT
        val leftX = if (placeLeft) marginX else w - marginX - boxW
        val rect = p.rect.apply { set(leftX, topY, leftX + boxW, topY + boxH) }

        canvas.save()
        p.whiteFill.color = colors.wallColor
        canvas.drawRoundRect(rect, 14f * unit, 14f * unit, p.whiteFill)
        canvas.clipRect(rect)

        drawStickman(
            canvas = canvas,
            p = p,
            cx = rect.centerX(),
            footY = rect.bottom + 84f * unit,
            rawScale = 0.68f * unit,
            profile = profile,
            mood = if (isSpeaking) MoodPose.SMUG else MoodPose.SHOCKED,
            isSpeaking = isSpeaking,
            lipAmp = lipAmp,
            timeSec = timeSec,
            facingRight = placeLeft
        )
        canvas.restore()

        p.inkPaint.strokeWidth = 4f * unit
        canvas.drawRoundRect(rect, 14f * unit, 14f * unit, p.inkPaint)

        // Small "REACT" tag at bottom of PiP frame
        p.accentFill.color = colors.accentColor
        p.rect.set(leftX + 10f * unit, topY + boxH - 26f * unit, leftX + boxW - 10f * unit, topY + boxH - 4f * unit)
        canvas.drawRoundRect(p.rect, 6f * unit, 6f * unit, p.accentFill)
        p.textPaint.color = Color.WHITE
        p.textPaint.typeface = monoBoldTf
        p.textPaint.textSize = 13f * unit
        canvas.drawText("REACTION CAM", p.rect.centerX(), p.rect.centerY() + 4.5f * unit, p.textPaint)
    }

    private fun writeWatermarkHandleInto(dst: StringBuilder, raw: String): Int {
        dst.setLength(0)
        val firstNonWs = raw.indexOfFirst { !it.isWhitespace() }
        if (firstNonWs < 0) return 0
        val lastNonWs = raw.indexOfLast { !it.isWhitespace() } + 1
        if (raw[firstNonWs] != '@') dst.append('@')
        for (i in firstNonWs until lastNonWs) {
            dst.append(raw[i])
        }
        return dst.length
    }
}
