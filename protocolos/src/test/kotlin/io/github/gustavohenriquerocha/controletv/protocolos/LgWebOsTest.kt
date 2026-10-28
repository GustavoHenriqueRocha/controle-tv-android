package io.github.gustavohenriquerocha.controletv.protocolos

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** TV webOS falsa: socket principal SSAP + pointer socket, no mesmo servidor local. */
class LgWebOsTest {
    private val servidor = MockWebServer()
    private val principal = LinkedBlockingQueue<JsonObject>()
    private val ponteiro = LinkedBlockingQueue<String>()

    /** O que a TV falsa responde ao registro. */
    @Volatile private var respostaRegistro: List<String> = listOf(
        """{"type":"response","id":"register_0","payload":{"pairingType":"PROMPT","returnValue":true}}""",
        """{"type":"registered","id":"register_0","payload":{"client-key":"chave-falsa"}}""",
    )

    private val tvPrincipal = object : WebSocketListener() {
        override fun onMessage(webSocket: WebSocket, text: String) {
            val msg = Json.parseToJsonElement(text).jsonObject
            principal += msg
            val id = msg["id"]?.jsonPrimitive?.contentOrNull
            when {
                msg["type"]?.jsonPrimitive?.contentOrNull == "register" -> respostaRegistro.forEach { webSocket.send(it) }
                msg["uri"]?.jsonPrimitive?.contentOrNull == "ssap://com.webos.service.networkinput/getPointerInputSocket" -> {
                    val caminho = "ws://${servidor.hostName}:${servidor.port}/resources/teste/netinput.pointer.sock"
                    webSocket.send("""{"type":"response","id":"$id","payload":{"returnValue":true,"socketPath":"$caminho"}}""")
                }
                else -> webSocket.send("""{"type":"response","id":"$id","payload":{"returnValue":true}}""")
            }
        }
    }

    private val tvPonteiro = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {}
        override fun onMessage(webSocket: WebSocket, text: String) {
            ponteiro += text
        }
    }

    @Before
    fun subir() {
        servidor.enqueue(MockResponse().withWebSocketUpgrade(tvPrincipal))
        servidor.enqueue(MockResponse().withWebSocketUpgrade(tvPonteiro))
        servidor.start()
    }

    @After
    fun descer() = servidor.shutdown()

    private fun tv(prazo: Long = 5_000) =
        LgWebOs(servidor.hostName, listOf(servidor.port to false), prazoPareamentoMs = prazo)

    private fun proximaPrincipal() = principal.poll(3, TimeUnit.SECONDS)!!

    @Test
    fun registroDevolveClientKey() {
        val t = tv()
        assertEquals(Pareamento.Pronto("chave-falsa"), t.conectar(null))
        val reg = proximaPrincipal()
        assertEquals("register", reg["type"]?.jsonPrimitive?.contentOrNull)
        val payload = reg["payload"]!!.jsonObject
        assertEquals("PROMPT", payload["pairingType"]?.jsonPrimitive?.contentOrNull)
        assertNotNull(payload["manifest"]!!.jsonObject["permissions"])
        assertTrue("sem chave guardada não manda client-key", payload["client-key"] == null)
        t.close()
    }

    @Test
    fun registroComChaveGuardadaMandaAChave() {
        val t = tv()
        t.conectar("chave-falsa")
        val payload = proximaPrincipal()["payload"]!!.jsonObject
        assertEquals("chave-falsa", payload["client-key"]?.jsonPrimitive?.contentOrNull)
        t.close()
    }

    @Test
    fun setasVaoPeloPointerSocketEVolumePeloSsap() {
        val t = tv()
        t.conectar(null)
        proximaPrincipal() // registro

        t.tecla(Tecla.CIMA)
        assertEquals("ssap://com.webos.service.networkinput/getPointerInputSocket",
            proximaPrincipal()["uri"]?.jsonPrimitive?.contentOrNull)
        assertEquals("type:button\nname:UP\n\n", ponteiro.poll(3, TimeUnit.SECONDS))

        t.moverPonteiro(4, -2)
        assertEquals("type:move\ndx:4\ndy:-2\ndown:0\n\n", ponteiro.poll(3, TimeUnit.SECONDS))
        t.clicar()
        assertEquals("type:click\n\n", ponteiro.poll(3, TimeUnit.SECONDS))

        t.tecla(Tecla.VOLUME_MAIS)
        assertEquals("ssap://audio/volumeUp", proximaPrincipal()["uri"]?.jsonPrimitive?.contentOrNull)
        t.close()
    }

    @Test
    fun textoUsaImeEEnter() {
        val t = tv()
        t.conectar(null)
        proximaPrincipal()
        t.enviarTexto("novela")
        val inserir = proximaPrincipal()
        assertEquals("ssap://com.webos.service.ime/insertText", inserir["uri"]?.jsonPrimitive?.contentOrNull)
        assertEquals("novela", inserir["payload"]!!.jsonObject["text"]?.jsonPrimitive?.contentOrNull)
        assertEquals("ssap://com.webos.service.ime/sendEnterKey", proximaPrincipal()["uri"]?.jsonPrimitive?.contentOrNull)
        t.close()
    }

    @Test(expected = ErroTv::class)
    fun tvRecusaORegistro() {
        respostaRegistro = listOf("""{"type":"error","id":"register_0","error":"403 User denied access"}""")
        tv().use { it.conectar(null) }
    }

    @Test(expected = ErroTv::class)
    fun ninguemAceitaNaTvDentroDoPrazo() {
        respostaRegistro = emptyList()
        tv(prazo = 300).use { it.conectar(null) }
    }

    @Test(expected = ErroTv::class)
    fun semTvNaPortaViraErroTv() {
        val portaFechada = java.net.ServerSocket(0).use { it.localPort }
        LgWebOs("127.0.0.1", listOf(portaFechada to false)).use { it.conectar(null) }
    }
}
