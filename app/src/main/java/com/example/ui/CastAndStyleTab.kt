package com.example.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Translate
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.model.AmbientBed
import com.example.model.BgmMood
import com.example.model.CaptionFontStyle
import com.example.model.CaptionPosition
import com.example.model.CaptionStyle
import com.example.model.CastPreset
import com.example.model.CharacterGender
import com.example.model.CharacterProfile
import com.example.model.CharacterProp
import com.example.model.ExportResolution
import com.example.model.FacialHair
import com.example.model.HairStyle
import com.example.model.OutfitColorOption
import com.example.model.ReelColorTheme
import com.example.model.ReelLanguage
import com.example.model.RoomSetting
import com.example.model.SocialMetaSuggestion
import com.example.model.VoiceCatalog
import com.example.model.VoiceEffect
import com.example.model.WatermarkPosition
import com.example.ui.theme.HairlineBorder
import com.example.ui.theme.InkBlack
import com.example.ui.theme.SlateMuted
import com.example.ui.theme.StudioPaper
import com.example.ui.theme.StudioSurfaceMuted
import com.example.ui.theme.StudioWhite
import com.example.viewmodel.InkReelStudioState
import java.util.Locale
import kotlin.math.roundToInt

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CastAndAudioTab(
    state: InkReelStudioState,
    castPresets: List<CastPreset>,
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
    onCopyFeedback: (String) -> Unit
) {
    val context = LocalContext.current
    val proj = state.project
    var presetTargetSlot by remember { mutableIntStateOf(0) }

    val bgmPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            onImportCustomBgm(uri)
        }
    }

    // 1-Tap Auto-Dub Language Bar
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = StudioSurfaceMuted),
        border = BorderStroke(1.dp, HairlineBorder)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(Icons.Filled.Translate, contentDescription = null, modifier = Modifier.size(16.dp), tint = InkBlack)
                Text(
                    text = if (state.isDubbingLanguage) "Dubbing & Re-Voicing..." else "1-Tap Auto-Dub Entire Reel (${proj.language})",
                    style = MaterialTheme.typography.titleSmall,
                    color = InkBlack
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                ReelLanguage.entries.forEach { lang ->
                    OutlinedButton(
                        onClick = { onDubProjectLanguage(lang) },
                        enabled = !state.isDubbingLanguage && !state.isSynthesizingAllVoices,
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .testTag("dub_language_${lang.name}")
                    ) {
                        Text(lang.label, maxLines = 1, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }

    // Reusable Cast Library Presets
    if (castPresets.isNotEmpty()) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = StudioPaper),
            border = BorderStroke(1.dp, HairlineBorder)
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("Saved Cast Library (Tap to Apply)", style = MaterialTheme.typography.titleSmall, color = InkBlack)
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    listOf("Slot A", "Slot B", "Slot C").forEachIndexed { sIdx, label ->
                        SegmentedButton(
                            selected = presetTargetSlot == sIdx,
                            onClick = { presetTargetSlot = sIdx },
                            shape = SegmentedButtonDefaults.itemShape(sIdx, 3)
                        ) {
                            Text(label, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    castPresets.forEach { preset ->
                        FilterChip(
                            selected = false,
                            onClick = { onApplyCastPreset(presetTargetSlot, preset) },
                            label = { Text(preset.presetLabel, style = MaterialTheme.typography.bodySmall) },
                            trailingIcon = {
                                Icon(
                                    imageVector = Icons.Filled.Close,
                                    contentDescription = "Delete Cast Preset",
                                    modifier = Modifier
                                        .size(16.dp)
                                        .clickable { onDeleteCastPreset(preset.id) }
                                )
                            }
                        )
                    }
                }
            }
        }
    }

    // Characters A, B, C
    proj.characters.forEachIndexed { idx, char ->
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = StudioPaper),
            border = BorderStroke(1.dp, HairlineBorder)
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "Character ${char.slot.name} — ${char.name} (${char.gender.label})",
                    style = MaterialTheme.typography.titleSmall,
                    color = InkBlack
                )

                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    CharacterGender.entries.forEachIndexed { gIdx, genderOption ->
                        SegmentedButton(
                            selected = char.gender == genderOption,
                            onClick = {
                                if (char.gender != genderOption) {
                                    val updatedVoice = if (genderOption == CharacterGender.FEMALE && !VoiceCatalog.isFemaleVoice(char.voiceName)) {
                                        VoiceCatalog.defaultVoiceFor(CharacterGender.FEMALE)
                                    } else if (genderOption == CharacterGender.MALE && VoiceCatalog.isFemaleVoice(char.voiceName)) {
                                        VoiceCatalog.defaultVoiceFor(CharacterGender.MALE)
                                    } else char.voiceName

                                    val updatedHair = if (genderOption == CharacterGender.FEMALE &&
                                        char.hairStyle in listOf(HairStyle.BALD, HairStyle.SPIKY, HairStyle.PARTED, HairStyle.MESSY)
                                    ) {
                                        HairStyle.LONG_WAVY
                                    } else if (genderOption == CharacterGender.MALE &&
                                        char.hairStyle in listOf(HairStyle.LONG_WAVY, HairStyle.TWIN_BRAIDS, HairStyle.PONYTAIL, HairStyle.BOB)
                                    ) {
                                        HairStyle.PARTED
                                    } else char.hairStyle

                                    onUpdateCharacter(
                                        idx,
                                        char.copy(
                                            gender = genderOption,
                                            voiceName = updatedVoice,
                                            hairStyle = updatedHair,
                                            facialHair = if (genderOption == CharacterGender.FEMALE) FacialHair.NONE else char.facialHair
                                        )
                                    )
                                }
                            },
                            shape = SegmentedButtonDefaults.itemShape(gIdx, CharacterGender.entries.size),
                            modifier = Modifier.testTag("char_${char.slot.name}_gender_${genderOption.name}")
                        ) {
                            Text(genderOption.label, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = char.name,
                        onValueChange = { onUpdateCharacter(idx, char.copy(name = it)) },
                        label = { Text("Name") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = char.role,
                        onValueChange = { onUpdateCharacter(idx, char.copy(role = it)) },
                        label = { Text("Role") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                }

                EnumDropdown(
                    label = "Voice Actor",
                    selectedLabel = VoiceCatalog.actors.firstOrNull { it.id == char.voiceName }
                        ?.let { "${it.displayName} (${it.tone})" } ?: char.voiceName,
                    entries = VoiceCatalog.actors,
                    labelOf = { "${it.displayName} (${it.tone})" },
                    onSelect = { onUpdateCharacter(idx, char.copy(voiceName = it.id)) }
                )

                EnumDropdown(
                    label = "Voice Effect",
                    selectedLabel = char.voiceEffect.label,
                    entries = VoiceEffect.entries,
                    labelOf = { it.label },
                    onSelect = { onUpdateCharacter(idx, char.copy(voiceEffect = it)) }
                )

                Text(
                    text = "Voice Speed: ${String.format(Locale.US, "%.2fx", char.voiceSpeed)}",
                    style = MaterialTheme.typography.labelMedium,
                    color = SlateMuted
                )
                Slider(
                    value = char.voiceSpeed,
                    onValueChange = { onUpdateCharacter(idx, char.copy(voiceSpeed = it)) },
                    valueRange = 0.85f..1.30f
                )

                val stInt = char.voicePitchSemitones.roundToInt()
                val stLabel = if (stInt > 0) "+$stInt st" else "$stInt st"
                Text(
                    text = "Voice Pitch: $stLabel",
                    style = MaterialTheme.typography.labelMedium,
                    color = SlateMuted
                )
                Slider(
                    value = char.voicePitchSemitones.coerceIn(-6f, 6f),
                    onValueChange = { onUpdateCharacter(idx, char.copy(voicePitchSemitones = it.roundToInt().toFloat())) },
                    valueRange = -6f..6f,
                    steps = 11
                )

                Text(
                    text = "Character Height: ${String.format(Locale.US, "%.2fx", char.heightScale)}",
                    style = MaterialTheme.typography.labelMedium,
                    color = SlateMuted
                )
                Slider(
                    value = char.heightScale.coerceIn(0.78f, 1.18f),
                    onValueChange = { onUpdateCharacter(idx, char.copy(heightScale = it)) },
                    valueRange = 0.78f..1.18f
                )

                Text("Outfit Accent Color", style = MaterialTheme.typography.labelMedium, color = SlateMuted)
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    OutfitColorOption.entries.forEach { option ->
                        val isSelected = char.outfitColorHex.equals(option.hex, ignoreCase = true)
                        val dotColor = remember(option.hex) {
                            if (option.hex.isBlank()) InkBlack else Color(android.graphics.Color.parseColor(option.hex))
                        }
                        FilterChip(
                            selected = isSelected,
                            onClick = { onUpdateCharacter(idx, char.copy(outfitColorHex = option.hex)) },
                            leadingIcon = {
                                Box(
                                    modifier = Modifier
                                        .size(12.dp)
                                        .background(dotColor, CircleShape)
                                        .border(1.dp, StudioWhite, CircleShape)
                                )
                            },
                            label = { Text(option.label, style = MaterialTheme.typography.bodySmall) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = InkBlack,
                                selectedLabelColor = StudioWhite
                            )
                        )
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    EnumDropdown(
                        label = "Hair",
                        selectedLabel = char.hairStyle.label,
                        entries = HairStyle.entries,
                        labelOf = { it.label },
                        onSelect = { onUpdateCharacter(idx, char.copy(hairStyle = it)) },
                        modifier = Modifier.weight(1f)
                    )
                    if (char.gender == CharacterGender.MALE) {
                        EnumDropdown(
                            label = "Facial Hair",
                            selectedLabel = char.facialHair.label,
                            entries = FacialHair.entries,
                            labelOf = { it.label },
                            onSelect = { onUpdateCharacter(idx, char.copy(facialHair = it)) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
                EnumDropdown(
                    label = "Held Prop",
                    selectedLabel = char.prop.label,
                    entries = CharacterProp.entries,
                    labelOf = { it.label },
                    onSelect = { onUpdateCharacter(idx, char.copy(prop = it)) }
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Wear Glasses", style = MaterialTheme.typography.bodyMedium, color = InkBlack)
                    Switch(
                        checked = char.hasGlasses,
                        onCheckedChange = { onUpdateCharacter(idx, char.copy(hasGlasses = it)) }
                    )
                }
                OutlinedButton(
                    onClick = { onSaveCastPreset(char) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .testTag("save_cast_preset_${char.slot.name}")
                ) {
                    Icon(Icons.Filled.PersonAdd, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.size(6.dp))
                    Text("Save ${char.name} to Cast Library", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }

    HorizontalDivider(color = HairlineBorder)
    Text("Theme, Intro/Outro & Audio", style = MaterialTheme.typography.titleSmall, color = InkBlack)

    OutlinedTextField(
        value = proj.title,
        onValueChange = {
            onUpdateSettings(it, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null)
        },
        label = { Text("Reel Title") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )

    OutlinedTextField(
        value = proj.introHookBanner,
        onValueChange = {
            onUpdateSettings(null, null, null, null, null, null, null, null, it, null, null, null, null, null, null, null)
        },
        label = { Text("Top Viral Hook Banner (Optional)") },
        placeholder = { Text("WAIT FOR THE END!") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        EnumDropdown(
            label = "Default Room",
            selectedLabel = proj.setting.label,
            entries = RoomSetting.entries,
            labelOf = { it.label },
            onSelect = {
                onUpdateSettings(null, it, null, null, null, null, null, null, null, null, null, null, null, null, null, null)
            },
            modifier = Modifier.weight(1f)
        )
        EnumDropdown(
            label = "Color Theme",
            selectedLabel = proj.colorTheme.label,
            entries = ReelColorTheme.entries,
            labelOf = { it.label },
            onSelect = {
                onUpdateSettings(null, null, it, null, null, null, null, null, null, null, null, null, null, null, null, null)
            },
            modifier = Modifier.weight(1f)
        )
    }

    EnumDropdown(
        label = "BGM Mood (Auto-Ducks on Speech)",
        selectedLabel = proj.bgmMood.label,
        entries = BgmMood.entries,
        labelOf = { it.label },
        onSelect = {
            onUpdateSettings(null, null, null, it, null, null, null, null, null, null, null, null, null, null, null, null)
        }
    )

    // Custom Phone Audio BGM (Zero-Permission SAF Picker)
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        OutlinedButton(
            onClick = { bgmPickerLauncher.launch(arrayOf("audio/*")) },
            enabled = !state.isImportingBgm,
            modifier = Modifier
                .weight(1f)
                .height(48.dp)
                .testTag("import_custom_bgm_button")
        ) {
            if (state.isImportingBgm) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.size(8.dp))
                Text("Importing Audio...")
            } else {
                Icon(Icons.Filled.MusicNote, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(6.dp))
                Text(
                    text = if (proj.customBgmName.isNotBlank()) "BGM: ${proj.customBgmName}" else "Import Phone BGM (MP3/M4A)",
                    maxLines = 1
                )
            }
        }
        if (proj.customBgmName.isNotBlank()) {
            IconButton(
                onClick = onClearCustomBgm,
                modifier = Modifier.size(48.dp).testTag("clear_custom_bgm_button")
            ) {
                Icon(Icons.Filled.Clear, contentDescription = "Remove Custom BGM", tint = InkBlack)
            }
        }
    }

    Text(
        text = "BGM Volume: ${(proj.bgmVolume * 100).toInt()}%",
        style = MaterialTheme.typography.labelMedium,
        color = SlateMuted
    )
    Slider(
        value = proj.bgmVolume,
        onValueChange = {
            onUpdateSettings(null, null, null, null, it, null, null, null, null, null, null, null, null, null, null, null)
        },
        valueRange = 0f..1f
    )

    EnumDropdown(
        label = "Room Ambient Soundbed",
        selectedLabel = proj.ambientBed.label,
        entries = AmbientBed.entries,
        labelOf = { it.label },
        onSelect = {
            onUpdateSettings(null, null, null, null, null, null, null, null, null, null, null, null, null, null, it, null)
        }
    )

    if (proj.ambientBed != AmbientBed.NONE) {
        Text(
            text = "Ambience Volume: ${(proj.ambientVolume * 100).toInt()}%",
            style = MaterialTheme.typography.labelMedium,
            color = SlateMuted
        )
        Slider(
            value = proj.ambientVolume,
            onValueChange = {
                onUpdateSettings(null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, it)
            },
            valueRange = 0f..1f
        )
    }

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        EnumDropdown(
            label = "Caption Style",
            selectedLabel = proj.captionStyle.label,
            entries = CaptionStyle.entries,
            labelOf = { it.label },
            onSelect = {
                onUpdateSettings(null, null, null, null, null, it, null, null, null, null, null, null, null, null, null, null)
            },
            modifier = Modifier.weight(1f)
        )
        EnumDropdown(
            label = "Caption Font",
            selectedLabel = proj.captionFontStyle.label,
            entries = CaptionFontStyle.entries,
            labelOf = { it.label },
            onSelect = {
                onUpdateSettings(null, null, null, null, null, null, it, null, null, null, null, null, null, null, null, null)
            },
            modifier = Modifier.weight(1f)
        )
    }

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        EnumDropdown(
            label = "Caption Position",
            selectedLabel = proj.captionPosition.label,
            entries = CaptionPosition.entries,
            labelOf = { it.label },
            onSelect = {
                onUpdateSettings(null, null, null, null, null, null, null, it, null, null, null, null, null, null, null, null)
            },
            modifier = Modifier.weight(1f)
        )
        EnumDropdown(
            label = "Watermark Corner",
            selectedLabel = proj.watermarkPosition.label,
            entries = WatermarkPosition.entries,
            labelOf = { it.label },
            onSelect = {
                onUpdateSettings(null, null, null, null, null, null, null, null, null, null, null, null, it, null, null, null)
            },
            modifier = Modifier.weight(1f)
        )
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("Include Animated Outro CTA Card", style = MaterialTheme.typography.bodyMedium, color = InkBlack)
        Switch(
            checked = proj.enableOutro,
            onCheckedChange = {
                onUpdateSettings(null, null, null, null, null, null, null, null, null, it, null, null, null, null, null, null)
            }
        )
    }

    if (proj.enableOutro) {
        OutlinedTextField(
            value = proj.outroText,
            onValueChange = {
                onUpdateSettings(null, null, null, null, null, null, null, null, null, null, it, null, null, null, null, null)
            },
            label = { Text("Outro CTA Text") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
    }

    OutlinedTextField(
        value = proj.watermarkHandle,
        onValueChange = {
            onUpdateSettings(null, null, null, null, null, null, null, null, null, null, null, it, null, null, null, null)
        },
        label = { Text("Channel Watermark (@handle)") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )

    EnumDropdown(
        label = "MP4 Export Resolution",
        selectedLabel = proj.exportResolution.label,
        entries = ExportResolution.entries,
        labelOf = { it.label },
        onSelect = {
            onUpdateSettings(null, null, null, null, null, null, null, null, null, null, null, null, null, it, null, null)
        }
    )

    // Viral Title, Caption & Hashtags + AI Rewriter
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = StudioSurfaceMuted),
        border = BorderStroke(1.dp, HairlineBorder)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("Viral Social Caption & Hashtags", style = MaterialTheme.typography.titleSmall, color = InkBlack)
            Text(proj.socialCaption, style = MaterialTheme.typography.bodySmall, color = InkBlack)
            Text(
                text = proj.hashtags.joinToString(" "),
                style = MaterialTheme.typography.labelMedium,
                color = SlateMuted
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = onRewriteSocialMeta,
                    enabled = !state.isRewritingSocial,
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .testTag("ai_rewrite_social_button")
                ) {
                    if (state.isRewritingSocial) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.size(6.dp))
                        Text("Rewriting...", maxLines = 1)
                    } else {
                        Icon(Icons.Filled.AutoAwesome, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.size(6.dp))
                        Text("AI Rewrite 3 Hooks", maxLines = 1, style = MaterialTheme.typography.bodySmall)
                    }
                }
                OutlinedButton(
                    onClick = {
                        val clip = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                        val copyText = "${proj.title}\n\n${proj.socialCaption}\n\n${proj.hashtags.joinToString(" ")}"
                        clip?.setPrimaryClip(ClipData.newPlainText("InkReel Caption", copyText))
                        onCopyFeedback("Copied viral title, caption & hashtags!")
                    },
                    modifier = Modifier.height(48.dp)
                ) {
                    Icon(Icons.Filled.ContentCopy, contentDescription = "Copy Caption & Hashtags", modifier = Modifier.size(16.dp))
                    Spacer(Modifier.size(6.dp))
                    Text("Copy", style = MaterialTheme.typography.bodySmall)
                }
            }

            if (state.socialSuggestions.isNotEmpty()) {
                Text("Tap a pack to apply:", style = MaterialTheme.typography.labelMedium, color = SlateMuted)
                state.socialSuggestions.forEach { pack ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onApplySocialSuggestion(pack) },
                        colors = CardDefaults.cardColors(containerColor = StudioWhite),
                        border = BorderStroke(1.dp, HairlineBorder)
                    ) {
                        Column(
                            modifier = Modifier.padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(pack.title, style = MaterialTheme.typography.labelLarge, color = InkBlack)
                            Text(pack.caption, style = MaterialTheme.typography.bodySmall, color = InkBlack)
                            Text(pack.hashtags.joinToString(" "), style = MaterialTheme.typography.labelSmall, color = SlateMuted)
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun <T> EnumDropdown(
    label: String,
    selectedLabel: String,
    entries: List<T>,
    labelOf: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = modifier
    ) {
        OutlinedTextField(
            value = selectedLabel,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                .fillMaxWidth()
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            entries.forEach { item ->
                DropdownMenuItem(
                    text = { Text(labelOf(item)) },
                    onClick = {
                        onSelect(item)
                        expanded = false
                    }
                )
            }
        }
    }
}
