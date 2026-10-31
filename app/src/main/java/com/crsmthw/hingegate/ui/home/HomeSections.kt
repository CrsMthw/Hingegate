package com.crsmthw.hingegate.ui.home

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.Wallpaper
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.rememberSliderState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ToggleButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.crsmthw.hingegate.R
import com.crsmthw.hingegate.data.CrackMode
import com.crsmthw.hingegate.data.Settings
import com.crsmthw.hingegate.domain.CrackSpec
import com.crsmthw.hingegate.domain.HingeSource
import com.crsmthw.hingegate.domain.ImageSlot
import com.crsmthw.hingegate.domain.LogEntry
import com.crsmthw.hingegate.domain.LogPhrase
import com.crsmthw.hingegate.domain.Posture
import com.crsmthw.hingegate.domain.PostureAuthority
import com.crsmthw.hingegate.posture.PostureSnapshot
import com.crsmthw.hingegate.render.CrackPalette
import java.time.ZoneId
import kotlin.math.roundToInt

// ── Building blocks ──────────────────────────────────────────────────────────

/** A titled card; every section of the screen is one. */
@Composable
internal fun SectionCard(title: String, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        shape = MaterialTheme.shapes.extraLarge,
    ) {
        Column(Modifier.padding(SectionPadding), verticalArrangement = Arrangement.spacedBy(ItemSpacing)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            content()
        }
    }
}

/** A whole-row switch: the row is the touch target, the switch only shows the state. */
@Composable
private fun SwitchRow(title: String, summary: String, checked: Boolean, onChecked: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChecked),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = ItemSpacing)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(summary, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}

/** A label with its value below and an optional action at the end. */
@Composable
private fun StatusRow(title: String, value: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = ItemSpacing)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        action?.invoke()
    }
}

// ── Sections ─────────────────────────────────────────────────────────────────

@Composable
internal fun PreviewSection(
    spec: CrackSpec,
    palette: CrackPalette,
    snapshot: PostureSnapshot,
    image: Bitmap?,
    fallbackPanelWidth: Int,
    fallbackPanelHeight: Int,
    flickerKey: Int,
    onShuffle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SectionCard(stringResource(R.string.preview_title), modifier) {
        CrackPreview(
            spec = spec,
            palette = palette,
            panelWidthPx = snapshot.innerWidth ?: fallbackPanelWidth,
            panelHeightPx = snapshot.innerHeight ?: fallbackPanelHeight,
            coverAspect = snapshot.coverAspect,
            image = image,
            flickerKey = flickerKey,
            description = stringResource(R.string.preview_description),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.preview_note),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f).padding(end = ItemSpacing),
            )
            FilledTonalIconButton(onClick = onShuffle) {
                Icon(Icons.Rounded.Shuffle, contentDescription = stringResource(R.string.action_shuffle))
            }
        }
    }
}

@Composable
internal fun MasterSection(enabled: Boolean, onEnabled: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (enabled) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest,
        ),
        shape = MaterialTheme.shapes.extraLarge,
    ) {
        SwitchRow(
            title = stringResource(R.string.master_title),
            summary = stringResource(R.string.master_summary),
            checked = enabled,
            onChecked = onEnabled,
            modifier = Modifier.padding(SectionPadding),
        )
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun ModeSection(
    mode: CrackMode,
    onMode: (CrackMode) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    SectionCard(stringResource(R.string.mode_title), modifier) {
        // A connected two-way choice (M3 Expressive): two toggle buttons sharing an edge, exactly one checked.
        Row(horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)) {
            ModeButton(
                label = stringResource(R.string.mode_overlay),
                icon = Icons.Rounded.Layers,
                checked = mode == CrackMode.OVERLAY,
                leading = true,
                onClick = { onMode(CrackMode.OVERLAY) },
                modifier = Modifier.weight(1f),
            )
            ModeButton(
                label = stringResource(R.string.mode_wallpaper),
                icon = Icons.Rounded.Wallpaper,
                checked = mode == CrackMode.WALLPAPER,
                leading = false,
                onClick = { onMode(CrackMode.WALLPAPER) },
                modifier = Modifier.weight(1f),
            )
        }
        Text(
            stringResource(if (mode == CrackMode.OVERLAY) R.string.mode_overlay_hint else R.string.mode_wallpaper_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        HorizontalDivider()
        content()
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ModeButton(
    label: String,
    icon: ImageVector,
    checked: Boolean,
    leading: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ToggleButton(
        checked = checked,
        onCheckedChange = { onClick() },
        modifier = modifier.semantics { role = Role.RadioButton },
        shapes = if (leading) {
            ButtonGroupDefaults.connectedLeadingButtonShapes()
        } else {
            ButtonGroupDefaults.connectedTrailingButtonShapes()
        },
    ) {
        Icon(icon, contentDescription = null)
        Spacer(Modifier.size(ButtonDefaults.IconSpacing))
        Text(label, maxLines = 1)
    }
}

@Composable
internal fun OverlaySetup(
    canDrawOverlays: Boolean,
    notificationsAllowed: Boolean,
    serviceRunning: Boolean,
    onAllowOverlay: () -> Unit,
    onAllowNotifications: () -> Unit,
) {
    StatusRow(
        title = stringResource(R.string.overlay_permission_title),
        value = stringResource(if (canDrawOverlays) R.string.overlay_permission_granted else R.string.overlay_permission_missing),
        action = if (canDrawOverlays) null else { { Button(onClick = onAllowOverlay) { Text(stringResource(R.string.action_allow)) } } },
    )
    StatusRow(
        title = stringResource(R.string.notifications_title),
        value = stringResource(if (notificationsAllowed) R.string.notifications_on else R.string.notifications_off),
        action = if (notificationsAllowed) {
            null
        } else {
            { OutlinedButton(onClick = onAllowNotifications) { Text(stringResource(R.string.action_allow)) } }
        },
    )
    StatusRow(
        title = stringResource(R.string.service_title),
        value = stringResource(if (serviceRunning) R.string.service_running else R.string.service_stopped),
    )
}

@Composable
internal fun WallpaperSetup(
    thumbnails: Map<ImageSlot, Bitmap>,
    wallpaperSet: Boolean,
    onChoose: (ImageSlot) -> Unit,
    onRemove: (ImageSlot) -> Unit,
    onSetWallpaper: () -> Unit,
) {
    ImageSlotRow(stringResource(R.string.image_main), thumbnails[ImageSlot.MAIN], { onChoose(ImageSlot.MAIN) }, { onRemove(ImageSlot.MAIN) })
    ImageSlotRow(stringResource(R.string.image_cover), thumbnails[ImageSlot.COVER], { onChoose(ImageSlot.COVER) }, { onRemove(ImageSlot.COVER) })
    StatusRow(
        title = stringResource(R.string.wallpaper_status_title),
        value = stringResource(if (wallpaperSet) R.string.wallpaper_is_set else R.string.wallpaper_not_set),
        action = if (wallpaperSet) {
            null
        } else {
            { Button(onClick = onSetWallpaper) { Text(stringResource(R.string.action_set_wallpaper)) } }
        },
    )
}

@Composable
private fun ImageSlotRow(label: String, thumbnail: Bitmap?, onChoose: () -> Unit, onRemove: () -> Unit, modifier: Modifier = Modifier) {
    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(ThumbnailSize)
                .clip(MaterialTheme.shapes.medium)
                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
            contentAlignment = Alignment.Center,
        ) {
            if (thumbnail != null) {
                val bitmap = remember(thumbnail) { thumbnail.asImageBitmap() }
                Image(bitmap = bitmap, contentDescription = label, contentScale = ContentScale.Crop, modifier = Modifier.size(ThumbnailSize))
            } else {
                Icon(Icons.Rounded.Image, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Column(Modifier.weight(1f).padding(horizontal = ItemSpacing)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Text(
                stringResource(if (thumbnail != null) R.string.image_set else R.string.image_none),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (thumbnail != null) {
            TextButton(onClick = onRemove) { Text(stringResource(R.string.action_remove)) }
        }
        FilledTonalButton(onClick = onChoose) { Text(stringResource(R.string.action_choose)) }
    }
}

@Composable
internal fun FrequencySection(
    chancePercent: Int,
    guaranteeAfter: Int,
    onChance: (Int) -> Unit,
    onGuarantee: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    // The sliders move locally while dragged and write once on release: one DataStore write per gesture.
    val chance = rememberSliderState(
        value = chancePercent.toFloat(),
        steps = (Settings.CHANCE_MAX - Settings.CHANCE_MIN) / Settings.CHANCE_STEP - 1,
        trackRange = Settings.CHANCE_MIN.toFloat()..Settings.CHANCE_MAX.toFloat(),
    )
    val guarantee = rememberSliderState(
        value = guaranteeAfter.toFloat(),
        steps = Settings.GUARANTEE_MAX - Settings.GUARANTEE_MIN - 1,
        trackRange = Settings.GUARANTEE_MIN.toFloat()..Settings.GUARANTEE_MAX.toFloat(),
    )
    // Follow the stored values (first read, or a change made elsewhere).
    LaunchedEffect(chancePercent) { chance.value = chancePercent.toFloat() }
    LaunchedEffect(guaranteeAfter) { guarantee.value = guaranteeAfter.toFloat() }

    SectionCard(stringResource(R.string.frequency_title), modifier) {
        StatusRow(
            title = stringResource(R.string.chance_title),
            value = stringResource(R.string.chance_value, Settings.clampChance(chance.value.roundToInt())),
        )
        Slider(
            state = chance,
            onValueChange = { chance.value = it },
            onValueChangeFinished = { onChance(chance.value.roundToInt()) },
        )
        val count = guarantee.value.roundToInt()
        StatusRow(
            title = stringResource(R.string.guarantee_title),
            value = if (count == 0) {
                stringResource(R.string.guarantee_off)
            } else {
                pluralStringResource(R.plurals.guarantee_value, count, count)
            },
        )
        Slider(
            state = guarantee,
            onValueChange = { guarantee.value = it },
            onValueChangeFinished = { onGuarantee(guarantee.value.roundToInt()) },
        )
    }
}

@Composable
internal fun TryItSection(
    crackNextUnfold: Boolean,
    posture: Posture,
    crackActive: Boolean,
    onCrackNext: (Boolean) -> Unit,
    onShowNow: () -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SectionCard(stringResource(R.string.try_title), modifier) {
        SwitchRow(
            title = stringResource(R.string.crack_next_title),
            summary = stringResource(R.string.crack_next_summary),
            checked = crackNextUnfold,
            onChecked = onCrackNext,
        )
        val unfolded = posture == Posture.UNFOLDED
        Row(horizontalArrangement = Arrangement.spacedBy(ItemSpacing), modifier = Modifier.fillMaxWidth()) {
            Button(onClick = onShowNow, enabled = unfolded, modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.action_show_now), textAlign = TextAlign.Center)
            }
            OutlinedButton(onClick = onClear, enabled = crackActive, modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.action_clear_crack), textAlign = TextAlign.Center)
            }
        }
        if (!unfolded) {
            Text(
                stringResource(R.string.show_now_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
internal fun DetectionSection(snapshot: PostureSnapshot, modifier: Modifier = Modifier) {
    SectionCard(stringResource(R.string.detection_title), modifier) {
        StatusRow(
            title = stringResource(R.string.detection_posture),
            value = stringResource(
                when (snapshot.posture) {
                    Posture.UNKNOWN -> R.string.posture_unknown
                    Posture.FOLDED -> R.string.posture_folded
                    Posture.UNFOLDED -> R.string.posture_unfolded
                },
            ),
        )
        StatusRow(
            title = stringResource(R.string.detection_method),
            value = stringResource(
                when (snapshot.authority) {
                    PostureAuthority.DISPLAY_SIZE -> R.string.method_display
                    PostureAuthority.WINDOW_MANAGER -> R.string.method_window_manager
                    PostureAuthority.NONE -> R.string.method_learning
                },
            ),
        )
        StatusRow(
            title = stringResource(R.string.detection_hinge),
            value = stringResource(
                when (snapshot.hinge?.source) {
                    HingeSource.WINDOW_MANAGER -> R.string.hinge_window_manager
                    HingeSource.ESTIMATED -> R.string.hinge_estimated
                    null -> R.string.hinge_none
                },
            ),
        )
    }
}

@Composable
internal fun LogSection(entries: List<LogEntry>, onClear: () -> Unit, modifier: Modifier = Modifier) {
    SectionCard(stringResource(R.string.log_title), modifier) {
        if (entries.isEmpty()) {
            Text(
                stringResource(R.string.log_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            val locale = LocalConfiguration.current.locales[0]
            val now = remember(entries) { System.currentTimeMillis() }
            val zone = remember { ZoneId.systemDefault() }
            entries.forEach { entry ->
                Row(Modifier.fillMaxWidth()) {
                    Text(
                        LogTime.format(entry.timeEpochMs, now, zone, locale),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(end = ItemSpacing),
                    )
                    Text(logSentence(entry), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                }
            }
            TextButton(onClick = onClear, modifier = Modifier.align(Alignment.End)) {
                Text(stringResource(R.string.action_clear_log))
            }
        }
    }
}

@Composable
private fun logSentence(entry: LogEntry): String = when (val phrase = LogPhrase.of(entry)) {
    is LogPhrase.CrackedRolled -> stringResource(R.string.log_cracked_rolled, phrase.roll, phrase.chancePercent)
    is LogPhrase.CrackedGuaranteed ->
        pluralStringResource(R.plurals.log_cracked_guaranteed, phrase.afterClean, phrase.afterClean)
    LogPhrase.CrackedForced -> stringResource(R.string.log_cracked_forced)
    is LogPhrase.Clean -> pluralStringResource(R.plurals.log_clean, phrase.inARow, phrase.roll, phrase.inARow)
    LogPhrase.UnfoldedWhileOff -> stringResource(R.string.log_unfolded_off)
    LogPhrase.FoldCleared -> stringResource(R.string.log_fold_cleared)
    LogPhrase.Folded -> stringResource(R.string.log_folded)
    LogPhrase.ShownByHand -> stringResource(R.string.log_shown_by_hand)
    LogPhrase.ClearedByHand -> stringResource(R.string.log_cleared_by_hand)
}

private val SectionPadding = 20.dp
private val ItemSpacing = 12.dp
private val ThumbnailSize = 56.dp
