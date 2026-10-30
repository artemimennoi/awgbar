package io.github.artemimennoi.awgbar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TunnelConfigTest {
    private val conf = """
        [Interface]
        PrivateKey = yAnz5TF+lXXJte14tji3zlMNq+hd2rYUIgJBgB3fBmk=
        Address = 10.8.1.2/32
        Jc = 4
        S3 = 10
        S4 = 12
        H1 = 100-200
        H2 = 300-400
        I1 = <b 0xf6ab3267fa><r 10>
        PostUp = rm -rf /
        [Peer]
        PublicKey = xTIBA5rboUvnH4htodjb6e697QjLERt1NAB4mZqp8Dg=
        AllowedIPs = 0.0.0.0/0
        Endpoint = 203.0.113.5:51820
    """.trimIndent()

    @Test fun dropsHooksKeepsAwg2() {
        val cfg = TunnelConfig.parse(conf)
        assertEquals(listOf("PostUp"), cfg.dropped)
        assertFalse(cfg.render().contains("PostUp"))
        assertTrue(cfg.render().contains("H1 = 100-200"))
        assertEquals("203.0.113.5:51820", cfg.endpoint)
    }

    @Test fun bypassCutsAllowedIps() {
        val backend = TunnelConfig.parse(conf).toBackendConfig(listOf(Cidr.parse("192.168.*")!!))
        val allowed = backend.peers[0].allowedIps.map { it.toString() }
        assertFalse(allowed.contains("0.0.0.0/0"))
        assertTrue(allowed.contains("192.0.0.0/9"))
        assertTrue(allowed.contains("192.169.0.0/16"))
    }

    @Test fun fallbackDnsForFullTunnelOnly() {
        val full = TunnelConfig.parse(conf).toBackendConfig(emptyList())
        assertEquals(listOf("1.1.1.1", "1.0.0.1"), full.`interface`.dnsServers.map { it.hostAddress })
        val split = TunnelConfig.parse(conf.replace("AllowedIPs = 0.0.0.0/0", "AllowedIPs = 10.0.0.0/8")).toBackendConfig(emptyList())
        assertTrue(split.`interface`.dnsServers.isEmpty())
        val own = TunnelConfig.parse(conf.replace("Address = 10.8.1.2/32", "Address = 10.8.1.2/32\nDNS = 9.9.9.9")).toBackendConfig(emptyList())
        assertEquals(listOf("9.9.9.9"), own.`interface`.dnsServers.map { it.hostAddress })
    }

    @Test fun bypassKeepsNameServersInTheTunnel() {
        val amneziaDns = conf.replace("Address = 10.8.1.2/32", "Address = 10.8.1.2/32\nDNS = 172.29.172.254")
        val backend = TunnelConfig.parse(amneziaDns).toBackendConfig(listOf(Cidr.parse("172.16.0.0/12")!!))
        val allowed = backend.peers[0].allowedIps.map { it.toString() }
        assertTrue(allowed.contains("172.29.172.254/32"))
        assertFalse(allowed.any { it.startsWith("172.16.0.0/") })
    }

    @Test fun fullTunnelSurvivesBypass() {
        assertTrue(TunnelConfig.parse(conf).isFullTunnel)
        assertFalse(TunnelConfig.parse(conf.replace("AllowedIPs = 0.0.0.0/0", "AllowedIPs = 10.0.0.0/8")).isFullTunnel)
    }
}
