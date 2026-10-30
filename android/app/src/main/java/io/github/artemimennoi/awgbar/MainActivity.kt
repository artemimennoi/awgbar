package io.github.artemimennoi.awgbar

import android.Manifest
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : ComponentActivity() {
    /** Text handed over by a "share"/"open with" intent, imported once the UI is up. */
    private val incoming = MutableStateFlow<Pair<String, String>?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handle(intent)
        setContent {
            var wallpaper by remember { mutableStateOf(Vpn.store.wallpaperColors) }
            AppTheme(wallpaper) {
                MainScreen(incoming, wallpaper) { wallpaper = it; Vpn.store.wallpaperColors = it }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent?) {
        intent ?: return
        when (intent.action) {
            Intent.ACTION_VIEW -> intent.data?.let { uri ->
                if (uri.scheme == "vpn") incoming.value = uri.toString() to "awg"
                else readUri(uri)?.let { incoming.value = it }
            }
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)?.let { incoming.value = it to "awg" }
        }
    }

    fun readUri(uri: Uri): Pair<String, String>? = runCatching {
        val text = contentResolver.openInputStream(uri)!!.use { input ->
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(8192)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
                if (out.size() > KeyImport.MAX_SIZE) return null
            }
            out.toString(Charsets.UTF_8.name())
        }
        val name = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        } ?: uri.lastPathSegment ?: "awg"
        text to name.substringBeforeLast('.')
    }.getOrNull()
}

/** A line for the info row; every one is a new object, so the same text again restarts its clock. */
private class Message(val text: String)

@Composable
private fun MainScreen(
    incoming: MutableStateFlow<Pair<String, String>?>,
    wallpaperColors: Boolean,
    onWallpaperColors: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    val activity = context as MainActivity
    val state by Vpn.state.collectAsState()
    var tunnels by remember { mutableStateOf(Vpn.store.titles()) }
    var selected by remember { mutableStateOf(Vpn.store.selected) }
    var renaming by remember { mutableStateOf<String?>(null) }
    var deleting by remember { mutableStateOf<String?>(null) }
    var bypass by remember { mutableStateOf(Cidr.parseList(Vpn.store.bypass).first.map { it.toString() }) }
    var scanning by remember { mutableStateOf(false) }
    var notification by remember { mutableStateOf(Vpn.store.showNotification) }
    var settings by remember { mutableStateOf(false) }
    var adding by remember { mutableStateOf(false) }
    var editingBypass by remember { mutableStateOf(false) }

    // Errors and notices fade out after six seconds, as in the Mac menu.
    var error by remember { mutableStateOf<Message?>(null) }
    var notice by remember { mutableStateOf<Message?>(null) }
    LaunchedEffect(error) { if (error != null) { delay(6000); error = null } }
    LaunchedEffect(notice) { if (notice != null) { delay(6000); notice = null } }
    fun say(text: String) { notice = Message(text) }
    fun fail(text: String) { error = Message(text) }
    fun reload() { tunnels = Vpn.store.titles(); selected = Vpn.store.selected }

    fun importWith(block: () -> ImportResult) {
        try {
            val result = block()
            reload()
            val r = context.resources
            say(
                listOfNotNull(
                    result.added.takeIf { it.isNotEmpty() }?.let { r.getQuantityString(R.plurals.imported, it.size, it.size) },
                    result.updated.takeIf { it.isNotEmpty() }?.let { r.getString(R.string.import_updated, it.joinToString(", ", transform = Vpn.store::title)) },
                    result.unchanged.takeIf { it.isNotEmpty() }?.let { r.getString(R.string.import_exists, it.joinToString(", ", transform = Vpn.store::title)) },
                ).joinToString("\n"),
            )
        } catch (e: ImportError) {
            fail(context.getString(e.res))
        } catch (e: ConfigError) {
            fail(context.getString(R.string.err_config, e.message))
        }
    }

    fun import(text: String, name: String) = importWith { Vpn.store.import(text, name) }

    // Another app or a link handed over a key: show what it is and import only on the user's say,
    // or anything on the phone could slip in a server that the next Connect (or always-on) uses.
    var offered by remember { mutableStateOf<Pair<Pair<String, String>, List<ImportedTunnel>>?>(null) }
    val pending by incoming.collectAsState()
    LaunchedEffect(pending) {
        pending?.let { (t, n) ->
            try {
                offered = (t to n) to KeyImport.parse(t, n)
            } catch (e: ImportError) {
                fail(context.getString(e.res))
            }
            incoming.value = null
        }
    }
    LaunchedEffect(state.error) { state.error?.let { fail(it); Vpn.clearError() } }

    val vpnPermission = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (VpnService.prepare(context) == null) Vpn.connect()
    }
    val openFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { activity.readUri(it) }?.let { (t, n) -> import(t, n) }
    }
    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        notification = ok
        Vpn.store.showNotification = ok
    }

    fun toggle() {
        if (state.active == null) {
            val prep = VpnService.prepare(context)
            if (prep != null) vpnPermission.launch(prep) else Vpn.connect()
        } else Vpn.disconnect()
    }

    /** An unchanged list doesn't reconnect the tunnel. */
    fun saveBypass(list: List<String>) {
        editingBypass = false
        if (list == bypass) return
        bypass = list
        Vpn.store.bypass = list.joinToString(", ")
        Vpn.reconnectIfActive()
    }

    fun fromClipboard() {
        val clip = context.getSystemService(ClipboardManager::class.java).primaryClip
        val text = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()
        if (text.isNullOrBlank()) fail(context.getString(R.string.clipboard_empty)) else import(text, "awg")
    }

    val shield = when {
        state.busy -> ShieldState.Busy
        state.active != null -> ShieldState.On
        else -> ShieldState.Off
    }
    val shown = state.active ?: selected
    // A Surface rather than a plain background, so text without its own color follows the theme.
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            // Not safeDrawing: text is only typed in dialogs, so the keyboard needn't squeeze the screen behind them.
            Modifier.systemBarsPadding().displayCutoutPadding().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Header(state.busy, state.active?.let { tunnels[it] ?: it })
            PowerButton(
                shield, enabled = !state.busy && selected != null, onToggle = ::toggle,
                modifier = Modifier.align(Alignment.CenterHorizontally).padding(vertical = 20.dp),
            )
            // The tunnels' card grows with the list and scrolls once it fills this space.
            Column(Modifier.weight(1f)) {
                if (tunnels.isEmpty()) {
                    EmptyState(::fromClipboard, { scanning = true }, { openFile.launch(arrayOf("*/*")) })
                } else {
                    // Switching tunnels needs the VPN off first.
                    val locked = state.active != null || state.busy
                    TunnelList(
                        tunnels = tunnels,
                        selected = shown,
                        locked = locked,
                        busy = state.busy,
                        onSelect = { name -> Vpn.store.selected = name; selected = name },
                        onLocked = { say(context.getString(R.string.switch_locked)) },
                        onRename = { renaming = it },
                        onDelete = { deleting = it },
                    )
                }
            }
            BypassRow(bypass, enabled = !state.busy) { editingBypass = true }
            InfoRow(
                error?.text, notice?.text, onDismissError = { error = null },
                connected = state.active != null, rx = state.rx, tx = state.tx, host = shown?.let { Vpn.endpointHost(it) },
            )
            Footer(onAdd = { adding = true }, onSettings = { settings = true })
        }
    }

    // Each choice closes the sheet, so what it did shows in the info line.
    if (adding) {
        AddSheet(
            onDismiss = { adding = false },
            onClipboard = { adding = false; fromClipboard() },
            onFile = { adding = false; openFile.launch(arrayOf("*/*")) },
            onQr = { adding = false; scanning = true },
        )
    }
    if (settings) {
        SettingsSheet(
            notification = notification,
            onNotification = { on ->
                val needsPermission = Build.VERSION.SDK_INT >= 33 &&
                    ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                if (on && needsPermission) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                else { notification = on; Vpn.store.showNotification = on }
            },
            wallpaperColors = wallpaperColors,
            onWallpaperColors = onWallpaperColors,
            onDismiss = { settings = false },
        )
    }
    if (editingBypass) {
        BypassDialog(bypass, onDismiss = { editingBypass = false }, onSave = ::saveBypass)
    }
    if (scanning) {
        QrScanner(
            onResult = { data -> scanning = false; importWith { Vpn.store.importQr(data) } },
            onDismiss = { scanning = false },
        )
    }
    offered?.let { (source, tunnels) ->
        ImportConfirmDialog(
            tunnels.map { t -> t.name to runCatching { TunnelConfig.parse(t.config).endpoint }.getOrNull() },
            onDismiss = { offered = null },
        ) {
            offered = null
            import(source.first, source.second)
        }
    }
    renaming?.let { old ->
        RenameDialog(Vpn.store.title(old), onDismiss = { renaming = null }) { new ->
            Vpn.store.rename(old, new); reload(); renaming = null
        }
    }
    deleting?.let { name ->
        DeleteDialog(Vpn.store.title(name), onDismiss = { deleting = null }) {
            if (state.active == name) Vpn.disconnect()
            Vpn.store.delete(name); reload(); deleting = null
        }
    }
}
