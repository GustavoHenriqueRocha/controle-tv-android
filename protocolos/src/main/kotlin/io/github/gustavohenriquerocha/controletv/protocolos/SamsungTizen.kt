package io.github.gustavohenriquerocha.controletv.protocolos

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.net.URLEncoder
import java.util.Base64
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Samsung Tizen (2016+): WebSocket em `/api/v2/channels/samsung.remote.control`.
 * Modelos 2018+ usam `wss://tv:8002` e exigem token: na primeira conexão a TV pergunta
 * se permite o controle e, aceito, manda o token no evento `ms.channel.connect`.
 * Modelos 2016–2017 usam `ws://tv:8001` sem token.
 *
 * Implementado pela documentação pública e por bibliotecas abertas; não testado em aparelho real.
 */
class SamsungTizen(
    private val host: String,
    private val enderecos: List<Pair<Int, Boolean>> = listOf(8002 to true, 8001 to false),
    private val nomeApp: String = "Controle TV",
    private val prazoPareamentoMs: Long = 30_000,
) : Tradutor {
    override val marca = Marca.SAMSUNG_TIZEN
    override val recursos = setOf(Recurso.PONTEIRO, Recurso.TEXTO, Recurso.CANAIS)

    private var canal: CanalWs? = null
    private val trava = Any()

    private fun b64(s: String) = Base64.getEncoder().encodeToString(s.toByteArray(Charsets.UTF_8))

    internal fun url(porta: Int, seguro: Boolean, token: String?): String {
        val esquema = if (seguro) "wss" else "ws"
        val nome = URLEncoder.encode(b64(nomeApp), "UTF-8")
        val sufixo = if (token.isNullOrBlank()) "" else "&token=" + URLEncoder.encode(token, "UTF-8")
        return "$esquema://$host:$porta/api/v2/channels/samsung.remote.control?name=$nome$sufixo"
    }

    override fun conectar(credencial: String?): Pareamento = synchronized(trava) {
        close()
        var erro: Throwable? = null
        for ((porta, seguro) in enderecos) {
            val conectado = CompletableFuture<String?>()
            val cliente = if (seguro) Rede.tvAutoassinada else Rede.http
            val ws = try {
                CanalWs(cliente, url(porta, seguro, credencial), { texto ->
                    val msg = runCatching { Json.parseToJsonElement(texto).jsonObject }.getOrNull()
                    when (msg?.get("event")?.jsonPrimitive?.contentOrNull) {
                        "ms.channel.connect" -> conectado.complete(
                            (msg?.get("data") as? JsonObject)?.get("token")?.jsonPrimitive?.contentOrNull,
                        )
                        "ms.channel.unauthorized" -> conectado.completeExceptionally(ErroTv("A TV negou o acesso"))
                        "ms.channel.timeOut" -> conectado.completeExceptionally(ErroTv("Pedido expirou na TV"))
                    }
                }) { causa -> conectado.completeExceptionally(ErroTv("Conexão com a TV caiu", causa)) }
                    .esperarAbrir()
            } catch (e: ErroTv) {
                erro = e
                continue
            }
            try {
                val token = conectado.get(prazoPareamentoMs, TimeUnit.MILLISECONDS)
                canal = ws
                return Pareamento.Pronto(token ?: credencial)
            } catch (e: TimeoutException) {
                ws.fechar()
                throw ErroTv("A TV não autorizou a tempo. Aceite o pedido que aparece na tela.")
            } catch (e: ExecutionException) {
                ws.fechar()
                throw (e.cause as? ErroTv) ?: ErroTv("Falha ao conectar", e.cause)
            }
        }
        throw ErroTv("TV Samsung não respondeu", erro)
    }

    private fun mandar(params: JsonObject) {
        val c = canal ?: throw ErroTv("Não conectado")
        c.enviar(buildJsonObject {
            put("method", "ms.remote.control")
            put("params", params)
        }.toString())
    }

    override fun tecla(tecla: Tecla) {
        val codigo = when (tecla) {
            Tecla.CIMA -> "KEY_UP"
            Tecla.BAIXO -> "KEY_DOWN"
            Tecla.ESQUERDA -> "KEY_LEFT"
            Tecla.DIREITA -> "KEY_RIGHT"
            Tecla.OK -> "KEY_ENTER"
            Tecla.VOLTAR -> "KEY_RETURN"
            Tecla.HOME -> "KEY_HOME"
            Tecla.MENU -> "KEY_MENU"
            Tecla.SAIR -> "KEY_EXIT"
            Tecla.VOLUME_MAIS -> "KEY_VOLUP"
            Tecla.VOLUME_MENOS -> "KEY_VOLDOWN"
            Tecla.MUDO -> "KEY_MUTE"
            Tecla.CANAL_MAIS -> "KEY_CHUP"
            Tecla.CANAL_MENOS -> "KEY_CHDOWN"
            Tecla.PLAY -> "KEY_PLAY"
            Tecla.PAUSA -> "KEY_PAUSE"
            Tecla.ENERGIA -> "KEY_POWER"
        }
        mandar(buildJsonObject {
            put("Cmd", "Click")
            put("DataOfCmd", codigo)
            put("Option", "false")
            put("TypeOfRemote", "SendRemoteKey")
        })
    }

    override fun moverPonteiro(dx: Int, dy: Int) = mandar(buildJsonObject {
        put("Cmd", "Move")
        put("Position", buildJsonObject {
            put("x", dx)
            put("y", dy)
            put("Time", System.currentTimeMillis().toString())
        })
        put("TypeOfRemote", "ProcessMouseDevice")
    })

    override fun clicar() = mandar(buildJsonObject {
        put("Cmd", "LeftClick")
        put("TypeOfRemote", "ProcessMouseDevice")
    })

    override fun enviarTexto(valor: String) {
        mandar(buildJsonObject {
            put("Cmd", b64(valor))
            put("DataOfCmd", "base64")
            put("TypeOfRemote", "SendInputString")
        })
        mandar(buildJsonObject { put("TypeOfRemote", "SendInputEnd") })
    }

    override fun close() {
        canal?.fechar()
        canal = null
    }
}
