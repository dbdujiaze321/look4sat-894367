/*
 * Look4Sat. Amateur radio satellite tracker and pass predictor.
 * Copyright (C) 2019-2026 Arty Bishop and contributors.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.rtbishop.look4sat.feature.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.rtbishop.look4sat.core.domain.model.OtherSettings
import com.rtbishop.look4sat.core.domain.model.ThemeColor
import com.rtbishop.look4sat.core.domain.model.ThemeOverrides
import com.rtbishop.look4sat.core.presentation.R

/** Which token the colour dialog is currently editing. */
private enum class ThemeTarget { PRIMARY, BACKGROUND, RADAR_TRACK }

/**
 * Swatches instead of a full HSV picker.
 *
 * A satellite app has no colour-grading workflow, and a free picker mostly produces unreadable
 * combinations (dark text on a dark surface). Twelve tested swatches plus the ability to hand-edit
 * the exported JSON covers the same need without shipping a wheel widget.
 */
private val swatches = listOf(
    "#FFE082", "#FFB300", "#FF7043", "#DC0000",
    "#66BB6A", "#26A69A", "#42A5F5", "#648FFF",
    "#AB47BC", "#8D6E63", "#E0E0E0", "#121212"
)

@Composable
internal fun ThemeEditor(
    settings: OtherSettings,
    onAction: (SettingsAction) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val overrides = remember(settings.themeJson) { ThemeOverrides.parse(settings.themeJson) }
    var editing by remember { mutableStateOf<ThemeTarget?>(null) }

    fun apply(next: ThemeOverrides) = onAction(SettingsAction.SetThemeJson(ThemeOverrides.encode(next)))

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.openOutputStream(uri)?.use { stream ->
                stream.write(ThemeOverrides.encode(overrides).toByteArray())
            }
        }
    }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val text = runCatching {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() }
        }.getOrNull()
        // A JSON object that parses to "no overrides" is ambiguous; require an object literal and
        // only then trust the result, so picking a random text file cannot silently wipe a theme.
        if (text == null || !text.contains('{')) {
            onAction(SettingsAction.ShowToast(context.getString(R.string.prefs_theme_import_failed)))
        } else {
            onAction(SettingsAction.SetThemeJson(text))
        }
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.fillMaxWidth()
    ) {
        ThemeSwatchRow(
            label = stringResource(R.string.prefs_theme_primary),
            value = overrides.primary,
            onClick = { editing = ThemeTarget.PRIMARY },
            modifier = Modifier.weight(1f)
        )
        ThemeSwatchRow(
            label = stringResource(R.string.prefs_theme_background),
            value = overrides.background,
            onClick = { editing = ThemeTarget.BACKGROUND },
            modifier = Modifier.weight(1f)
        )
        ThemeSwatchRow(
            label = stringResource(R.string.prefs_theme_radar_track),
            value = overrides.radarTrack,
            onClick = { editing = ThemeTarget.RADAR_TRACK },
            modifier = Modifier.weight(1f)
        )
    }
    Text(
        text = stringResource(R.string.prefs_theme_radius) +
            "  ${overrides.cornerRadius() ?: 12}"
    )
    Slider(
        value = (overrides.cornerRadius() ?: 12).toFloat(),
        onValueChange = { apply(overrides.copy(cornerRadiusDp = it.toInt())) },
        valueRange = ThemeOverrides.MIN_RADIUS_DP.toFloat()..ThemeOverrides.MAX_RADIUS_DP.toFloat(),
        steps = ThemeOverrides.MAX_RADIUS_DP - ThemeOverrides.MIN_RADIUS_DP - 1
    )
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        TextButton(onClick = { exportLauncher.launch("look4sat-theme.json") }) {
            Text(text = stringResource(R.string.prefs_theme_export))
        }
        TextButton(onClick = { importLauncher.launch(arrayOf("application/json", "text/plain")) }) {
            Text(text = stringResource(R.string.prefs_theme_import))
        }
        TextButton(onClick = { onAction(SettingsAction.SetThemeJson("")) }) {
            Text(text = stringResource(R.string.prefs_theme_reset))
        }
    }

    val target = editing
    if (target != null) {
        val current = when (target) {
            ThemeTarget.PRIMARY -> overrides.primary
            ThemeTarget.BACKGROUND -> overrides.background
            ThemeTarget.RADAR_TRACK -> overrides.radarTrack
        }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text(text = stringResource(R.string.prefs_theme_pick)) },
            text = {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    swatches.forEach { hex ->
                        val argb = ThemeColor.parse(hex)
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                                .background(if (argb != null) Color(argb) else Color.Transparent)
                                .border(
                                    width = if (current == hex) 3.dp else 1.dp,
                                    color = MaterialTheme.colorScheme.outline,
                                    shape = CircleShape
                                )
                                .clickable {
                                    editing = null
                                    apply(
                                        when (target) {
                                            ThemeTarget.PRIMARY -> overrides.copy(primary = hex)
                                            ThemeTarget.BACKGROUND -> overrides.copy(background = hex)
                                            ThemeTarget.RADAR_TRACK -> overrides.copy(radarTrack = hex)
                                        }
                                    )
                                }
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    editing = null
                    apply(
                        when (target) {
                            ThemeTarget.PRIMARY -> overrides.copy(primary = null)
                            ThemeTarget.BACKGROUND -> overrides.copy(background = null)
                            ThemeTarget.RADAR_TRACK -> overrides.copy(radarTrack = null)
                        }
                    )
                }) {
                    Text(text = stringResource(R.string.prefs_theme_use_preset))
                }
            }
        )
    }
}

@Composable
private fun ThemeSwatchRow(
    label: String,
    value: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val argb = ThemeColor.parse(value)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .clip(MaterialTheme.shapes.small)
            .clickable { onClick() }
            .padding(vertical = 6.dp, horizontal = 4.dp)
    ) {
        Box(
            modifier = Modifier
                .size(20.dp)
                .clip(CircleShape)
                .background(if (argb != null) Color(argb) else MaterialTheme.colorScheme.surfaceVariant)
                .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(text = label, style = MaterialTheme.typography.labelMedium)
    }
}
