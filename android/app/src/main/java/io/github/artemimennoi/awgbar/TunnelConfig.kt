package io.github.artemimennoi.awgbar

import org.amnezia.awg.config.Config
import java.io.BufferedReader
import java.io.StringReader

/**
 * An AmneziaWG config reduced to keys the Go backend understands, like TunnelConfig.swift.
 * Unknown keys (PostUp, Table, ...) are dropped and reported instead of failing the import;
 * values are then validated by the tunnel library's own parser.
 */
class TunnelConfig private constructor(
    val interfaceEntries: List<Pair<String, String>>,
    val peers: List<List<Pair<String, String>>>,
    val dropped: List<String>,
) {
    val endpoint get() = peers.firstNotNullOfOrNull { p -> p.firstOrNull { it.first == "Endpoint" }?.second }

    /** Same client key talking to the same peers is the same tunnel, whatever its title or endpoint. */
    val identity get() = (listOf(value(interfaceEntries, "PrivateKey") ?: "") +
        peers.mapNotNull { value(it, "PublicKey") }.sorted()).joinToString("|")

    fun render(): String = buildString {
        append("[Interface]\n")
        interfaceEntries.forEach { (k, v) -> append("$k = $v\n") }
        for (peer in peers) {
            append("\n[Peer]\n")
            peer.forEach { (k, v) -> append("$k = $v\n") }
        }
    }

    private fun allowedIps(peer: List<Pair<String, String>>) =
        peer.filter { it.first == "AllowedIPs" }.flatMap { it.second.split(",") }.mapNotNull { Cidr.parse(it) }

    private val addresses get() =
        interfaceEntries.filter { it.first == "Address" }.flatMap { it.second.split(",") }.mapNotNull { Cidr.parse(it) }

    /** One peer that gets everything of a family: the VPN keeps other families blocked. */
    val isFullTunnel get() = peers.size == 1 && allowedIps(peers[0]).any { it.prefix == 0 }

    private val configDns get() =
        interfaceEntries.filter { it.first == "DNS" }.flatMap { it.second.split(",") }.map { it.trim() }.filter { Cidr.parseAddress(it) != null }

    /** No DNS in a full-tunnel config: use AmneziaVPN's default rather than the network's. */
    private val usesFallbackDns get() =
        configDns.isEmpty() && peers.any { p -> allowedIps(p).any { it.prefix == 0 && !it.isV6 } } && addresses.any { !it.isV6 }

    /**
     * The backend config: the fallback DNS if the config names none, and bypass subnets cut out of
     * every peer's AllowedIPs, except the tunnel's name servers (AmneziaDNS 172.29.172.254 inside
     * a bypassed 172.16.0.0/12 still goes through the tunnel).
     */
    fun toBackendConfig(bypass: List<Cidr>): Config {
        val iface = if (usesFallbackDns) interfaceEntries + ("DNS" to FALLBACK_DNS.joinToString(", ")) else interfaceEntries
        val servers = (if (usesFallbackDns) FALLBACK_DNS else configDns).mapNotNull { Cidr.parse(it) }
        val rewritten = peers.map { peer ->
            if (bypass.isEmpty()) return@map peer
            val original = allowedIps(peer)
            val kept = Cidr.subtract(original, bypass)
            val dns = servers.filter { s -> original.any { it.contains(s) } && kept.none { it.contains(s) } }
            peer.filterNot { it.first == "AllowedIPs" } +
                listOfNotNull((kept + dns).takeIf { it.isNotEmpty() }?.let { "AllowedIPs" to it.joinToString(", ") })
        }
        return parseBackend(TunnelConfig(iface, rewritten, dropped).render())
    }

    companion object {
        val FALLBACK_DNS = listOf("1.1.1.1", "1.0.0.1")

        private val interfaceKeys = listOf(
            "Address", "DNS", "MTU", "PrivateKey", "ListenPort", "Jc", "Jmin", "Jmax", "S1", "S2", "S3", "S4",
            "H1", "H2", "H3", "H4", "I1", "I2", "I3", "I4", "I5", "HeaderProtectionKey",
            "ContentPaddingAddition", "RekeyAfterTime", "RekeyTimeout", "RejectAfterTime",
            "KeepaliveTimeout", "MaxHandshakeAttempts", "RandomTrailers", "DisableCookies",
        ).associateBy { it.lowercase() }
        private val peerKeys = listOf("PublicKey", "PresharedKey", "AllowedIPs", "Endpoint", "PersistentKeepalive")
            .associateBy { it.lowercase() }

        private fun value(entries: List<Pair<String, String>>, key: String) = entries.firstOrNull { it.first == key }?.second

        private fun parseBackend(text: String): Config = Config.parse(BufferedReader(StringReader(text)))

        /** Throws [ConfigError] with a readable message. */
        fun parse(text: String): TunnelConfig {
            val iface = mutableListOf<Pair<String, String>>()
            val peers = mutableListOf<MutableList<Pair<String, String>>>()
            val dropped = mutableListOf<String>()
            var section = 0 // 0 none, 1 interface, 2 peer
            var sawInterface = false
            for (raw in text.lines()) {
                val line = raw.substringBefore('#').trim()
                if (line.isEmpty()) continue
                if (line.startsWith("[")) {
                    when (line.lowercase()) {
                        "[interface]" -> {
                            if (sawInterface) throw ConfigError("More than one [Interface] section")
                            sawInterface = true; section = 1
                        }
                        "[peer]" -> { peers.add(mutableListOf()); section = 2 }
                        else -> throw ConfigError("Unknown section $line")
                    }
                    continue
                }
                val parts = line.split("=", limit = 2).map { it.trim() }
                if (parts.size != 2 || parts[0].isEmpty()) throw ConfigError("Not a key = value line: $line")
                if (parts[1].any { it.isISOControl() }) throw ConfigError("Control characters in ${parts[0]}")
                when (section) {
                    0 -> throw ConfigError("${parts[0]} outside of a section")
                    1 -> interfaceKeys[parts[0].lowercase()]?.let { iface += it to parts[1] } ?: run { dropped += parts[0] }
                    else -> peerKeys[parts[0].lowercase()]?.let { peers.last() += it to parts[1] } ?: run { dropped += parts[0] }
                }
            }
            if (!sawInterface || peers.isEmpty()) throw ConfigError("Not an AmneziaWG config: [Interface] or [Peer] section is missing")
            if (value(iface, "PrivateKey") == null) throw ConfigError("PrivateKey is missing")
            val cfg = TunnelConfig(iface, peers, dropped)
            try {
                parseBackend(cfg.render())
            } catch (e: Exception) {
                throw ConfigError(e.message ?: e.javaClass.simpleName)
            }
            return cfg
        }
    }
}

class ConfigError(message: String) : Exception(message)
