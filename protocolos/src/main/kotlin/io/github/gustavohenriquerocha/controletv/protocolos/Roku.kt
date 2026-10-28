package io.github.gustavohenriquerocha.controletv.protocolos

import okhttp3.OkHttpClient
import java.net.URLEncoder

/**
 * Roku e Roku TV, External Control Protocol (ECP): HTTP POST em `:8060/keypress/<Tecla>`
 * e `:8060/launch/<appId>`, sem pareamento. Não tem ponteiro; texto vai letra a letra
 * como `Lit_<caractere>`. Implementado pela documentação pública; não testado em aparelho real.
 *
 * Nos aparelhos novos o ECP pode vir limitado de fábrica: é preciso ligar
 * "Controle por apps móveis" nas configurações de rede do Roku.
 */
class Roku(
    host: String,
    porta: Int = 8060,
    private val http: OkHttpClient = Rede.http,
) : Tradutor {
    override val marca = Marca.ROKU
    override val recursos = setOf(Recurso.TEXTO, Recurso.CANAIS)

    private val base = "http://$host:$porta"

    override fun conectar(credencial: String?): Pareamento {
        val (r, _) = Rede.get(http, "$base/query/device-info")
        if (r != 200) throw ErroTv("Roku não respondeu (HTTP $r)")
        return Pareamento.Pronto(null)
    }

    private fun post(caminho: String) {
        val (r, _) = Rede.post(http, base + caminho, "", tipo = "text/plain")
        if (r !in 200..299) throw ErroTv("Roku recusou $caminho (HTTP $r)")
    }

    override fun tecla(tecla: Tecla) {
        val nome = when (tecla) {
            Tecla.CIMA -> "Up"
            Tecla.BAIXO -> "Down"
            Tecla.ESQUERDA -> "Left"
            Tecla.DIREITA -> "Right"
            Tecla.OK -> "Select"
            Tecla.VOLTAR -> "Back"
            Tecla.HOME, Tecla.SAIR -> "Home"
            Tecla.MENU -> "Info"
            Tecla.VOLUME_MAIS -> "VolumeUp"
            Tecla.VOLUME_MENOS -> "VolumeDown"
            Tecla.MUDO -> "VolumeMute"
            Tecla.CANAL_MAIS -> "ChannelUp"
            Tecla.CANAL_MENOS -> "ChannelDown"
            Tecla.PLAY, Tecla.PAUSA -> "Play" // no Roku é a mesma tecla (alterna)
            Tecla.ENERGIA -> "PowerOff"
        }
        post("/keypress/$nome")
    }

    override fun enviarTexto(valor: String) {
        for (c in valor) {
            val cod = URLEncoder.encode(c.toString(), "UTF-8").replace("+", "%20")
            post("/keypress/Lit_$cod")
        }
        post("/keypress/Enter")
    }

    /** Abre um canal/app pelo id numérico da loja Roku. */
    fun abrirApp(id: String) = post("/launch/" + URLEncoder.encode(id, "UTF-8"))
}
