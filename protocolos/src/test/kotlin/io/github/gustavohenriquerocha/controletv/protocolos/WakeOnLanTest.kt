package io.github.gustavohenriquerocha.controletv.protocolos

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Test
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

class WakeOnLanTest {
    @Test
    fun pacoteTemSeisFFEDezesseisVezesOMac() {
        val p = WakeOnLan.pacote("A4:30:7A:01:02:0F")
        assertEquals(102, p.size)
        assertTrue(p.take(6).all { it == 0xFF.toByte() })
        val mac = byteArrayOf(0xA4.toByte(), 0x30, 0x7A, 0x01, 0x02, 0x0F)
        for (i in 0 until 16) assertArrayEquals(mac, p.copyOfRange(6 + 6 * i, 12 + 6 * i))
    }

    @Test
    fun aceitaHifenERecusaLixo() {
        assertTrue(WakeOnLan.valido("a4-30-7a-01-02-0f"))
        assertFalse(WakeOnLan.valido("a4:30:7a"))
        assertFalse(WakeOnLan.valido("não é mac"))
    }

    @Test
    fun destinosIncluemBroadcastDaRedeEATv() {
        assertEquals(listOf("255.255.255.255", "192.168.0.255", "192.168.0.42"), WakeOnLan.destinos("192.168.0.42"))
        assertEquals(listOf("255.255.255.255", "tv.local"), WakeOnLan.destinos("tv.local"))
    }

    @Test
    fun pacoteChegaNaTv() {
        DatagramSocket(0, InetAddress.getLoopbackAddress()).use { tv ->
            tv.soTimeout = 2_000
            WakeOnLan.acordar("a4:30:7a:01:02:0f", "127.0.0.1", tv.localPort)
            val buf = ByteArray(200)
            val pkt = DatagramPacket(buf, buf.size)
            tv.receive(pkt)
            assertArrayEquals(WakeOnLan.pacote("a4:30:7a:01:02:0f"), buf.copyOf(pkt.length))
        }
    }

    @Test
    fun credencialSamsungGuardaTokenEMac() {
        assertEquals("tok|a4:30:7a:01:02:0f", SamsungTizen.juntar("tok", "a4:30:7a:01:02:0f"))
        assertEquals("tok", SamsungTizen.token("tok|a4:30:7a:01:02:0f"))
        assertEquals("a4:30:7a:01:02:0f", SamsungTizen.mac("tok|a4:30:7a:01:02:0f"))
        // credencial antiga (só token) continua valendo
        assertEquals("tok", SamsungTizen.token("tok"))
        assertNull(SamsungTizen.mac("tok"))
        assertEquals("tok", SamsungTizen.juntar("tok", null))
    }

    @Test
    fun macVemDaApiRestDaTv() {
        MockWebServer().use { api ->
            api.enqueue(MockResponse().setBody("""{"device":{"name":"[TV] Samsung AU7700","wifiMac":"a4:30:7a:01:02:0f"}}"""))
            api.start()
            assertEquals("a4:30:7a:01:02:0f", SamsungTizen(api.hostName).lerMac(api.port))
        }
    }
}
