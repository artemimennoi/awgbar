package io.github.artemimennoi.awgbar

import android.os.Build
import android.text.format.Formatter
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.artemimennoi.awgbar.icons.*

// The screen has the Mac menu's layout, block by block: header, power button, tunnels, "Bypass",
// one info line, two buttons at the bottom. What they're made of is Material, so the app is
// recognizably AWG Bar and still an Android app.

private val RowHeight = 56.dp
private val RowGap = 2.dp

/** "AWG Bar" on the left; a status dot and what the VPN is doing on the right. */
@Composable
fun Header(busy: Boolean, activeTitle: String?) {
    val status = when {
        busy -> stringResource(R.string.please_wait)
        activeTitle != null -> stringResource(R.string.connected, activeTitle)
        else -> stringResource(R.string.disconnected)
    }
    val dot = when {
        busy -> Status.colors.busy
        activeTitle != null -> Status.colors.connected
        else -> MaterialTheme.colorScheme.outline
    }
    Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineSmall)
        Row(Modifier.weight(1f).padding(start = 16.dp), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).background(dot, CircleShape))
            Spacer(Modifier.width(8.dp))
            Text(
                status, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * The app's shield on a round button, with the halo breathing behind it while connected or busy.
 * Connected tints the button with the status green rather than the theme's primary, so with
 * wallpaper colors the button, halo and shield still agree.
 */
@Composable
fun PowerButton(shield: ShieldState, enabled: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val status = Status.colors
    val description = stringResource(if (shield == ShieldState.On) R.string.disconnect else R.string.connect)
    val connected = status.connected.copy(alpha = 0.25f).compositeOver(scheme.surfaceContainer)
    val container by animateColorAsState(if (shield == ShieldState.On) connected else scheme.surfaceContainer, label = "power")
    Box(modifier.size(160.dp), contentAlignment = Alignment.Center) {
        if (shield != ShieldState.Off) {
            Halo(if (shield == ShieldState.Busy) status.busy else status.connected, fast = shield == ShieldState.Busy, Modifier.matchParentSize())
        }
        Surface(
            onClick = onToggle, enabled = enabled, shape = CircleShape, color = container,
            modifier = Modifier.matchParentSize().semantics { contentDescription = description },
        ) {
            Box(contentAlignment = Alignment.Center) {
                AnimatedContent(
                    shield,
                    transitionSpec = { (fadeIn() + scaleIn(initialScale = 0.85f)) togetherWith (fadeOut() + scaleOut(targetScale = 0.85f)) },
                    label = "shield",
                    modifier = Modifier.alpha(if (!enabled && shield == ShieldState.Off) 0.38f else 1f),
                ) { StatusShield(it, size = 88.dp) }
            }
        }
    }
}

/**
 * The tunnels in one card: a radio button and the name per row, with the selection's tint sliding
 * to the chosen row; while connected the chosen row is the connected tunnel. Rename and Delete are
 * behind each row's ⋮, or a long press. Switching needs the VPN off, so while connected the other
 * rows are dimmed and a tap on one only says why.
 */
@Composable
fun TunnelList(
    /** File id to title, in display order. */
    tunnels: Map<String, String>,
    selected: String?,
    locked: Boolean,
    busy: Boolean,
    onSelect: (String) -> Unit,
    onLocked: () -> Unit,
    onRename: (String) -> Unit,
    onDelete: (String) -> Unit,
) {
    val names = tunnels.keys.toList()
    val index = names.indexOf(selected)
    val highlight by animateDpAsState(
        (RowHeight + RowGap) * index.coerceAtLeast(0), spring(Spring.DampingRatioLowBouncy, Spring.StiffnessMediumLow), label = "selection",
    )
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth()) {
        Box(Modifier.verticalScroll(rememberScrollState()).padding(6.dp)) {
            if (index >= 0) {
                Box(
                    Modifier.offset(y = highlight).fillMaxWidth().height(RowHeight)
                        .background(MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.shapes.medium),
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(RowGap)) {
                names.forEach { name ->
                    val chosen = name == selected
                    TunnelRow(
                        title = tunnels.getValue(name), chosen = chosen,
                        dimmed = locked && !chosen, enabled = !busy,
                        onClick = { if (!locked) onSelect(name) else if (!chosen) onLocked() },
                        onRename = { onRename(name) }, onDelete = { onDelete(name) },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TunnelRow(
    title: String,
    chosen: Boolean,
    dimmed: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    // Only the radio and the name dim while connected: the ⋮ still works.
    val dim = if (dimmed) 0.38f else 1f
    Row(
        Modifier.fillMaxWidth().height(RowHeight).clip(MaterialTheme.shapes.medium)
            .combinedClickable(enabled = enabled, onLongClick = { menu = true }, onClick = onClick)
            .padding(start = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = chosen, onClick = null, modifier = Modifier.padding(12.dp).alpha(dim))
        Text(
            title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
            color = if (chosen) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f).alpha(dim),
        )
        Box {
            IconButton({ menu = true }, enabled = enabled) {
                Icon(Icons.Rounded.MoreVert, stringResource(R.string.more), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            AppMenu(menu, { menu = false }) {
                AppMenuItem(stringResource(R.string.rename), Icons.Rounded.Edit) { menu = false; onRename() }
                AppMenuItem(stringResource(R.string.delete), Icons.Rounded.DeleteOutline, destructive = true) { menu = false; onDelete() }
            }
        }
    }
}

/** No tunnels yet: what to import and where from, in the tunnels' card. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EmptyState(onClipboard: () -> Unit, onQr: () -> Unit, onFile: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth()) {
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(stringResource(R.string.no_tunnels), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.no_tunnels_hint), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(16.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ImportButton(Icons.Rounded.ContentPaste, R.string.from_clipboard, onClipboard)
                ImportButton(Icons.Rounded.QrCodeScanner, R.string.scan_qr, onQr)
                ImportButton(Icons.Rounded.FolderOpen, R.string.from_file, onFile)
            }
        }
    }
}

@Composable
private fun ImportButton(icon: ImageVector, text: Int, onClick: () -> Unit) {
    FilledTonalButton(onClick, contentPadding = ButtonDefaults.ButtonWithIconContentPadding) {
        Icon(icon, null, Modifier.size(ButtonDefaults.IconSize))
        Spacer(Modifier.width(ButtonDefaults.IconSpacing))
        Text(stringResource(text))
    }
}

/** "Bypass" and the subnets that go around the tunnel, on one line; a tap opens [BypassDialog]. */
@Composable
fun BypassRow(subnets: List<String>, enabled: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick, enabled = enabled, color = MaterialTheme.colorScheme.surfaceContainer, shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth().height(56.dp),
    ) {
        Row(Modifier.padding(start = 20.dp, end = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.bypass), style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.width(12.dp))
            Text(
                subnets.joinToString(", ").ifEmpty { stringResource(R.string.bypass_off) },
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
            )
            Icon(Icons.Rounded.Edit, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 12.dp).size(20.dp))
        }
    }
}

/** Edits the bypass list, checking it as you type; Apply saves it and reconnects an active tunnel. */
@Composable
fun BypassDialog(subnets: List<String>, onDismiss: () -> Unit, onSave: (List<String>) -> Unit) {
    val initial = subnets.joinToString(", ")
    var text by remember { mutableStateOf(TextFieldValue(initial, TextRange(initial.length))) }
    val (parsed, invalid) = Cidr.parseList(text.text)
    val focus = remember { FocusRequester() }
    fun save() {
        if (invalid.isEmpty()) onSave(parsed.map { it.toString() }.distinct())
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.bypass)) },
        text = {
            Column {
                Text(stringResource(R.string.bypass_hint))
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(
                    text, { text = it }, Modifier.fillMaxWidth().focusRequester(focus),
                    placeholder = { Text("10.*, 192.168.0.0/16") },
                    singleLine = true,
                    isError = invalid.isNotEmpty(),
                    supportingText = if (invalid.isNotEmpty()) {
                        { Text(stringResource(R.string.bypass_invalid, invalid.joinToString(", "))) }
                    } else null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { save() }),
                )
            }
        },
        confirmButton = { TextButton(::save, enabled = invalid.isEmpty()) { Text(stringResource(R.string.apply)) } },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
    LaunchedEffect(Unit) { focus.requestFocus() }
}

/** One fixed-height line for what matters most right now: an error, a notice, the traffic, or where Connect goes. */
@Composable
fun InfoRow(error: String?, notice: String?, onDismissError: () -> Unit, connected: Boolean, rx: Long, tx: Long, host: String?) {
    val scheme = MaterialTheme.colorScheme
    val small = MaterialTheme.typography.bodySmall
    val context = LocalContext.current
    Row(Modifier.fillMaxWidth().height(40.dp).padding(start = 8.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        when {
            error != null -> {
                Icon(Icons.Rounded.Warning, null, tint = scheme.error, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(error, style = small, color = scheme.error, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                IconButton(onDismissError) { Icon(Icons.Rounded.Close, stringResource(R.string.close), Modifier.size(18.dp)) }
            }
            notice != null -> {
                Icon(Icons.Rounded.Info, null, tint = scheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(notice, style = small, color = scheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            }
            connected -> {
                host?.let { SelectionContainer { Text(it, style = small, color = scheme.onSurfaceVariant, maxLines = 1) } }
                Spacer(Modifier.weight(1f))
                val bytes = { n: Long -> Formatter.formatShortFileSize(context, n) }
                Text("↓ ${bytes(rx)}    ↑ ${bytes(tx)}", style = small.copy(fontFeatureSettings = "tnum"), maxLines = 1)
            }
            // Where the power button will connect to, dimmed, in the same spot as the connected IP.
            host != null -> SelectionContainer { Text(host, style = small, color = scheme.outline, maxLines = 1) }
        }
    }
}

/** Add a tunnel on the left, settings on the right; each opens its sheet. */
@Composable
fun Footer(onAdd: () -> Unit, onSettings: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        FilledTonalIconButton(onAdd, Modifier.size(56.dp)) {
            Icon(Icons.Rounded.Add, stringResource(R.string.add_tunnel))
        }
        Spacer(Modifier.weight(1f))
        FilledTonalIconButton(onSettings, Modifier.size(56.dp)) {
            Icon(Icons.Rounded.Settings, stringResource(R.string.settings))
        }
    }
}

/** Android 16-style grouped list: rows are separate rounded tiles, only the group's ends are fully round. */
private fun groupShape(index: Int, count: Int): RoundedCornerShape {
    val big = 24.dp
    val small = 6.dp
    return RoundedCornerShape(
        topStart = if (index == 0) big else small, topEnd = if (index == 0) big else small,
        bottomStart = if (index == count - 1) big else small, bottomEnd = if (index == count - 1) big else small,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddSheet(onDismiss: () -> Unit, onClipboard: () -> Unit, onFile: () -> Unit, onQr: () -> Unit) {
    ModalBottomSheet(onDismiss, containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(stringResource(R.string.add_tunnel), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 8.dp, bottom = 12.dp))
            val options = listOf(
                Triple(Icons.Rounded.ContentPaste, R.string.from_clipboard to R.string.from_clipboard_desc, onClipboard),
                Triple(Icons.Rounded.QrCodeScanner, R.string.scan_qr to R.string.scan_qr_desc, onQr),
                Triple(Icons.Rounded.FolderOpen, R.string.from_file to R.string.from_file_desc, onFile),
            )
            options.forEachIndexed { i, (icon, text, action) ->
                ActionRow(icon, stringResource(text.first), stringResource(text.second), groupShape(i, options.size), action)
            }
        }
    }
}

@Composable
private fun ActionRow(icon: ImageVector, title: String, subtitle: String, shape: RoundedCornerShape, onClick: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = shape, onClick = onClick) {
        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
            }
            Spacer(Modifier.width(14.dp))
            Column {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSheet(
    notification: Boolean,
    onNotification: (Boolean) -> Unit,
    wallpaperColors: Boolean,
    onWallpaperColors: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    // Wallpaper colors came with Android 12.
    val count = if (Build.VERSION.SDK_INT >= 31) 2 else 1
    ModalBottomSheet(onDismiss, containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(stringResource(R.string.settings), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 8.dp, bottom = 12.dp))
            SwitchRow(Icons.Rounded.NotificationsActive, R.string.notif_setting, R.string.notif_setting_desc, notification, groupShape(0, count), onNotification)
            if (count == 2) SwitchRow(Icons.Rounded.Palette, R.string.wallpaper, R.string.wallpaper_desc, wallpaperColors, groupShape(1, count), onWallpaperColors)
        }
    }
}

@Composable
private fun SwitchRow(icon: ImageVector, title: Int, subtitle: Int, checked: Boolean, shape: RoundedCornerShape, onChange: (Boolean) -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = shape, onClick = { onChange(!checked) }) {
        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(title), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(subtitle), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.width(8.dp))
            Switch(checked, onChange, thumbContent = if (checked) {
                { Icon(Icons.Rounded.Check, null, Modifier.size(SwitchDefaults.IconSize)) }
            } else null)
        }
    }
}

@Composable
fun RenameDialog(old: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var text by remember(old) { mutableStateOf(TextFieldValue(old, TextRange(0, old.length))) }
    val valid = KeyImport.cleanTitle(text.text) != null && text.text.trim() != old
    val focus = remember { FocusRequester() }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.Edit, null) },
        title = { Text(stringResource(R.string.rename_title)) },
        text = {
            OutlinedTextField(
                text, { text = it }, Modifier.focusRequester(focus), singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (valid) onConfirm(text.text) }),
            )
        },
        confirmButton = { TextButton({ onConfirm(text.text) }, enabled = valid) { Text(stringResource(R.string.rename)) } },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
    LaunchedEffect(Unit) { focus.requestFocus() }
}

@Composable
fun ImportConfirmDialog(tunnels: List<Pair<String, String?>>, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.import_confirm_title)) },
        text = {
            Text(tunnels.joinToString("\n") { (name, endpoint) -> listOfNotNull(name, endpoint).joinToString(" · ") })
        },
        confirmButton = { TextButton(onConfirm) { Text(stringResource(R.string.import_confirm)) } },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
fun DeleteDialog(name: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.DeleteOutline, null) },
        title = { Text(stringResource(R.string.delete_title, name)) },
        text = { Text(stringResource(R.string.delete_message)) },
        confirmButton = {
            TextButton(onConfirm, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                Text(stringResource(R.string.delete))
            }
        },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
