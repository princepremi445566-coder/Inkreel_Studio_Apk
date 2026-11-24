package com.example.ui

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.model.AmbientBed
import com.example.model.BgmMood
import com.example.model.CameraFraming
import com.example.model.CaptionFontStyle
import com.example.model.CaptionPosition
import com.example.model.CaptionStyle
import com.example.model.CastPreset
import com.example.model.CharacterActivity
import com.example.model.CharacterProfile
import com.example.model.ComicSfx
import com.example.model.ExportResolution
import com.example.model.MemeSticker
import com.example.model.MoodPose
import com.example.model.ParticleOverlay
import com.example.model.ReelColorTheme
import com.example.model.ReelLanguage
import com.example.model.ReelShot
import com.example.model.RoomSetting
import com.example.model.SfxTiming
import com.example.model.ShotTransition
import com.example.model.SocialMetaSuggestion
import com.example.model.SpeakerSlot
import com.example.model.WatermarkPosition
import com.example.ui.theme.HairlineBorder
import com.example.ui.theme.InkBlack
import com.example.ui.theme.SlateMuted
import com.example.ui.theme.StudioCrimson
import com.example.ui.theme.StudioCrimsonSoft
import com.example.ui.theme.StudioPaper
import com.example.ui.theme.StudioSurfaceMuted
import com.example.ui.theme.StudioWhite
import com.example.viewmodel.InkReelStudioState
import kotlinx.coroutines.flow.StateFlow
import java.util.Locale

@Composable
fun RightDirectorPanel(
    state: InkReelStudioState,
    castPresets: List<CastPreset>,
    micLevelFlow: StateFlow<Float>,
    micCountdownFlow: StateFlow<Int>,
    onSelectShot: (String) -> Unit,
    onUpdateShot: (ReelShot) -> Unit,
    onAddShot: () -> Unit,
    onDuplicateShot: (String) -> Unit,
    onMoveShotUp: (String) -> Unit,
    onMoveShotDown: (String) -> Unit,
    onDeleteShot: (String) -> Unit,
    onApplyViralPacingTightener: () -> Unit,
    onRevoiceShot: (String) -> Unit,
    onVoiceAllShots: () -> Unit,
    onStartMic: (String) -> Unit,
    onStopMic: (String) -> Unit,
    onPreviewSfx: (ComicSfx) -> Unit,
    onUpdateCharacter: (Int, CharacterProfile) -> Unit,
    onSaveCastPreset: (CharacterProfile) -> Unit,
    onApplyCastPreset: (Int, CastPreset) -> Unit,
    onDeleteCastPreset: (String) -> Unit,
    onDubProjectLanguage: (ReelLanguage) -> Unit,
    onUpdateSettings: (
        title: String?,
        setting: RoomSetting?,
        colorTheme: ReelColorTheme?,
        bgmMood: BgmMood?,
        bgmVolume: Float?,
        captionStyle: CaptionStyle?,
        captionFontStyle: CaptionFontStyle?,
        captionPosition: CaptionPosition?,
        introHookBanner: String?,
        enableOutro: Boolean?,
        outroText: String?,
        watermarkHandle: String?,
        watermarkPosition: WatermarkPosition?,
        exportResolution: ExportResolution?,
        ambientBed: AmbientBed?,
        ambientVolume: Float?
    ) -> Unit,
    onImportCustomBgm: (Uri) -> Unit,
    onClearCustomBgm: () -> Unit,
    onRewriteSocialMeta: () -> Unit,
    onApplySocialSuggestion: (SocialMetaSuggestion) -> Unit,
    onCopyFeedback: (String) -> Unit,
    onPermissionError: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var tabIndex by rememberSaveable { mutableIntStateOf(0) } // 0=Shots, 1=Cast & Audio

    Card(
        modifier = modifier.fillMaxSize(),
        colors = CardDefaults.cardColors(containerColor = StudioWhite),
        border = BorderStroke(1.dp, HairlineBorder),
        shape = MaterialTheme.shapes.large
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "Shot Director",
                style = MaterialTheme.typography.headlineSmall,
                color = InkBlack
            )

            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                SegmentedButton(
                    selected = tabIndex == 0,
                    onClick = { tabIndex = 0 },
                    shape = SegmentedButtonDefaults.itemShape(0, 2),
                    modifier = Modifier.testTag("director_tab_shots")
                ) {
                    Text("Shots (${state.project.shots.size})", style = MaterialTheme.typography.labelLarge)
                }
                SegmentedButton(
                    selected = tabIndex == 1,
                    onClick = { tabIndex = 1 },
                    shape = SegmentedButtonDefaults.itemShape(1, 2),
                    modifier = Modifier.testTag("director_tab_cast")
                ) {
                    Text("Cast & Style", style = MaterialTheme.typography.labelLarge)
                }
            }

            if (tabIndex == 0) {
                ShotsDirectorTab(
                    state = state,
                    micLevelFlow = micLevelFlow,
                    micCountdownFlow = micCountdownFlow,
                    onSelectShot = onSelectShot,
                    onUpdateShot = onUpdateShot,
                    onAddShot = onAddShot,
                    onDuplicateShot = onDuplicateShot,
                    onMoveShotUp = onMoveShotUp,
                    onMoveShotDown = onMoveShotDown,
                    onDeleteShot = onDeleteShot,
                    onApplyViralPacingTightener = onApplyViralPacingTightener,
                    onRevoiceShot = onRevoiceShot,
                    onVoiceAllShots = onVoiceAllShots,
                    onStartMic = onStartMic,
                    onStopMic = onStopMic,
                    onPreviewSfx = onPreviewSfx,
                    onPermissionError = onPermissionError
                )
            } else {
                CastAndAudioTab(
                    state = state,
                    castPresets = castPresets,
                    onUpdateCharacter = onUpdateCharacter,
                    onSaveCastPreset = onSaveCastPreset,
                    onApplyCastPreset = onApplyCastPreset,
                    onDeleteCastPreset = onDeleteCastPreset,
                    onDubProjectLanguage = onDubProjectLanguage,
                    onUpdateSettings = onUpdateSettings,
                    onImportCustomBgm = onImportCustomBgm,
                    onClearCustomBgm = onClearCustomBgm,
                    onRewriteSocialMeta = onRewriteSocialMeta,
                    onApplySocialSuggestion = onApplySocialSuggestion,
                    onCopyFeedback = onCopyFeedback
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun ShotsDirectorTab(
    state: InkReelStudioState,
    micLevelFlow: StateFlow<Float>,
    micCountdownFlow: StateFlow<Int>,
    onSelectShot: (String) -> Unit,
    onUpdateShot: (ReelShot) -> Unit,
    onAddShot: () -> Unit,
    onDuplicateShot: (String) -> Unit,
    onMoveShotUp: (String) -> Unit,
    onMoveShotDown: (String) -> Unit,
    onDeleteShot: (String) -> Unit,
    onApplyViralPacingTightener: () -> Unit,
    onRevoiceShot: (String) -> Unit,
    onVoiceAllShots: () -> Unit,
    onStartMic: (String) -> Unit,
    onStopMic: (String) -> Unit,
    onPreviewSfx: (ComicSfx) -> Unit,
    onPermissionError: (String) -> Unit
) {
    val context = LocalContext.current
    var pendingMicShotId by rememberSaveable { mutableStateOf<String?>(null) }
    val micLevel by micLevelFlow.collectAsStateWithLifecycle()
    val micCountdownSec by micCountdownFlow.collectAsStateWithLifecycle()

    val micPermLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        val sid = pendingMicShotId
        pendingMicShotId = null
        if (granted && sid != null) {
            onStartMic(sid)
        } else if (!granted) {
            onPermissionError("Microphone permission denied. Allow Microphone access in Settings to use Mic Dubbing.")
        }
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Button(
            onClick = onVoiceAllShots,
            enabled = !state.isSynthesizingAllVoices,
            modifier = Modifier.weight(1f).height(48.dp).testTag("voice_all_button"),
            colors = ButtonDefaults.buttonColors(containerColor = InkBlack, contentColor = StudioWhite)
        ) {
            if (state.isSynthesizingAllVoices) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), color = StudioWhite, strokeWidth = 2.dp)
                Spacer(Modifier.size(6.dp))
                Text("Voicing...", style = MaterialTheme.typography.bodySmall)
            } else {
                Icon(Icons.Filled.RecordVoiceOver, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.size(6.dp))
                Text("Voice All Shots", style = MaterialTheme.typography.bodySmall)
            }
        }

        OutlinedButton(
            onClick = onAddShot,
            modifier = Modifier.height(48.dp).testTag("add_shot_button")
        ) {
            Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.size(4.dp))
            Text("Shot", style = MaterialTheme.typography.bodySmall)
        }
    }

    OutlinedButton(
        onClick = onApplyViralPacingTightener,
        enabled = state.project.shots.isNotEmpty(),
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .testTag("viral_pacing_tightener_button")
    ) {
        Icon(Icons.Filled.Speed, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.size(6.dp))
        Text("Snappy 1.2x Viral Pace Cut (Trim Dead Air)", style = MaterialTheme.typography.bodySmall)
    }

    // Shot Timeline Strip
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        state.project.shots.forEachIndexed { idx, shot ->
            val isSelected = shot.shotId == state.selectedShotId
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSelectShot(shot.shotId) }
                    .testTag("shot_card_$idx"),
                colors = CardDefaults.cardColors(
                    containerColor = if (isSelected) InkBlack else StudioPaper
                ),
                border = BorderStroke(1.dp, if (isSelected) InkBlack else HairlineBorder)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "#${idx + 1} ${shot.speaker.label} • ${shot.framing.label}",
                            style = MaterialTheme.typography.labelMedium,
                            color = if (isSelected) StudioWhite else InkBlack
                        )
                        Text(
                            text = shot.transitionCard.ifBlank { shot.dialogue.ifBlank { "(Silent beat)" } },
                            style = MaterialTheme.typography.bodySmall,
                            color = if (isSelected) StudioSurfaceMuted else SlateMuted,
                            maxLines = 1
                        )
                    }
                    Text(
                        text = String.format(Locale.US, "%.1fs", shot.durationMs / 1000f),
                        style = MaterialTheme.typography.labelMedium,
                        color = if (isSelected) StudioWhite else SlateMuted
                    )
                }
            }
        }
    }

    // Active Shot Editor
    val activeIdx = state.project.shots.indexOfFirst { it.shotId == state.selectedShotId }
    val activeShot = state.project.shots.getOrNull(activeIdx)
    if (activeShot != null) {
        HorizontalDivider(color = HairlineBorder)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Edit Shot #${activeIdx + 1}", style = MaterialTheme.typography.titleSmall, color = InkBlack)
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(
                    onClick = { onMoveShotUp(activeShot.shotId) },
                    enabled = activeIdx > 0,
                    modifier = Modifier.size(48.dp).testTag("move_shot_up_button")
                ) {
                    Icon(
                        imageVector = Icons.Filled.ArrowUpward,
                        contentDescription = "Move Shot Up",
                        tint = if (activeIdx > 0) InkBlack else HairlineBorder
                    )
                }
                IconButton(
                    onClick = { onMoveShotDown(activeShot.shotId) },
                    enabled = activeIdx < state.project.shots.lastIndex,
                    modifier = Modifier.size(48.dp).testTag("move_shot_down_button")
                ) {
                    Icon(
                        imageVector = Icons.Filled.ArrowDownward,
                        contentDescription = "Move Shot Down",
                        tint = if (activeIdx < state.project.shots.lastIndex) InkBlack else HairlineBorder
                    )
                }
                IconButton(
                    onClick = { onDuplicateShot(activeShot.shotId) },
                    modifier = Modifier.size(48.dp).testTag("duplicate_shot_button")
                ) {
                    Icon(Icons.Filled.ContentCopy, contentDescription = "Duplicate Shot", tint = InkBlack)
                }
                if (state.project.shots.size > 1) {
                    IconButton(
                        onClick = { onDeleteShot(activeShot.shotId) },
                        modifier = Modifier.size(48.dp).testTag("delete_shot_button")
                    ) {
                        Icon(Icons.Filled.Delete, contentDescription = "Delete Shot", tint = SlateMuted)
                    }
                }
            }
        }

        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            SpeakerSlot.entries.forEach { slot ->
                FilterChip(
                    selected = activeShot.speaker == slot,
                    onClick = { onUpdateShot(activeShot.copy(speaker = slot)) },
                    label = { Text(slot.label, style = MaterialTheme.typography.bodySmall) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = InkBlack,
                        selectedLabelColor = StudioWhite
                    )
                )
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            EnumDropdown(
                label = "Camera Framing",
                selectedLabel = activeShot.framing.label,
                entries = CameraFraming.entries,
                labelOf = { it.label },
                onSelect = { onUpdateShot(activeShot.copy(framing = it)) },
                modifier = Modifier.weight(1f)
            )
            EnumDropdown(
                label = "Entry Transition",
                selectedLabel = activeShot.transition.label,
                entries = ShotTransition.entries,
                labelOf = { it.label },
                onSelect = { onUpdateShot(activeShot.copy(transition = it)) },
                modifier = Modifier.weight(1f)
            )
        }

        // Per-Shot Room Setting Override Dropdown
        var roomOverrideExpanded by remember { mutableStateOf(false) }
        ExposedDropdownMenuBox(
            expanded = roomOverrideExpanded,
            onExpandedChange = { roomOverrideExpanded = it }
        ) {
            OutlinedTextField(
                value = activeShot.roomOverride?.label ?: "Project Default (${state.project.setting.label})",
                onValueChange = {},
                readOnly = true,
                label = { Text("Shot Room Location") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = roomOverrideExpanded) },
                modifier = Modifier
                    .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                    .fillMaxWidth()
            )
            ExposedDropdownMenu(
                expanded = roomOverrideExpanded,
                onDismissRequest = { roomOverrideExpanded = false }
            ) {
                DropdownMenuItem(
                    text = { Text("Project Default (${state.project.setting.label})") },
                    onClick = {
                        onUpdateShot(activeShot.copy(roomOverride = null))
                        roomOverrideExpanded = false
                    }
                )
                RoomSetting.entries.forEach { room ->
                    DropdownMenuItem(
                        text = { Text(room.label) },
                        onClick = {
                            onUpdateShot(activeShot.copy(roomOverride = room))
                            roomOverrideExpanded = false
                        }
                    )
                }
            }
        }

        EnumDropdown(
            label = "Stickman Activity / Action",
            selectedLabel = activeShot.activity.label,
            entries = CharacterActivity.entries,
            labelOf = { it.label },
            onSelect = { onUpdateShot(activeShot.copy(activity = it)) }
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            EnumDropdown(
                label = "Expression / Pose",
                selectedLabel = activeShot.moodPose.label,
                entries = MoodPose.entries,
                labelOf = { it.label },
                onSelect = { onUpdateShot(activeShot.copy(moodPose = it)) },
                modifier = Modifier.weight(1f)
            )
            EnumDropdown(
                label = "Meme Sticker",
                selectedLabel = activeShot.sticker.label,
                entries = MemeSticker.entries,
                labelOf = { it.label },
                onSelect = { onUpdateShot(activeShot.copy(sticker = it)) },
                modifier = Modifier.weight(1f)
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            EnumDropdown(
                label = "Comic SFX",
                selectedLabel = activeShot.sfx.label,
                entries = ComicSfx.entries,
                labelOf = { it.label },
                onSelect = { onUpdateShot(activeShot.copy(sfx = it)) },
                modifier = Modifier.weight(1f)
            )
            IconButton(
                onClick = { onPreviewSfx(activeShot.sfx) },
                modifier = Modifier.size(48.dp)
            ) {
                Icon(Icons.Filled.PlayArrow, contentDescription = "Preview SFX", tint = InkBlack)
            }
        }

        if (activeShot.sfx != ComicSfx.NONE) {
            EnumDropdown(
                label = "SFX Trigger Timing",
                selectedLabel = activeShot.sfxTiming.label,
                entries = SfxTiming.entries,
                labelOf = { it.label },
                onSelect = { onUpdateShot(activeShot.copy(sfxTiming = it)) }
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            EnumDropdown(
                label = "Particle Mood",
                selectedLabel = activeShot.particles.label,
                entries = ParticleOverlay.entries,
                labelOf = { it.label },
                onSelect = { onUpdateShot(activeShot.copy(particles = it)) },
                modifier = Modifier.weight(1f)
            )

            var pipExpanded by remember { mutableStateOf(false) }
            ExposedDropdownMenuBox(
                expanded = pipExpanded,
                onExpandedChange = { pipExpanded = it },
                modifier = Modifier.weight(1f)
            ) {
                OutlinedTextField(
                    value = activeShot.reactionCamSlot?.let { "PiP: ${it.label}" } ?: "PiP Cam: Off",
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Reaction Cam") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = pipExpanded) },
                    modifier = Modifier
                        .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                        .fillMaxWidth()
                )
                ExposedDropdownMenu(
                    expanded = pipExpanded,
                    onDismissRequest = { pipExpanded = false }
                ) {
                    DropdownMenuItem(
                        text = { Text("Off (No PiP)") },
                        onClick = {
                            onUpdateShot(activeShot.copy(reactionCamSlot = null))
                            pipExpanded = false
                        }
                    )
                    listOf(SpeakerSlot.A, SpeakerSlot.B, SpeakerSlot.C).forEach { slot ->
                        DropdownMenuItem(
                            text = { Text("${slot.label} Reaction") },
                            onClick = {
                                onUpdateShot(activeShot.copy(reactionCamSlot = slot))
                                pipExpanded = false
                            }
                        )
                    }
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Dramatic Camera Shake", style = MaterialTheme.typography.bodyMedium, color = InkBlack)
            Switch(
                checked = activeShot.cameraShake,
                onCheckedChange = { onUpdateShot(activeShot.copy(cameraShake = it)) }
            )
        }

        OutlinedTextField(
            value = activeShot.impactCallout,
            onValueChange = { onUpdateShot(activeShot.copy(impactCallout = it)) },
            label = { Text("Comic Impact Callout Badge (Optional)") },
            placeholder = { Text("BOOM! / RIP! / PLOT TWIST!") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        OutlinedTextField(
            value = activeShot.dialogue,
            onValueChange = { onUpdateShot(activeShot.copy(dialogue = it)) },
            label = { Text("Spoken Dialogue") },
            minLines = 2,
            maxLines = 4,
            modifier = Modifier.fillMaxWidth()
        )

        OutlinedTextField(
            value = activeShot.phoneticHint,
            onValueChange = { onUpdateShot(activeShot.copy(phoneticHint = it)) },
            label = { Text("Pronunciation Hint (Optional Hinglish Tuning)") },
            placeholder = { Text("e.g., Kyaa baat hai, ach-chaa...") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        OutlinedTextField(
            value = activeShot.speechBubble,
            onValueChange = { onUpdateShot(activeShot.copy(speechBubble = it)) },
            label = { Text("Speech Bubble Text") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        if (activeShot.framing == CameraFraming.TIME_SKIP_CARD || activeShot.transitionCard.isNotBlank()) {
            OutlinedTextField(
                value = activeShot.transitionCard,
                onValueChange = { onUpdateShot(activeShot.copy(transitionCard = it)) },
                label = { Text("Day / Time-Skip Flash Card") },
                placeholder = { Text("NEXT DAY") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        }

        Column {
            Text(
                text = "Duration: ${String.format(Locale.US, "%.1fs", activeShot.durationMs / 1000f)} (${activeShot.audioSourceLabel})",
                style = MaterialTheme.typography.labelMedium,
                color = SlateMuted
            )
            Slider(
                value = (activeShot.durationMs / 1000f).coerceIn(0.8f, 20.0f),
                onValueChange = { sec -> onUpdateShot(activeShot.copy(durationMs = (sec * 1000f).toLong())) },
                valueRange = 0.8f..20.0f
            )
        }

        val isVoicingThis = state.voicingShotIds.contains(activeShot.shotId)
        val isRecordingThis = state.recordingShotId == activeShot.shotId

        if (isRecordingThis) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = StudioCrimsonSoft),
                border = BorderStroke(1.dp, StudioCrimson)
            ) {
                Column(
                    modifier = Modifier.padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = if (micCountdownSec > 0) {
                            "Get ready to speak in $micCountdownSec..."
                        } else {
                            "Recording live mic dub — speak now!"
                        },
                        style = MaterialTheme.typography.labelLarge,
                        color = StudioCrimson
                    )
                    if (micCountdownSec == 0) {
                        LinearProgressIndicator(
                            progress = { micLevel.coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth().height(6.dp),
                            color = StudioCrimson,
                            trackColor = StudioWhite
                        )
                    }
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = { onRevoiceShot(activeShot.shotId) },
                enabled = !isVoicingThis && activeShot.speaker != SpeakerSlot.SILENT && activeShot.dialogue.isNotBlank(),
                modifier = Modifier.weight(1f).height(48.dp).testTag("revoice_shot_button"),
                colors = ButtonDefaults.buttonColors(containerColor = InkBlack, contentColor = StudioWhite)
            ) {
                if (isVoicingThis) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), color = StudioWhite, strokeWidth = 2.dp)
                } else {
                    Text("Voice Shot", style = MaterialTheme.typography.bodySmall)
                }
            }

            OutlinedButton(
                onClick = {
                    if (isRecordingThis) {
                        onStopMic(activeShot.shotId)
                    } else {
                        val granted = ContextCompat.checkSelfPermission(
                            context,
                            Manifest.permission.RECORD_AUDIO
                        ) == PackageManager.PERMISSION_GRANTED
                        if (granted) {
                            onStartMic(activeShot.shotId)
                        } else {
                            pendingMicShotId = activeShot.shotId
                            micPermLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    }
                },
                modifier = Modifier.weight(1f).height(48.dp).testTag("mic_dub_button")
            ) {
                Icon(
                    imageVector = if (isRecordingThis) Icons.Filled.Stop else Icons.Filled.Mic,
                    contentDescription = null,
                    tint = if (isRecordingThis) StudioCrimson else InkBlack,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.size(4.dp))
                Text(
                    text = if (isRecordingThis) "Stop Mic" else "Mic Dub",
                    color = if (isRecordingThis) StudioCrimson else InkBlack,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}
