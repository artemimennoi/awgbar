package io.github.artemimennoi.awgbar

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.amnezia.awg.backend.GoBackend
import org.amnezia.awg.backend.Tunnel

data class VpnState(
    val active: String? = null,
    val busy: Boolean = false,
    val rx: Long = 0,
    val tx: Long = 0,
    val error: String? = null,
)

/** One backend for the whole process: the activity, the tile and always-on all drive it. */
object Vpn {
    private const val TAG = "AWGBar"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(VpnState())
    val state: StateFlow<VpnState> = _state.asStateFlow()

    private lateinit var app: Context
    private lateinit var backend: GoBackend
    lateinit var store: TunnelStore
        private set
    private var current: AppTunnel? = null

    private class AppTunnel(private val tunnelName: String) : Tunnel {
        override fun getName(): String = tunnelName
        override fun onStateChange(newState: Tunnel.State) {
            if (newState == Tunnel.State.DOWN) _state.update { if (it.active == tunnelName) it.copy(active = null, rx = 0, tx = 0) else it }
            Tile.refresh(app)
        }
    }

    fun init(context: Context) {
        if (::app.isInitialized) return
        app = context.applicationContext
        store = TunnelStore(app)
        backend = GoBackend(app)
        GoBackend.setAlwaysOnCallback { connect() }
        scope.launch { pollStats() }
    }

    fun toggle() = if (state.value.active != null) disconnect() else connect()

    fun connect(name: String? = store.selected) {
        name ?: return
        _state.update { it.copy(busy = true, error = null) }
        scope.launch {
            try {
                val bypass = Cidr.parseList(store.bypass).first
                val config = store.read(name).toBackendConfig(bypass)
                val tunnel = AppTunnel(name)
                current?.let { if (it.name != name) backend.setState(it, Tunnel.State.DOWN, null) }
                backend.setState(tunnel, Tunnel.State.UP, config)
                current = tunnel
                store.selected = name
                _state.update { it.copy(active = name, busy = false) }
            } catch (e: Exception) {
                Log.e(TAG, "connect", e)
                _state.update { it.copy(active = null, busy = false, error = e.message ?: e.javaClass.simpleName) }
            }
            Tile.refresh(app)
        }
    }

    fun disconnect() {
        val tunnel = current ?: return
        _state.update { it.copy(busy = true, error = null) }
        scope.launch {
            runCatching { backend.setState(tunnel, Tunnel.State.DOWN, null) }.onFailure { Log.e(TAG, "disconnect", it) }
            current = null
            _state.update { it.copy(active = null, busy = false, rx = 0, tx = 0) }
            Tile.refresh(app)
        }
    }

    /** Reconnects so a changed bypass list or config takes effect. */
    fun reconnectIfActive() {
        state.value.active?.let { connect(it) }
    }

    fun clearError() = _state.update { it.copy(error = null) }

    private suspend fun pollStats() {
        while (scope.isActive) {
            current?.takeIf { state.value.active != null }?.let { t ->
                runCatching { backend.getStatistics(t) }.getOrNull()?.let { s ->
                    _state.update { it.copy(rx = s.totalRx(), tx = s.totalTx()) }
                }
            }
            delay(1000)
        }
    }
}
