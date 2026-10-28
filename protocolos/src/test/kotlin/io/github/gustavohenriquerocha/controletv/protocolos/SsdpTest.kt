package io.github.gustavohenriquerocha.controletv.protocolos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import kotlin.concurrent.thread

class SsdpTest {
    private fun resposta(vararg linhas: String) = (listOf("HTTP/1.1 200 OK") + linhas).joinToString("\r\n") + "\r\n\r\n"

    @Test
    fun reconheceCadaMarca() {
        val roku = Ssdp.interpretar(resposta("ST: roku:ecp", "LOCATION: http://10.0.0.7:8060/", "USN: uuid:roku:ecp:X"), "10.0.0.99")
        assertEquals(TvEncontrada(Marca.ROKU, "10.0.0.7", "Roku (10.0.0.7)"), roku)

        val webos = Ssdp.interpretar(
            resposta("st: urn:lge-com:service:webos-second-screen:1", "Server: WebOS/4.1.0 UPnP/1.0", "location: http://10.0.0.8:1876/"),
            "10.0.0.8",
        )
        assertEquals(Marca.LG_WEBOS, webos!!.marca)
        assertEquals("10.0.0.8", webos.host)

        val samsung = Ssdp.interpretar(resposta("ST: urn:samsung.com:device:RemoteControlReceiver:1"), "10.0.0.9")
        assertEquals(TvEncontrada(Marca.SAMSUNG_TIZEN, "10.0.0.9", "Samsung Tizen 2016+ (10.0.0.9)"), samsung)

        val netcast = Ssdp.interpretar(resposta("ST: urn:schemas-udap:service:netrcu:1"), "10.0.0.10")
        assertEquals(Marca.LG_NETCAST, netcast!!.marca)
    }

    @Test
    fun ignoraOutrosAparelhos() {
        assertNull(Ssdp.interpretar(resposta("ST: urn:schemas-upnp-org:device:InternetGatewayDevice:1"), "10.0.0.1"))
    }

    @Test
    fun mensagemDeBusca() {
        val m = Ssdp.mensagem("roku:ecp")
        assertTrue(m.startsWith("M-SEARCH * HTTP/1.1\r\n"))
        assertTrue(m.contains("MAN: \"ssdp:discover\"\r\n"))
        assertTrue(m.contains("ST: roku:ecp\r\n"))
        assertTrue(m.endsWith("\r\n\r\n"))
    }

    @Test
    fun procurarComRespondedorFalsoLocal() {
        val falso = DatagramSocket(0, InetAddress.getLoopbackAddress())
        falso.soTimeout = 3_000
        val t = thread {
            falso.use { s ->
                val buf = ByteArray(1024)
                val p = DatagramPacket(buf, buf.size)
                s.receive(p)
                val r = resposta("ST: roku:ecp", "LOCATION: http://10.0.0.7:8060/").toByteArray()
                // responde duas vezes: a lista não pode repetir a TV
                repeat(2) { s.send(DatagramPacket(r, r.size, p.socketAddress)) }
            }
        }
        val achadas = Ssdp.procurar(prazoMs = 1_500, destino = InetSocketAddress(InetAddress.getLoopbackAddress(), falso.localPort))
        t.join()
        assertEquals(listOf(TvEncontrada(Marca.ROKU, "10.0.0.7", "Roku (10.0.0.7)")), achadas)
    }
}
