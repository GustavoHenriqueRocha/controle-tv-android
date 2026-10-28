package io.github.gustavohenriquerocha.controletv.protocolos

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** WebSocket com abertura bloqueante e um callback para cada mensagem de texto recebida. */
internal class CanalWs(
    cliente: OkHttpClient,
    url: String,
    private val aoReceber: (String) -> Unit,
    private val aoFechar: (Throwable?) -> Unit = {},
) {
    private val aberto = CompletableFuture<Unit>()

    @Volatile
    var vivo = false
        private set

    private val ws: WebSocket = cliente.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            vivo = true
            aberto.complete(Unit)
        }

        override fun onMessage(webSocket: WebSocket, text: String) = aoReceber(text)

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            vivo = false
            webSocket.close(1000, null)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            vivo = false
            aberto.completeExceptionally(ErroTv("conexão fechada pela TV"))
            aoFechar(null)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            vivo = false
            aberto.completeExceptionally(t)
            aoFechar(t)
        }
    })

    /** Espera a conexão abrir; lança [ErroTv] se não abrir no prazo. */
    fun esperarAbrir(prazoMs: Long = 5_000): CanalWs {
        try {
            aberto.get(prazoMs, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            fechar()
            throw ErroTv("TV não abriu a conexão", e)
        } catch (e: ExecutionException) {
            throw ErroTv("Falha ao conectar: ${e.cause?.message}", e.cause)
        }
        return this
    }

    fun enviar(texto: String) {
        if (!vivo || !ws.send(texto)) throw ErroTv("Conexão com a TV caiu")
    }

    fun fechar() {
        vivo = false
        ws.close(1000, null)
    }
}
