package io.github.artemimennoi.awgbar

import java.math.BigInteger
import java.net.InetAddress

/** An IPv4 or IPv6 network: bypass subnets and AllowedIPs arithmetic. */
data class Cidr(val bytes: List<Byte>, val prefix: Int) {
    val isV6 get() = bytes.size == 16
    private val bits get() = bytes.size * 8
    private val value get() = BigInteger(1, bytes.toByteArray())

    fun contains(other: Cidr) =
        isV6 == other.isV6 && prefix <= other.prefix && of(other.value, prefix) == value

    /** This network without [hole]: the siblings along the path from this prefix down to the hole. */
    fun minus(hole: Cidr): List<Cidr> = when {
        hole.contains(this) -> emptyList()
        !contains(hole) -> listOf(this)
        else -> (prefix until hole.prefix).map { depth ->
            val path = of(hole.value, depth + 1)
            make(path.flipBit(bits - depth - 1), depth + 1)
        }
    }

    override fun toString(): String {
        val host = InetAddress.getByAddress(bytes.toByteArray()).hostAddress
        return "$host/$prefix"
    }

    private fun of(v: BigInteger, p: Int): BigInteger {
        val mask = BigInteger.ONE.shiftLeft(bits).subtract(BigInteger.ONE)
            .xor(BigInteger.ONE.shiftLeft(bits - p).subtract(BigInteger.ONE))
        return v.and(mask)
    }

    private fun make(v: BigInteger, p: Int): Cidr {
        val raw = v.toByteArray().takeLast(bytes.size)
        val padded = List(bytes.size - raw.size) { 0.toByte() } + raw
        return Cidr(padded, p)
    }

    companion object {
        fun of(bytes: ByteArray, prefix: Int): Cidr? {
            if ((bytes.size != 4 && bytes.size != 16) || prefix !in 0..bytes.size * 8) return null
            val c = Cidr(bytes.toList(), prefix)
            return c.make(c.of(c.value, prefix), prefix)
        }

        /** Accepts "10.0.0.0/8", "10.1.2.3" (/32), "10.*" or "192.168.*.*" (octet wildcards), "fd00::/8", "fd00::1". */
        fun parse(text: String): Cidr? {
            val s = text.trim()
            if ("*" in s) {
                val parts = s.split(".")
                if (parts.size > 4) return null
                val octets = parts.takeWhile { it != "*" }.map { it.toIntOrNull()?.takeIf { n -> n in 0..255 } ?: return null }
                // Everything after the first "*" must be "*" too: "10.*.3" is not a network.
                if (!parts.drop(octets.size).all { it == "*" }) return null
                return of(ByteArray(4) { octets.getOrElse(it) { 0 }.toByte() }, octets.size * 8)
            }
            val pieces = s.split("/", limit = 2)
            val bytes = parseAddress(pieces[0]) ?: return null
            val prefix = if (pieces.size == 2) pieces[1].toIntOrNull() ?: return null else bytes.size * 8
            return of(bytes, prefix)
        }

        /** Literal addresses only: InetAddress would otherwise resolve host names. */
        fun parseAddress(s: String): ByteArray? {
            if (s.isEmpty() || !s.all { it.isDigit() || it in "abcdefABCDEF:." }) return null
            if (':' !in s && s.split(".").let { p -> p.size != 4 || p.any { it.toIntOrNull()?.takeIf { n -> n in 0..255 && it.length <= 3 } == null } }) return null
            return runCatching { InetAddress.getByName(s).address }.getOrNull()
        }

        /** Splits input like "10.*, 192.168.0.0/16 fd00::/8" into networks and the entries that failed to parse. */
        fun parseList(text: String): Pair<List<Cidr>, List<String>> {
            val networks = mutableListOf<Cidr>()
            val invalid = mutableListOf<String>()
            for (entry in text.split(',', ';', ' ', '\n', '\t').filter { it.isNotBlank() }) {
                val net = parse(entry)
                if (net == null) invalid += entry else if (net !in networks) networks += net
            }
            return networks to invalid
        }

        /** [allowed] with every [excluded] network cut out. */
        fun subtract(allowed: List<Cidr>, excluded: List<Cidr>): List<Cidr> =
            excluded.fold(allowed) { nets, hole -> nets.flatMap { it.minus(hole) } }
    }
}
