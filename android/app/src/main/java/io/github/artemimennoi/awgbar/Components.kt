package io.github.artemimennoi.awgbar

import android.text.format.Formatter
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.MoreVert
import io.github.artemimennoi.awgbar.icons.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
fun TopBar(onSettings: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = 8.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
        FilledTonalIconButton(onClick = onSettings) {
            Icon(Icons.Rounded.Tune, contentDescription = stringResource(R.string.settings))
        }
    }
}

@Composable
fun SectionHeader(res: Int) {
    Text(
        stringResource(res).uppercase(),
        style = SectionLabel,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 12.dp, top = 12.dp),
    )
}

@Composable
fun HeroCard(state: VpnState, tunnel: String?, host: String?, canConnect: Boolean, onToggle: () -> Unit) {
    val on = state.active != null
    val scheme = MaterialTheme.colorScheme
    val container by animateColorAsState(if (on) scheme.primaryContainer else scheme.surfaceContainer, tween(500), label = "hero")
    val content = if (on) scheme.onPrimaryContainer else scheme.onSurface

    Surface(color = container, contentColor = content, shape = MaterialTheme.shapes.extraLarge, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.size(156.dp)) {
                Halo(on = on, busy = state.busy)
                StatusShield(on, state.busy, content, size = 112.dp)
            }
            Spacer(Modifier.height(12.dp))
            Text(
                stringResource(
                    when {
                        state.busy -> R.string.status_busy
                        on -> R.string.status_on
                        else -> R.string.status_off
                    },
                ),
                style = MaterialTheme.typography.headlineLarge,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                listOfNotNull(tunnel, host).joinToString(" · ").ifEmpty { stringResource(R.string.no_tunnels) },
                style = MaterialTheme.typography.bodyLarge,
                color = content.copy(alpha = 0.7f),
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            AnimatedVisibility(on, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                Row(Modifier.padding(top = 20.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StatTile(Icons.Rounded.SouthWest, R.string.download, state.rxRate, state.rx, Modifier.weight(1f))
                    StatTile(Icons.Rounded.NorthEast, R.string.upload, state.txRate, state.tx, Modifier.weight(1f))
                }
            }
            Spacer(Modifier.height(24.dp))
            val buttonColors = if (on) {
                ButtonDefaults.buttonColors(containerColor = scheme.surfaceContainerLowest, contentColor = scheme.onSurface)
            } else ButtonDefaults.buttonColors()
            Button(
                onClick = onToggle,
                enabled = canConnect && !state.busy,
                colors = buttonColors,
                shape = CircleShape,
                contentPadding = PaddingValues(horizontal = 24.dp),
                modifier = Modifier.fillMaxWidth().height(60.dp),
            ) {
                if (state.busy) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.5.dp, color = LocalContentColor.current)
                } else {
                    Icon(Icons.Rounded.PowerSettingsNew, contentDescription = null)
                    Spacer(Modifier.width(10.dp))
                    Text(stringResource(if (on) R.string.disconnect else R.string.connect), style = MaterialTheme.typography.titleMedium)
                }
            }
        }
    }
}

/** Soft rings behind the shield: breathing while connected, faster while connecting. */
@Composable
private fun Halo(on: Boolean, busy: Boolean) {
    val transition = rememberInfiniteTransition(label = "halo")
    val pulse by transition.animateFloat(
        0.86f, 1f,
        infiniteRepeatable(tween(if (busy) 700 else 2200, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "pulse",
    )
    val ring = if (on) ShieldGreen else MaterialTheme.colorScheme.onSurface
    val alpha by animateFloatAsState(if (on) 0.18f else if (busy) 0.10f else 0.05f, label = "alpha")
    val scale = if (on || busy) pulse else 0.92f
    Box(Modifier.fillMaxSize().scale(scale).clip(CircleShape).background(ring.copy(alpha = alpha)))
    Box(Modifier.fillMaxSize(0.78f).scale(scale).clip(CircleShape).background(ring.copy(alpha = alpha)))
}

@Composable
private fun StatTile(icon: ImageVector, label: Int, rate: Long, total: Long, modifier: Modifier) {
    val context = LocalContext.current
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLowest.copy(alpha = 0.6f), shape = MaterialTheme.shapes.medium, modifier = modifier) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null, Modifier.size(16.dp), tint = LocalContentColor.current.copy(alpha = 0.7f))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(label), style = MaterialTheme.typography.labelMedium, color = LocalContentColor.current.copy(alpha = 0.7f))
            }
            Spacer(Modifier.height(6.dp))
            Text("${Formatter.formatShortFileSize(context, rate)}/s", style = MaterialTheme.typography.titleLarge, maxLines = 1)
            Text(Formatter.formatShortFileSize(context, total), style = MaterialTheme.typography.bodySmall, color = LocalContentColor.current.copy(alpha = 0.6f))
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

@Composable
fun TunnelGroup(
    names: List<String>,
    selected: String?,
    active: String?,
    locked: Boolean,
    onSelect: (String) -> Unit,
    onRename: (String) -> Unit,
    onDelete: (String) -> Unit,
    onAdd: () -> Unit,
) {
    val count = names.size + 1
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        names.forEachIndexed { i, name ->
            val isSelected = name == selected
            // While connected the other tunnels are dimmed and can't be picked; their menu still works.
            val dimmed = locked && !isSelected
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainer, shape = groupShape(i, count),
                enabled = !locked, onClick = { onSelect(name) },
            ) {
                Row(Modifier.padding(start = 14.dp, end = 4.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    val bg by animateColorAsState(
                        if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh, label = "dot",
                    )
                    Box(Modifier.alpha(if (dimmed) 0.45f else 1f).size(40.dp).clip(CircleShape).background(bg), contentAlignment = Alignment.Center) {
                        Icon(
                            if (isSelected) Icons.Rounded.Check else Icons.Rounded.Shield, null,
                            tint = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f).alpha(if (dimmed) 0.45f else 1f)) {
                        Text(name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val host = remember(name) { Vpn.endpointHost(name) }
                        val sub = if (name == active) stringResource(R.string.active) else host
                        sub?.let {
                            Text(
                                it, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                color = if (name == active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    var menu by remember { mutableStateOf(false) }
                    Box {
                        IconButton({ menu = true }) { Icon(Icons.Rounded.MoreVert, stringResource(R.string.more)) }
                        DropdownMenu(menu, { menu = false }, shape = MaterialTheme.shapes.medium) {
                            DropdownMenuItem(
                                { Text(stringResource(R.string.rename)) }, { menu = false; onRename(name) },
                                leadingIcon = { Icon(Icons.Rounded.Edit, null) },
                            )
                            DropdownMenuItem(
                                { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) }, { menu = false; onDelete(name) },
                                leadingIcon = { Icon(Icons.Rounded.DeleteOutline, null, tint = MaterialTheme.colorScheme.error) },
                            )
                        }
                    }
                }
            }
        }
        Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = groupShape(names.size, count), onClick = onAdd) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.Add, null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
                }
                Spacer(Modifier.width(14.dp))
                Column {
                    Text(stringResource(R.string.add_tunnel), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                    if (names.isEmpty()) {
                        Text(stringResource(R.string.no_tunnels_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BypassCard(subnets: List<String>, onAdd: (List<String>) -> Unit, onRemove: (String) -> Unit) {
    var draft by remember { mutableStateOf("") }
    val (parsed, invalid) = Cidr.parseList(draft)
    fun submit() {
        if (parsed.isNotEmpty() && invalid.isEmpty()) { onAdd(parsed.map { it.toString() }); draft = "" }
    }
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(stringResource(R.string.bypass_hint), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (subnets.isNotEmpty()) {
                FlowRow(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    subnets.forEach { net ->
                        InputChip(
                            selected = false,
                            onClick = { onRemove(net) },
                            label = { Text(net) },
                            trailingIcon = { Icon(Icons.Rounded.Close, stringResource(R.string.delete), Modifier.size(18.dp)) },
                            shape = CircleShape,
                        )
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("10.*, 192.168.0.0/16") },
                singleLine = true,
                shape = CircleShape,
                isError = invalid.isNotEmpty(),
                supportingText = if (invalid.isNotEmpty()) {
                    { Text(stringResource(R.string.bypass_invalid, invalid.joinToString(", "))) }
                } else null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { submit() }),
                trailingIcon = {
                    FilledIconButton(onClick = ::submit, enabled = parsed.isNotEmpty() && invalid.isEmpty(), modifier = Modifier.padding(end = 4.dp)) {
                        Icon(Icons.Rounded.Add, stringResource(R.string.bypass_add))
                    }
                },
            )
        }
    }
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
    ModalBottomSheet(onDismiss, containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(stringResource(R.string.settings), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 8.dp, bottom = 12.dp))
            SwitchRow(Icons.Rounded.NotificationsActive, R.string.notif_setting, R.string.notif_setting_desc, notification, groupShape(0, 2), onNotification)
            SwitchRow(Icons.Rounded.Palette, R.string.wallpaper, R.string.wallpaper_desc, wallpaperColors, groupShape(1, 2), onWallpaperColors)
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
fun RenameDialog(old: String, renamingActive: Boolean, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var text by remember(old) { mutableStateOf(old) }
    val valid = KeyImport.isValidName(text)
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.Edit, null) },
        title = { Text(stringResource(R.string.rename)) },
        text = {
            OutlinedTextField(
                text, { text = it }, singleLine = true, isError = !valid, shape = MaterialTheme.shapes.medium,
                supportingText = { Text(stringResource(if (renamingActive) R.string.rename_active else R.string.name_rule)) },
            )
        },
        confirmButton = { Button({ onConfirm(text) }, enabled = valid && !renamingActive) { Text(stringResource(R.string.ok)) } },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
fun DeleteDialog(name: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.DeleteOutline, null) },
        title = { Text(stringResource(R.string.delete_title, name)) },
        confirmButton = {
            Button(onConfirm, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError)) {
                Text(stringResource(R.string.delete))
            }
        },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
