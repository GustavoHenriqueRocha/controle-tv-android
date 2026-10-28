package io.github.gustavohenriquerocha.controletv.protocolos

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger

/**
 * LG webOS (2014+), protocolo SSAP: WebSocket em `ws://tv:3000` (ou `wss://tv:3001` nos
 * firmwares novos, com certificado autoassinado). O primeiro registro faz a TV mostrar um
 * pedido de autorização; aceito, ela devolve uma `client-key`, que é a credencial guardada.
 *
 * Setas, OK, voltar e home vão pelo "pointer socket" (um segundo WebSocket com mensagens
 * de texto `type:button`, `type:move`...), igual ao Magic Remote. Volume, canal, mídia,
 * texto e desligar vão como requisições SSAP.
 *
 * Implementado pela documentação pública e por bibliotecas abertas; não testado em aparelho real.
 */
class LgWebOs(
    private val host: String,
    /** Endereços tentados em ordem: (porta, seguro). */
    private val enderecos: List<Pair<Int, Boolean>> = listOf(3001 to true, 3000 to false),
    private val prazoPareamentoMs: Long = 60_000,
) : Tradutor {
    override val marca = Marca.LG_WEBOS
    override val recursos = setOf(Recurso.PONTEIRO, Recurso.TEXTO, Recurso.CANAIS)

    private val seq = AtomicInteger()
    private val pendentes = ConcurrentHashMap<String, CompletableFuture<JsonObject>>()
    private var registro = CompletableFuture<String>()
    private var principal: CanalWs? = null
    private var ponteiro: CanalWs? = null
    private var seguro = false
    private val trava = Any()

    private fun cliente(seguro: Boolean): OkHttpClient = if (seguro) Rede.tvAutoassinada else Rede.http

    override fun conectar(credencial: String?): Pareamento = synchronized(trava) {
        close()
        var erro: Throwable? = null
        for ((porta, seg) in enderecos) {
            try {
                val reg = CompletableFuture<String>()
                registro = reg
                val esquema = if (seg) "wss" else "ws"
                principal = CanalWs(cliente(seg), "$esquema://$host:$porta/", ::receber) { causa ->
                    // Só derruba as esperas se não houver outra conexão viva no lugar desta.
                    val e = ErroTv("Conexão com a TV caiu", causa)
                    reg.completeExceptionally(e)
                    if (principal?.vivo != true) {
                        pendentes.values.forEach { it.completeExceptionally(e) }
                        pendentes.clear()
                    }
                }.esperarAbrir()
                seguro = seg
                break
            } catch (e: ErroTv) {
                erro = e
            }
        }
        val canal = principal ?: throw ErroTv("TV webOS não respondeu", erro)
        canal.enviar(mensagemRegistro(credencial).toString())
        val chave = try {
            registro.get(prazoPareamentoMs, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            throw ErroTv("A TV não autorizou a tempo. Aceite o pedido que aparece na tela.")
        } catch (e: ExecutionException) {
            throw ErroTv(e.cause?.message ?: "TV recusou o registro", e.cause)
        }
        Pareamento.Pronto(chave)
    }

    private fun receber(texto: String) {
        val msg = runCatching { Json.parseToJsonElement(texto).jsonObject }.getOrNull() ?: return
        val tipo = msg["type"]?.jsonPrimitive?.contentOrNull
        val id = msg["id"]?.jsonPrimitive?.contentOrNull
        val payload = (msg["payload"] as? JsonObject) ?: JsonObject(emptyMap())
        when {
            tipo == "registered" -> {
                val chave = payload["client-key"]?.jsonPrimitive?.contentOrNull
                if (chave != null) registro.complete(chave)
                else registro.completeExceptionally(ErroTv("TV registrou sem devolver client-key"))
            }
            tipo == "error" && id == ID_REGISTRO ->
                registro.completeExceptionally(ErroTv("TV recusou: ${msg["error"]?.jsonPrimitive?.contentOrNull}"))
            id != null -> pendentes.remove(id)?.let { futuro ->
                if (tipo == "error") futuro.completeExceptionally(ErroTv("TV recusou: ${msg["error"]?.jsonPrimitive?.contentOrNull}"))
                else futuro.complete(payload)
            }
        }
    }

    /** Manda uma requisição SSAP e espera a resposta. */
    fun pedir(uri: String, payload: JsonObject? = null, prazoMs: Long = 5_000): JsonObject {
        val canal = principal ?: throw ErroTv("Não conectado")
        val id = "req_${seq.incrementAndGet()}"
        val futuro = CompletableFuture<JsonObject>()
        pendentes[id] = futuro
        canal.enviar(buildJsonObject {
            put("id", id)
            put("type", "request")
            put("uri", uri)
            if (payload != null) put("payload", payload)
        }.toString())
        return try {
            futuro.get(prazoMs, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            pendentes.remove(id)
            throw ErroTv("TV não respondeu a $uri")
        } catch (e: ExecutionException) {
            throw (e.cause as? ErroTv) ?: ErroTv("Falha em $uri", e.cause)
        }
    }

    private fun ponteiro(): CanalWs = synchronized(trava) {
        ponteiro?.takeIf { it.vivo }?.let { return it }
        val resp = pedir("ssap://com.webos.service.networkinput/getPointerInputSocket")
        var caminho = resp["socketPath"]?.jsonPrimitive?.contentOrNull
            ?: throw ErroTv("TV não informou o socket do ponteiro")
        // Nos firmwares que só aceitam wss, o caminho anunciado ainda vem como ws://...:3000.
        if (seguro) caminho = caminho.replaceFirst("ws://", "wss://").replace(":3000/", ":3001/")
        CanalWs(cliente(seguro), caminho, {}).esperarAbrir().also { ponteiro = it }
    }

    private fun botao(nome: String) = ponteiro().enviar("type:button\nname:$nome\n\n")

    override fun tecla(tecla: Tecla) {
        when (tecla) {
            Tecla.CIMA -> botao("UP")
            Tecla.BAIXO -> botao("DOWN")
            Tecla.ESQUERDA -> botao("LEFT")
            Tecla.DIREITA -> botao("RIGHT")
            Tecla.OK -> botao("ENTER")
            Tecla.VOLTAR -> botao("BACK")
            Tecla.HOME -> botao("HOME")
            Tecla.MENU -> botao("MENU")
            Tecla.SAIR -> botao("EXIT")
            Tecla.MUDO -> botao("MUTE")
            Tecla.VOLUME_MAIS -> pedir("ssap://audio/volumeUp")
            Tecla.VOLUME_MENOS -> pedir("ssap://audio/volumeDown")
            Tecla.CANAL_MAIS -> pedir("ssap://tv/channelUp")
            Tecla.CANAL_MENOS -> pedir("ssap://tv/channelDown")
            Tecla.PLAY -> pedir("ssap://media.controls/play")
            Tecla.PAUSA -> pedir("ssap://media.controls/pause")
            Tecla.ENERGIA -> pedir("ssap://system/turnOff")
        }
    }

    override fun moverPonteiro(dx: Int, dy: Int) = ponteiro().enviar("type:move\ndx:$dx\ndy:$dy\ndown:0\n\n")

    override fun clicar() = ponteiro().enviar("type:click\n\n")

    override fun rolar(paraCima: Boolean) =
        ponteiro().enviar("type:scroll\ndx:0\ndy:${if (paraCima) -1 else 1}\n\n")

    override fun enviarTexto(valor: String) {
        pedir("ssap://com.webos.service.ime/insertText", buildJsonObject {
            put("text", valor)
            put("replace", 0)
        })
        pedir("ssap://com.webos.service.ime/sendEnterKey")
    }

    /** Abre um app pelo id (ex.: "youtube.leanback.v4", "netflix"). */
    fun abrirApp(id: String) {
        pedir("ssap://system.launcher/launch", buildJsonObject { put("id", id) })
    }

    override fun close() {
        ponteiro?.fechar()
        principal?.fechar()
        ponteiro = null
        principal = null
    }

    internal companion object {
        const val ID_REGISTRO = "register_0"

        fun mensagemRegistro(chave: String?): JsonObject = buildJsonObject {
            put("type", "register")
            put("id", ID_REGISTRO)
            put("payload", buildJsonObject {
                put("forcePairing", false)
                put("pairingType", "PROMPT")
                if (!chave.isNullOrBlank()) put("client-key", chave)
                put("manifest", Json.parseToJsonElement(MANIFESTO_WEBOS))
            })
        }
    }
}
