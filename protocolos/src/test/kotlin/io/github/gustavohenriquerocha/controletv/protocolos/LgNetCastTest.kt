package io.github.gustavohenriquerocha.controletv.protocolos

import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Collections

/** TV NetCast falsa: aceita só a chave "000000" e pode "esquecer" o pareamento. */
class LgNetCastTest {
    private val servidor = MockWebServer()
    private val pedidos = Collections.synchronizedList(mutableListOf<Pair<String, String>>())
    @Volatile private var pareada = false
    @Volatile private var agora = 0L

    @Before
    fun subir() {
        servidor.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val corpo = request.body.readUtf8()
                pedidos += request.path!! to corpo
                if (request.getHeader("User-Agent") != "UDAP/2.0") return MockResponse().setResponseCode(400)
                return when (request.path) {
                    "/udap/api/pairing" -> when {
                        "showKey" in corpo -> MockResponse()
                        "<value>000000</value>" in corpo -> { pareada = true; MockResponse() }
                        else -> MockResponse().setResponseCode(401)
                    }
                    "/udap/api/command", "/udap/api/event" ->
                        if (pareada) MockResponse() else MockResponse().setResponseCode(401)
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        servidor.start()
    }

    @After
    fun descer() = servidor.shutdown()

    private fun tv() = LgNetCastUdap(servidor.hostName, servidor.port, relogio = { agora })

    @Test
    fun semChavePedeQueATvMostreOCodigo() {
        assertEquals(Pareamento.PedirCodigo, tv().conectar(null))
        assertTrue(pedidos.single().second.contains("<name>showKey</name>"))
    }

    @Test
    fun chaveCertaConectaEDevolveACredencial() {
        assertEquals(Pareamento.Pronto("000000"), tv().conectar("000000"))
    }

    @Test(expected = ErroTv::class)
    fun chaveErradaLancaErro() {
        tv().conectar("999999")
    }

    @Test
    fun teclaVaiComOCodigoNetCast() {
        val t = tv()
        t.conectar("000000")
        t.tecla(Tecla.OK)
        t.tecla(Tecla.VOLUME_MAIS)
        val comandos = pedidos.filter { it.first == "/udap/api/command" }.map { it.second }
        assertTrue(comandos[0].contains("<name>HandleKeyInput</name><value>20</value>"))
        assertTrue(comandos[1].contains("<value>24</value>"))
        assertTrue(comandos[0].startsWith("<?xml version=\"1.0\" encoding=\"utf-8\"?><envelope><api type=\"command\">"))
    }

    @Test
    fun sessaoExpiradaPareiaDeNovoERepete() {
        val t = tv()
        t.conectar("000000")
        pareada = false // TV reiniciou e esqueceu
        t.tecla(Tecla.CIMA)
        val caminhos = pedidos.map { it.first }
        assertEquals(
            listOf("/udap/api/pairing", "/udap/api/command", "/udap/api/pairing", "/udap/api/command"),
            caminhos,
        )
    }

    @Test
    fun pareiaDeNovoAposDezMinutos() {
        val t = tv()
        t.conectar("000000")
        agora += 11 * 60_000
        t.tecla(Tecla.CIMA)
        assertEquals(listOf("/udap/api/pairing", "/udap/api/pairing", "/udap/api/command"), pedidos.map { it.first })
    }

    @Test
    fun ponteiroCliqueERoda() {
        val t = tv()
        t.conectar("000000")
        t.moverPonteiro(5, -3)
        t.clicar()
        t.rolar(paraCima = true)
        val c = pedidos.filter { it.first == "/udap/api/command" }.map { it.second }
        assertTrue(c[0].contains("<name>HandleTouchMove</name><x>5</x><y>-3</y>"))
        assertTrue(c[1].contains("<name>HandleTouchClick</name>"))
        assertTrue(c[2].contains("<name>HandleTouchWheel</name><value>up</value>"))
    }

    @Test
    fun textoEscapadoEConfirmado() {
        val t = tv()
        t.conectar("000000")
        t.enviarTexto("a&b<c>")
        val e = pedidos.filter { it.first == "/udap/api/event" }.map { it.second }
        assertEquals(2, e.size)
        assertTrue(e[0].contains("<state>Editing</state><value>a&amp;b&lt;c&gt;</value>"))
        assertTrue(e[1].contains("<state>EditEnd</state>"))
    }

    @Test(expected = ErroTv::class)
    fun tvForaDoArViraErroTv() {
        val portaFechada = java.net.ServerSocket(0).use { it.localPort }
        LgNetCastUdap("127.0.0.1", portaFechada).conectar("000000")
    }
}
