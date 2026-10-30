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
    /** Bytes per second over the last poll. */
    val rxRate: Long = 0,
    val txRate: Long = 0,
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

    private class AppTunnel(private val tunnelName: String, private val fullTunnel: Boolean) : Tunnel {
        override fun getName(): String = tunnelName
        override fun isFullTunnel() = fullTunnel
        override fun onStateChange(newState: Tunnel.State) {
            if (newState == Tunnel.State.DOWN) _state.update { if (it.active == tunnelName) it.copy(active = null, rx = 0, tx = 0, rxRate = 0, txRate = 0) else it }
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
        endpointCache = null
        _state.update { it.copy(busy = true, error = null) }
        scope.launch {
            try {
                val bypass = Cidr.parseList(store.bypass).first
                val stored = store.read(name)
                val config = stored.toBackendConfig(bypass)
                val tunnel = AppTunnel(name, stored.isFullTunnel)
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
            _state.update { it.copy(active = null, busy = false, rx = 0, tx = 0, rxRate = 0, txRate = 0) }
            Tile.refresh(app)
        }
    }

    /** Reconnects so a changed bypass list or config takes effect. */
    fun reconnectIfActive() {
        state.value.active?.let { connect(it) }
    }

    fun clearError() = _state.update { it.copy(error = null) }

    private suspend fun pollStats() {
        var notified = false
        while (scope.isActive) {
            val active = state.value.active
            current?.takeIf { active != null }?.let { t ->
                runCatching { backend.getStatistics(t) }.getOrNull()?.let { s ->
                    _state.update {
                        val first = it.rx == 0L && it.tx == 0L
                        it.copy(
                            rx = s.totalRx(), tx = s.totalTx(),
                            rxRate = if (first) 0 else (s.totalRx() - it.rx).coerceAtLeast(0),
                            txRate = if (first) 0 else (s.totalTx() - it.tx).coerceAtLeast(0),
                        )
                    }
                }
            }
            if (active != null && store.showNotification) {
                Notifier.update(app, state.value, endpointHost(active))
                notified = true
            } else if (notified) {
                Notifier.cancel(app)
                notified = false
            }
            delay(1000)
        }
    }

    private var endpointCache: Pair<String, String?>? = null

    /** The server's host for [name], without the port: what the UI and the notification show. */
    fun endpointHost(name: String): String? {
        endpointCache?.let { (n, host) -> if (n == name) return host }
        val host = runCatching { store.read(name).endpoint }.getOrNull()?.substringBeforeLast(':')?.trim('[', ']')
        endpointCache = name to host
        return host
    }
}
