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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

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
        val text = contentResolver.openInputStream(uri)!!.use { it.readBytes().toString(Charsets.UTF_8) }
        val name = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        } ?: uri.lastPathSegment ?: "awg"
        text to name.substringBeforeLast('.')
    }.getOrNull()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainScreen(
    incoming: MutableStateFlow<Pair<String, String>?>,
    wallpaperColors: Boolean,
    onWallpaperColors: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    val activity = context as MainActivity
    val state by Vpn.state.collectAsState()
    var names by remember { mutableStateOf(Vpn.store.names()) }
    var selected by remember { mutableStateOf(Vpn.store.selected) }
    var renaming by remember { mutableStateOf<String?>(null) }
    var deleting by remember { mutableStateOf<String?>(null) }
    var bypass by remember { mutableStateOf(Cidr.parseList(Vpn.store.bypass).first.map { it.toString() }) }
    var scanning by remember { mutableStateOf(false) }
    var adding by remember { mutableStateOf(false) }
    var settings by remember { mutableStateOf(false) }
    var notification by remember { mutableStateOf(Vpn.store.showNotification) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    fun say(text: String) { scope.launch { snackbar.currentSnackbarData?.dismiss(); snackbar.showSnackbar(text) } }
    fun reload() { names = Vpn.store.names(); selected = Vpn.store.selected }

    fun importWith(block: () -> List<String>) {
        try {
            val added = block()
            reload()
            adding = false
            say(context.resources.getQuantityString(R.plurals.imported, added.size, added.size))
        } catch (e: ImportError) {
            say(context.getString(e.res))
        } catch (e: ConfigError) {
            say(context.getString(R.string.err_config, e.message))
        }
    }

    fun import(text: String, name: String) = importWith { Vpn.store.import(text, name) }

    val pending by incoming.collectAsState()
    LaunchedEffect(pending) { pending?.let { (t, n) -> import(t, n); incoming.value = null } }
    LaunchedEffect(state.error) { state.error?.let { say(it); Vpn.clearError() } }

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

    fun saveBypass(list: List<String>) {
        bypass = list
        Vpn.store.bypass = list.joinToString(", ")
        Vpn.reconnectIfActive()
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp, end = 16.dp,
                top = padding.calculateTopPadding() + 8.dp, bottom = padding.calculateBottomPadding() + 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { TopBar(onSettings = { settings = true }) }
            item {
                val shown = state.active ?: selected
                HeroCard(
                    state = state,
                    tunnel = shown,
                    host = shown?.let { Vpn.endpointHost(it) },
                    canConnect = selected != null,
                    onToggle = ::toggle,
                )
            }
            item { SectionHeader(R.string.tunnels) }
            item {
                TunnelGroup(
                    names = names,
                    selected = selected,
                    active = state.active,
                    onSelect = { name ->
                        Vpn.store.selected = name; selected = name
                        if (state.active != null && state.active != name) Vpn.connect(name)
                    },
                    onRename = { renaming = it },
                    onDelete = { deleting = it },
                    onAdd = { adding = true },
                )
            }
            item { SectionHeader(R.string.bypass) }
            item {
                BypassCard(
                    subnets = bypass,
                    onAdd = { added -> saveBypass((bypass + added).distinct()) },
                    onRemove = { gone -> saveBypass(bypass - gone) },
                )
            }
        }
    }

    if (adding) {
        AddSheet(
            onDismiss = { adding = false },
            onClipboard = {
                val clip = context.getSystemService(ClipboardManager::class.java).primaryClip
                val text = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()
                if (text.isNullOrBlank()) say(context.getString(R.string.clipboard_empty)) else import(text, "awg")
            },
            onFile = { openFile.launch(arrayOf("*/*")) },
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
    if (scanning) {
        QrScanner(
            onResult = { data -> scanning = false; importWith { Vpn.store.importQr(data) } },
            onDismiss = { scanning = false },
        )
    }
    renaming?.let { old ->
        RenameDialog(old, renamingActive = state.active == old, onDismiss = { renaming = null }) { new ->
            if (new == old || Vpn.store.rename(old, new)) { reload(); renaming = null }
            else say(context.getString(R.string.name_taken))
        }
    }
    deleting?.let { name ->
        DeleteDialog(name, onDismiss = { deleting = null }) {
            if (state.active == name) Vpn.disconnect()
            Vpn.store.delete(name); reload(); deleting = null
        }
    }
}
