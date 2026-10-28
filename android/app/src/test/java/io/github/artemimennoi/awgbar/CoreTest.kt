package io.github.artemimennoi.awgbar

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.Base64
import java.util.zip.Deflater
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CoreTest {
    private val conf = """
        [Interface]
        PrivateKey = yAnz5TF+lXXJte14tji3zlMNq+hd2rYUIgJBgB3fBmk=
        Address = 10.8.1.2/32
        DNS = 1.1.1.1
        Jc = 4
        S3 = 10
        S4 = 12
        H1 = 100-200
        I1 = <b 0xf6ab3267fa><r 10>
        PostUp = rm -rf /

        [Peer]
        PublicKey = xTIBA5rboUvnH4htodjb6e697QjLERt1NAB4mZqp8Dg=
        AllowedIPs = 0.0.0.0/0
        Endpoint = 203.0.113.5:51820
    """.trimIndent()

    private fun qCompress(text: String): String {
        val data = text.toByteArray()
        val d = Deflater().apply { setInput(data); finish() }
        val out = ByteArrayOutputStream()
        val buf = ByteArray(1024)
        while (!d.finished()) out.write(buf, 0, d.deflate(buf))
        val packed = ByteBuffer.allocate(4).putInt(data.size).array() + out.toByteArray()
        return "vpn://" + Base64.getUrlEncoder().withoutPadding().encodeToString(packed)
    }

    @Test fun vpnKey() {
        val lastConfig = org.json.JSONObject().put("config", conf).toString()
        val json = org.json.JSONObject()
            .put("description", "Мой сервер | #2")
            .put("containers", org.json.JSONArray().put(org.json.JSONObject().put("awg",
                org.json.JSONObject().put("last_config", lastConfig))))
        val tunnels = KeyImport.parse(qCompress(json.toString()), "x")
        assertEquals(1, tunnels.size)
        assertEquals("Moj-server-2", KeyImport.tunnelName(tunnels[0].name, emptySet()))
        assertEquals("Moj-server-2-2", KeyImport.tunnelName(tunnels[0].name, setOf("Moj-server-2")))
    }

    @Test fun plainConf() {
        assertEquals(conf + "\n", KeyImport.parse(conf, "home")[0].config)
    }

    @Test(expected = ImportError::class) fun premiumKey() {
        KeyImport.parse("""{"api_config": {}, "auth_data": {}}""", "x")
    }

    @Test fun cidr() {
        assertEquals("10.0.0.0/8", Cidr.parse("10.*").toString())
        assertEquals("192.168.0.0/16", Cidr.parse("192.168.*.*").toString())
        assertNull(Cidr.parse("10.*.3"))
        assertNull(Cidr.parse("example.com"))
        val left = Cidr.subtract(listOf(Cidr.parse("0.0.0.0/0")!!), listOf(Cidr.parse("10.*")!!))
        assertEquals(8, left.size)
        assertTrue(left.none { it.contains(Cidr.parse("10.1.2.3")!!) })
        assertTrue(left.any { it.contains(Cidr.parse("11.0.0.1")!!) })
        assertEquals(emptyList<Cidr>(), Cidr.subtract(listOf(Cidr.parse("10.1.0.0/16")!!), listOf(Cidr.parse("10.*")!!)))
    }
}
