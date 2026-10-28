package io.github.artemimennoi.awgbar

import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.net.VpnService
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.format.Formatter
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : ComponentActivity() {
    /** Text handed over by a "share"/"open with" intent, imported once the UI is up. */
    private val incoming = MutableStateFlow<Pair<String, String>?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handle(intent)
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                Surface(Modifier.fillMaxSize()) { MainScreen(incoming) }
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

@Composable
private fun MainScreen(incoming: MutableStateFlow<Pair<String, String>?>) {
    val context = LocalContext.current
    val activity = context as MainActivity
    val state by Vpn.state.collectAsState()
    var names by remember { mutableStateOf(Vpn.store.names()) }
    var selected by remember { mutableStateOf(Vpn.store.selected) }
    var message by remember { mutableStateOf<String?>(null) }
    var renaming by remember { mutableStateOf<String?>(null) }
    var deleting by remember { mutableStateOf<String?>(null) }
    var bypass by remember { mutableStateOf(Vpn.store.bypass) }

    fun reload() { names = Vpn.store.names(); selected = Vpn.store.selected }

    fun import(text: String, name: String) {
        message = try {
            val added = Vpn.store.import(text, name)
            reload()
            context.resources.getQuantityString(R.plurals.imported, added.size, added.size)
        } catch (e: ImportError) {
            context.getString(e.res)
        } catch (e: ConfigError) {
            context.getString(R.string.err_config, e.message)
        }
    }

    val pending by incoming.collectAsState()
    LaunchedEffect(pending) { pending?.let { (t, n) -> import(t, n); incoming.value = null } }

    val vpnPermission = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (VpnService.prepare(context) == null) Vpn.connect()
    }
    val openFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { activity.readUri(it) }?.let { (t, n) -> import(t, n) }
    }

    fun toggle() {
        if (state.active == null) {
            val prep = VpnService.prepare(context)
            if (prep != null) vpnPermission.launch(prep) else Vpn.connect()
        } else Vpn.disconnect()
    }

    Column(
        Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // Status
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            StatusShield(state.active != null, state.busy, MaterialTheme.colorScheme.onSurface,
                Modifier.clickable(enabled = selected != null && !state.busy) { toggle() })
            Spacer(Modifier.height(8.dp))
            Text(
                when {
                    state.busy -> stringResource(R.string.please_wait)
                    state.active != null -> stringResource(R.string.connected, state.active!!)
                    else -> stringResource(R.string.disconnected)
                },
                style = MaterialTheme.typography.titleMedium,
            )
            val endpoint = remember(selected, state.active) {
                (state.active ?: selected)?.let { runCatching { Vpn.store.read(it).endpoint }.getOrNull() }
            }
            endpoint?.let { Text(it.substringBeforeLast(':').trim('[', ']'), color = MaterialTheme.colorScheme.outline) }
            if (state.active != null) {
                Text("↓ ${Formatter.formatShortFileSize(context, state.rx)}   ↑ ${Formatter.formatShortFileSize(context, state.tx)}",
                    style = MaterialTheme.typography.bodySmall)
            }
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            Spacer(Modifier.height(8.dp))
            Button(onClick = ::toggle, enabled = selected != null && !state.busy) {
                Text(stringResource(if (state.active != null) R.string.disconnect else R.string.connect))
            }
        }

        HorizontalDivider()

        // Tunnels
        if (names.isEmpty()) {
            Text(stringResource(R.string.no_tunnels), fontWeight = FontWeight.SemiBold)
            Text(stringResource(R.string.no_tunnels_hint), color = MaterialTheme.colorScheme.outline)
        }
        names.forEach { name ->
            var menu by remember { mutableStateOf(false) }
            Row(
                Modifier.fillMaxWidth().clickable {
                    Vpn.store.selected = name; selected = name
                    if (state.active != null && state.active != name) Vpn.connect(name)
                },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = name == selected, onClick = null)
                Spacer(Modifier.width(8.dp))
                Text(name, Modifier.weight(1f))
                Box {
                    TextButton(onClick = { menu = true }) { Text("•••") }
                    DropdownMenu(menu, { menu = false }) {
                        DropdownMenuItem({ Text(stringResource(R.string.rename)) }, { menu = false; renaming = name })
                        DropdownMenuItem({ Text(stringResource(R.string.delete)) }, { menu = false; deleting = name })
                    }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                val clip = (context.getSystemService(ClipboardManager::class.java)).primaryClip
                val text = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()
                if (text.isNullOrBlank()) message = context.getString(R.string.clipboard_empty) else import(text, "awg")
            }) { Text(stringResource(R.string.from_clipboard)) }
            OutlinedButton(onClick = { openFile.launch(arrayOf("*/*")) }) { Text(stringResource(R.string.from_file)) }
        }
        message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }

        HorizontalDivider()

        // Bypass VPN
        Text(stringResource(R.string.bypass), fontWeight = FontWeight.SemiBold)
        val invalid = Cidr.parseList(bypass).second
        OutlinedTextField(
            bypass, { bypass = it }, Modifier.fillMaxWidth(),
            placeholder = { Text("10.*, 192.168.0.0/16") },
            supportingText = {
                Text(if (invalid.isEmpty()) stringResource(R.string.bypass_hint) else stringResource(R.string.bypass_invalid, invalid.joinToString(", ")))
            },
            isError = invalid.isNotEmpty(),
        )
        TextButton(
            onClick = { Vpn.store.bypass = bypass; Vpn.reconnectIfActive() },
            enabled = invalid.isEmpty() && bypass != Vpn.store.bypass,
        ) { Text(stringResource(R.string.apply)) }
    }

    renaming?.let { old ->
        var text by remember(old) { mutableStateOf(old) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text(stringResource(R.string.rename)) },
            text = { OutlinedTextField(text, { text = it }, singleLine = true, isError = !KeyImport.isValidName(text)) },
            confirmButton = {
                TextButton(enabled = KeyImport.isValidName(text) && state.active != old, onClick = {
                    if (text == old || Vpn.store.rename(old, text)) { reload(); renaming = null }
                    else message = context.getString(R.string.name_taken)
                }) { Text(stringResource(R.string.ok)) }
            },
            dismissButton = { TextButton({ renaming = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    deleting?.let { name ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.delete_title, name)) },
            confirmButton = {
                TextButton({
                    if (state.active == name) Vpn.disconnect()
                    Vpn.store.delete(name); reload(); deleting = null
                }) { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton({ deleting = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}
