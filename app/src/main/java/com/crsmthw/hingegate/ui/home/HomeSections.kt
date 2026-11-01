package com.crsmthw.hingegate.ui.home

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.NotificationsOff
import androidx.compose.material.icons.rounded.Photo
import androidx.compose.material.icons.rounded.ScreenRotation
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.Wallpaper
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import androidx.compose.material3.ToggleButtonShapes
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
import com.crsmthw.hingegate.domain.SwapFailure
import com.crsmthw.hingegate.domain.SwapSetWording
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
        // A connected three-way choice (M3 Expressive): toggle buttons sharing their edges, exactly one checked.
        // Icon above a label that wraps as needed, so the three fit side by side on the narrow cover screen too;
        // the row's intrinsic height keeps all three as tall as the tallest label (large font sizes included).
        Row(
            horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
            modifier = Modifier.height(IntrinsicSize.Min),
        ) {
            ModeButton(
                label = stringResource(R.string.mode_overlay),
                icon = Icons.Rounded.Layers,
                checked = mode == CrackMode.OVERLAY,
                shapes = ButtonGroupDefaults.connectedLeadingButtonShapes(),
                onClick = { onMode(CrackMode.OVERLAY) },
                modifier = Modifier.weight(1f).fillMaxHeight(),
            )
            ModeButton(
                label = stringResource(R.string.mode_wallpaper),
                icon = Icons.Rounded.Wallpaper,
                checked = mode == CrackMode.WALLPAPER,
                shapes = ButtonGroupDefaults.connectedMiddleButtonShapes(),
                onClick = { onMode(CrackMode.WALLPAPER) },
                modifier = Modifier.weight(1f).fillMaxHeight(),
            )
            ModeButton(
                label = stringResource(R.string.mode_swap),
                icon = Icons.Rounded.Photo,
                checked = mode == CrackMode.SWAP,
                shapes = ButtonGroupDefaults.connectedTrailingButtonShapes(),
                onClick = { onMode(CrackMode.SWAP) },
                modifier = Modifier.weight(1f).fillMaxHeight(),
            )
        }
        Text(
            stringResource(
                when (mode) {
                    CrackMode.OVERLAY -> R.string.mode_overlay_hint
                    CrackMode.WALLPAPER -> R.string.mode_wallpaper_hint
                    CrackMode.SWAP -> R.string.mode_swap_hint
                },
            ),
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
    shapes: ToggleButtonShapes,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ToggleButton(
        checked = checked,
        onCheckedChange = { onClick() },
        modifier = modifier.semantics { role = Role.RadioButton },
        shapes = shapes,
        contentPadding = ModeButtonPadding,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, contentDescription = null)
            Text(label, textAlign = TextAlign.Center)
        }
    }
}

/**
 * A permission with its plain-words explanation always on the card (docs/SWAP-MODE.md §8): what it is for and
 * what it is NOT used for. Allow never opens Settings itself; it asks for the confirmation dialog first.
 */
@Composable
private fun PermissionRow(
    title: String,
    status: String,
    explanation: String,
    granted: Boolean,
    onAllow: () -> Unit,
    optional: Boolean = false,
) {
    StatusRow(
        title = title,
        value = status,
        action = when {
            granted -> null
            optional -> { { OutlinedButton(onClick = onAllow) { Text(stringResource(R.string.action_allow)) } } }
            else -> { { Button(onClick = onAllow) { Text(stringResource(R.string.action_allow)) } } }
        },
    )
    Text(explanation, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/**
 * The confirmation before any Settings screen or system prompt (docs/SWAP-MODE.md §8): the same explanation as on
 * the card, what happens next, and "Continue to Settings" (or "Continue" before a system prompt) / "Not now".
 */
@Composable
internal fun PermissionDialog(
    kind: PermissionKind,
    systemPrompt: Boolean,
    isSamsung: Boolean,
    onContinue: () -> Unit,
    onDismiss: () -> Unit,
) {
    val title = when (kind) {
        PermissionKind.ALL_FILES -> R.string.permission_all_files_dialog_title
        PermissionKind.OVERLAY -> R.string.permission_overlay_dialog_title
        PermissionKind.NOTIFICATIONS -> R.string.permission_notifications_dialog_title
    }
    val explanation = when (kind) {
        PermissionKind.ALL_FILES -> R.string.permission_all_files_explanation
        PermissionKind.OVERLAY -> R.string.permission_overlay_explanation
        PermissionKind.NOTIFICATIONS -> R.string.permission_notifications_explanation
    }
    val next = when (PermissionNext.of(kind, systemPrompt, isSamsung)) {
        PermissionNext.SYSTEM_PROMPT -> R.string.permission_next_prompt
        PermissionNext.SETTINGS_PAGE -> R.string.permission_next_settings
        PermissionNext.SETTINGS_LIST -> R.string.permission_next_settings_list
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(ItemSpacing)) {
                Text(stringResource(explanation))
                Text(stringResource(next))
            }
        },
        confirmButton = {
            TextButton(onClick = onContinue) {
                Text(stringResource(if (systemPrompt) R.string.action_continue else R.string.action_continue_to_settings))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_not_now)) } },
    )
}

@Composable
internal fun OverlaySetup(
    canDrawOverlays: Boolean,
    isSamsung: Boolean,
    notificationsAllowed: Boolean,
    serviceRunning: Boolean,
    onAllowOverlay: () -> Unit,
    onAllowNotifications: () -> Unit,
) {
    PermissionRow(
        title = stringResource(R.string.overlay_permission_title),
        status = stringResource(if (canDrawOverlays) R.string.permission_granted else R.string.overlay_permission_missing),
        explanation = stringResource(R.string.permission_overlay_explanation),
        granted = canDrawOverlays,
        onAllow = onAllowOverlay,
    )
    // Android's own notice about the crack's window gives the prank away in the shade (DESIGN §18.24).
    if (OverlayNoticeTip.shows(isSamsung)) InfoLine(Icons.Rounded.NotificationsOff, stringResource(R.string.overlay_notice_tip))
    ServiceRows(notificationsAllowed, serviceRunning, onAllowNotifications)
}

/** The optional notifications permission and the foreground service's state: shared by the two modes that use it. */
@Composable
private fun ServiceRows(notificationsAllowed: Boolean, serviceRunning: Boolean, onAllowNotifications: () -> Unit) {
    PermissionRow(
        title = stringResource(R.string.notifications_title),
        status = stringResource(if (notificationsAllowed) R.string.notifications_on else R.string.notifications_off),
        explanation = stringResource(R.string.permission_notifications_explanation),
        granted = notificationsAllowed,
        onAllow = onAllowNotifications,
        optional = true,
    )
    StatusRow(
        title = stringResource(R.string.service_title),
        value = stringResource(if (serviceRunning) R.string.service_running else R.string.service_stopped),
    )
}

/**
 * The Real wallpaper mode's setup (docs/SWAP-MODE.md §6, §8, §9): All files access explained and asked for,
 * the captured original with "Use my current wallpaper", "Put my wallpaper back", what is not touched, and that
 * this mode does not follow rotation.
 */
@Composable
internal fun SwapSetup(
    allFilesAccess: Boolean,
    isSamsung: Boolean,
    posture: Posture,
    original: Bitmap?,
    capturedAtEpochMs: Long?,
    notificationsAllowed: Boolean,
    serviceRunning: Boolean,
    onAllowAllFiles: () -> Unit,
    onCapture: () -> Unit,
    onRestore: () -> Unit,
    onAllowNotifications: () -> Unit,
) {
    PermissionRow(
        title = stringResource(R.string.permission_all_files_title),
        status = stringResource(if (allFilesAccess) R.string.permission_granted else R.string.permission_all_files_missing),
        explanation = stringResource(R.string.permission_all_files_explanation),
        granted = allFilesAccess,
        onAllow = onAllowAllFiles,
    )
    val captured = if (capturedAtEpochMs != null) {
        val locale = LocalConfiguration.current.locales[0]
        val now = remember(capturedAtEpochMs) { System.currentTimeMillis() }
        val zone = remember { ZoneId.systemDefault() }
        stringResource(R.string.swap_original_captured, LogTime.format(capturedAtEpochMs, now, zone, locale))
    } else {
        stringResource(R.string.swap_original_none)
    }
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Thumbnail(original, stringResource(R.string.swap_original_title))
        Column(Modifier.weight(1f).padding(horizontal = ItemSpacing)) {
            Text(stringResource(R.string.swap_original_title), style = MaterialTheme.typography.bodyLarge)
            Text(captured, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    val availability = CaptureAvailability.of(allFilesAccess, posture)
    Row(horizontalArrangement = Arrangement.spacedBy(ItemSpacing), modifier = Modifier.fillMaxWidth()) {
        Button(onClick = onCapture, enabled = availability == CaptureAvailability.READY, modifier = Modifier.weight(1f)) {
            Text(stringResource(R.string.action_use_current_wallpaper), textAlign = TextAlign.Center)
        }
        OutlinedButton(onClick = onRestore, enabled = capturedAtEpochMs != null, modifier = Modifier.weight(1f)) {
            Text(stringResource(R.string.action_put_back), textAlign = TextAlign.Center)
        }
    }
    when (availability) {
        CaptureAvailability.NEEDS_PERMISSION -> Hint(stringResource(R.string.swap_capture_needs_permission))
        CaptureAvailability.NEEDS_UNFOLD -> Hint(stringResource(R.string.swap_capture_needs_unfold))
        CaptureAvailability.READY -> Unit
    }
    // Samsung keeps a wallpaper per panel; elsewhere one home wallpaper serves both screens (docs/SWAP-MODE.md §1).
    InfoLine(Icons.Rounded.Info, stringResource(if (isSamsung) R.string.swap_untouched_note else R.string.swap_untouched_note_shared))
    InfoLine(Icons.Rounded.ScreenRotation, stringResource(R.string.swap_rotation_note))
    ServiceRows(notificationsAllowed, serviceRunning, onAllowNotifications)
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** A short fact about the mode, with an icon in front. */
@Composable
private fun InfoLine(icon: ImageVector, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(InfoIconSize))
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f).padding(start = ItemSpacing),
        )
    }
}

/** A rounded square with the image cropped into it, or an image icon when there is none. */
@Composable
private fun Thumbnail(image: Bitmap?, description: String) {
    Box(
        modifier = Modifier
            .size(ThumbnailSize)
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
        contentAlignment = Alignment.Center,
    ) {
        if (image != null) {
            val bitmap = remember(image) { image.asImageBitmap() }
            Image(bitmap = bitmap, contentDescription = description, contentScale = ContentScale.Crop, modifier = Modifier.size(ThumbnailSize))
        } else {
            Icon(Icons.Rounded.Image, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * The Live wallpaper mode's setup. The status speaks only about the INNER screen (where the crack shows), and
 * only while unfolded: One UI keeps a wallpaper per panel and answers for the panel in use. "Set as wallpaper"
 * stays available either way (the other panel or the lock screen may still need it); it is the main action only
 * while the inner screen is known not to have it.
 */
@Composable
internal fun WallpaperSetup(
    thumbnails: Map<ImageSlot, Bitmap>,
    posture: Posture,
    wallpaperSet: Boolean,
    onChoose: (ImageSlot) -> Unit,
    onRemove: (ImageSlot) -> Unit,
    onSetWallpaper: () -> Unit,
) {
    ImageSlotRow(stringResource(R.string.image_main), thumbnails[ImageSlot.MAIN], { onChoose(ImageSlot.MAIN) }, { onRemove(ImageSlot.MAIN) })
    ImageSlotRow(stringResource(R.string.image_cover), thumbnails[ImageSlot.COVER], { onChoose(ImageSlot.COVER) }, { onRemove(ImageSlot.COVER) })
    val status = WallpaperStatus.of(posture, wallpaperSet)
    StatusRow(
        title = stringResource(R.string.wallpaper_status_title),
        value = stringResource(
            when (status) {
                WallpaperStatus.INNER_SET -> R.string.wallpaper_inner_set
                WallpaperStatus.INNER_NOT_SET -> R.string.wallpaper_inner_not_set
                WallpaperStatus.UNFOLD_TO_CHECK -> R.string.wallpaper_unfold_to_check
            },
        ),
        action = if (status == WallpaperStatus.INNER_NOT_SET) {
            { Button(onClick = onSetWallpaper) { Text(stringResource(R.string.action_set_wallpaper)) } }
        } else {
            { OutlinedButton(onClick = onSetWallpaper) { Text(stringResource(R.string.action_set_wallpaper)) } }
        },
    )
}

@Composable
private fun ImageSlotRow(label: String, thumbnail: Bitmap?, onChoose: () -> Unit, onRemove: () -> Unit, modifier: Modifier = Modifier) {
    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Thumbnail(thumbnail, label)
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
    appEnabled: Boolean,
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
        val showNow = ShowNowAvailability.of(appEnabled, posture)
        Row(horizontalArrangement = Arrangement.spacedBy(ItemSpacing), modifier = Modifier.fillMaxWidth()) {
            Button(onClick = onShowNow, enabled = showNow == ShowNowAvailability.READY, modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.action_show_now), textAlign = TextAlign.Center)
            }
            OutlinedButton(onClick = onClear, enabled = crackActive, modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.action_clear_crack), textAlign = TextAlign.Center)
            }
        }
        when (showNow) {
            ShowNowAvailability.NEEDS_SWITCH_ON -> Hint(stringResource(R.string.show_now_needs_on))
            ShowNowAvailability.NEEDS_UNFOLD -> Hint(stringResource(R.string.show_now_hint))
            ShowNowAvailability.READY -> Unit
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
    is LogPhrase.SwapSet -> stringResource(swapSetSentence(phrase.wording), phrase.which, phrase.id)
    is LogPhrase.SwapCaptured -> stringResource(R.string.log_swap_captured, phrase.id)
    is LogPhrase.SwapRecaptured -> stringResource(R.string.log_swap_recaptured, phrase.id)
    is LogPhrase.SwapIdAdopted -> stringResource(R.string.log_swap_id_adopted, phrase.id)
    LogPhrase.SwapStageMissed -> stringResource(R.string.log_swap_stage_missed)
    is LogPhrase.SwapFailed -> stringResource(
        when (phrase.failure) {
            SwapFailure.LIVE_WALLPAPER -> R.string.log_swap_failed_live
            SwapFailure.NO_PERMISSION -> R.string.log_swap_failed_permission
            SwapFailure.NOT_UNFOLDED -> R.string.log_swap_failed_unfold
            SwapFailure.ALREADY_OURS -> R.string.log_swap_failed_ours
            SwapFailure.NO_PANEL_SIZE -> R.string.log_swap_failed_size
            SwapFailure.READ_FAILED -> R.string.log_swap_failed_read
            SwapFailure.SET_FAILED -> R.string.log_swap_failed_set
            SwapFailure.CHANGED_DURING_READ -> R.string.log_swap_failed_changed
            SwapFailure.BUILT_IN_WALLPAPER -> R.string.log_swap_failed_built_in
        },
    )
}

/** Each Real wallpaper set reads as: which copy, when, then the flags passed and the id returned. */
private fun swapSetSentence(wording: SwapSetWording): Int = when (wording) {
    SwapSetWording.STAGED_CRACKED -> R.string.log_swap_staged_cracked
    SwapSetWording.STAGED_CLEAN -> R.string.log_swap_staged_clean
    SwapSetWording.SET_CRACKED -> R.string.log_swap_set_cracked
    SwapSetWording.SET_CLEAN -> R.string.log_swap_set_clean
    SwapSetWording.LATE_CRACKED -> R.string.log_swap_late_cracked
    SwapSetWording.RESTORED -> R.string.log_swap_restored
}

private val SectionPadding = 20.dp
private val ItemSpacing = 12.dp
private val ThumbnailSize = 56.dp
private val InfoIconSize = 20.dp
private val ModeButtonPadding = PaddingValues(horizontal = 8.dp, vertical = 12.dp)
