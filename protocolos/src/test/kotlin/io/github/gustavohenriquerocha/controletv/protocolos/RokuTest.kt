package io.github.gustavohenriquerocha.controletv.protocolos

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

class RokuTest {
    private val servidor = MockWebServer()

    @Before
    fun subir() = servidor.start()

    @After
    fun descer() = servidor.shutdown()

    private fun caminhos(n: Int) = List(n) {
        val r = servidor.takeRequest(2, TimeUnit.SECONDS)!!
        "${r.method} ${r.path}"
    }

    @Test
    fun conectarConsultaODispositivoETeclasViramKeypress() {
        repeat(4) { servidor.enqueue(MockResponse()) }
        val roku = Roku(servidor.hostName, servidor.port)
        assertEquals(Pareamento.Pronto(null), roku.conectar(null))
        roku.tecla(Tecla.OK)
        roku.tecla(Tecla.VOLTAR)
        roku.tecla(Tecla.VOLUME_MAIS)
        assertEquals(
            listOf("GET /query/device-info", "POST /keypress/Select", "POST /keypress/Back", "POST /keypress/VolumeUp"),
            caminhos(4),
        )
    }

    @Test
    fun textoVaiLetraALetraEConfirma() {
        repeat(5) { servidor.enqueue(MockResponse()) }
        Roku(servidor.hostName, servidor.port).enviarTexto("a b&")
        assertEquals(
            listOf(
                "POST /keypress/Lit_a", "POST /keypress/Lit_%20", "POST /keypress/Lit_b",
                "POST /keypress/Lit_%26", "POST /keypress/Enter",
            ),
            caminhos(5),
        )
    }

    @Test(expected = ErroTv::class)
    fun recusaViraErro() {
        servidor.enqueue(MockResponse().setResponseCode(403))
        Roku(servidor.hostName, servidor.port).tecla(Tecla.HOME)
    }

    @Test(expected = ErroTv::class)
    fun rokuNaoTemPonteiro() {
        Roku(servidor.hostName, servidor.port).moverPonteiro(1, 1)
    }
}
