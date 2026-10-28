package io.github.gustavohenriquerocha.controletv.protocolos

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** TV Samsung falsa: ao abrir, manda o evento de conexão (ou de recusa). */
class SamsungTizenTest {
    private val servidor = MockWebServer()
    private val recebidas = LinkedBlockingQueue<String>()

    @Volatile private var aoAbrir =
        """{"event":"ms.channel.connect","data":{"id":"x","token":"token-falso","clients":[]}}"""

    @Before
    fun subir() {
        servidor.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                abertos += webSocket
                webSocket.send(aoAbrir)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                recebidas += text
            }
        }))
        servidor.start()
    }

    /** Conexões abertas pela TV falsa: derrubadas antes do shutdown, senão ele espera para sempre. */
    private val abertos = java.util.concurrent.CopyOnWriteArrayList<WebSocket>()

    @After
    fun descer() {
        abertos.forEach { it.cancel() }
        servidor.shutdown()
    }

    private fun tv() = SamsungTizen(servidor.hostName, listOf(servidor.port to false), prazoPareamentoMs = 3_000)

    private fun proxima() = Json.parseToJsonElement(recebidas.poll(3, TimeUnit.SECONDS)!!).jsonObject

    @Test
    fun primeiraConexaoGuardaOToken() {
        val t = tv()
        assertEquals(Pareamento.Pronto("token-falso"), t.conectar(null))
        val caminho = servidor.takeRequest(2, TimeUnit.SECONDS)!!.path!!
        // name = base64("Controle TV"), com o "=" codificado
        assertEquals("/api/v2/channels/samsung.remote.control?name=Q29udHJvbGUgVFY%3D", caminho)
        t.close()
    }

    @Test
    fun conexaoComTokenMandaOTokenNaUrl() {
        val t = tv()
        t.conectar("token-falso")
        assertTrue(servidor.takeRequest(2, TimeUnit.SECONDS)!!.path!!.endsWith("&token=token-falso"))
        t.close()
    }

    @Test
    fun teclaViraSendRemoteKey() {
        val t = tv()
        t.conectar(null)
        t.tecla(Tecla.HOME)
        val msg = proxima()
        assertEquals("ms.remote.control", msg["method"]?.jsonPrimitive?.contentOrNull)
        val p = msg["params"]!!.jsonObject
        assertEquals("Click", p["Cmd"]?.jsonPrimitive?.contentOrNull)
        assertEquals("KEY_HOME", p["DataOfCmd"]?.jsonPrimitive?.contentOrNull)
        assertEquals("SendRemoteKey", p["TypeOfRemote"]?.jsonPrimitive?.contentOrNull)
        t.close()
    }

    @Test
    fun ponteiroETexto() {
        val t = tv()
        t.conectar(null)
        t.moverPonteiro(7, 3)
        val mover = proxima()["params"]!!.jsonObject
        assertEquals("Move", mover["Cmd"]?.jsonPrimitive?.contentOrNull)
        assertEquals("7", mover["Position"]!!.jsonObject["x"]?.jsonPrimitive?.contentOrNull)

        t.enviarTexto("oi")
        val texto = proxima()["params"]!!.jsonObject
        assertEquals("SendInputString", texto["TypeOfRemote"]?.jsonPrimitive?.contentOrNull)
        assertEquals("b2k=", texto["Cmd"]?.jsonPrimitive?.contentOrNull)
        assertEquals("SendInputEnd", proxima()["params"]!!.jsonObject["TypeOfRemote"]?.jsonPrimitive?.contentOrNull)
        t.close()
    }

    @Test(expected = ErroTv::class)
    fun tvNegaOAcesso() {
        aoAbrir = """{"event":"ms.channel.unauthorized"}"""
        tv().use { it.conectar(null) }
    }
}
