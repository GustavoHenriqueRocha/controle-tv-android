package io.github.gustavohenriquerocha.controletv.protocolos

import okhttp3.OkHttpClient

/** Códigos de tecla das LG NetCast (os mesmos em UDAP e ROAP). */
internal val TECLAS_NETCAST = mapOf(
    Tecla.ENERGIA to 1,
    Tecla.CIMA to 12, Tecla.BAIXO to 13, Tecla.ESQUERDA to 14, Tecla.DIREITA to 15,
    Tecla.OK to 20, Tecla.HOME to 21, Tecla.MENU to 22, Tecla.VOLTAR to 23,
    Tecla.VOLUME_MAIS to 24, Tecla.VOLUME_MENOS to 25, Tecla.MUDO to 26,
    Tecla.CANAL_MAIS to 27, Tecla.CANAL_MENOS to 28,
    Tecla.PLAY to 33, Tecla.PAUSA to 34, Tecla.SAIR to 412,
)

/**
 * LG NetCast 4.0 (2013), API UDAP: HTTP POST em `:8080/udap/api/{pairing,command,event}`
 * com envelope XML e `User-Agent: UDAP/2.0`. Testado em aparelho real.
 *
 * A sessão de pareamento expira; por isso o tradutor pareia de novo a cada 10 minutos
 * e sempre que a TV recusa um comando, e repete o comando uma vez.
 */
class LgNetCastUdap(
    host: String,
    porta: Int = 8080,
    private val http: OkHttpClient = Rede.http,
    private val relogio: () -> Long = System::currentTimeMillis,
) : Tradutor {
    override val marca = Marca.LG_NETCAST
    override val recursos = setOf(Recurso.PONTEIRO, Recurso.TEXTO, Recurso.CANAIS)

    private val base = "http://$host:$porta/udap/api/"
    private val trava = Any()
    private var chave: String? = null
    private var pareadoEm = 0L

    private fun enviar(caminho: String, api: String): Int {
        val corpo = "<?xml version=\"1.0\" encoding=\"utf-8\"?><envelope>$api</envelope>"
        return Rede.post(http, base + caminho, corpo, cabecalhos = mapOf("User-Agent" to "UDAP/2.0")).first
    }

    private fun parear(): Boolean {
        val c = chave ?: return false
        val ok = enviar(
            "pairing",
            "<api type=\"pairing\"><name>hello</name><value>${Rede.xml(c)}</value><port>8080</port></api>",
        ) == 200
        if (ok) pareadoEm = relogio()
        return ok
    }

    override fun conectar(credencial: String?): Pareamento = synchronized(trava) {
        if (credencial.isNullOrBlank()) {
            val r = enviar("pairing", "<api type=\"pairing\"><name>showKey</name></api>")
            if (r != 200) throw ErroTv("TV não respondeu ao pedido de chave (HTTP $r)")
            return Pareamento.PedirCodigo
        }
        chave = credencial.trim()
        if (!parear()) throw ErroTv("TV recusou a chave de pareamento")
        Pareamento.Pronto(chave)
    }

    private fun mandar(caminho: String, api: String) = synchronized(trava) {
        if (relogio() - pareadoEm > 10 * 60_000) parear()
        var r = enviar(caminho, api)
        if (r != 200 && parear()) r = enviar(caminho, api)
        if (r != 200) throw ErroTv("TV não aceitou o comando (HTTP $r)")
    }

    private fun comando(conteudo: String) = mandar("command", "<api type=\"command\">$conteudo</api>")

    override fun tecla(tecla: Tecla) {
        val codigo = TECLAS_NETCAST[tecla] ?: throw ErroTv("Tecla $tecla sem código na NetCast")
        comando("<name>HandleKeyInput</name><value>$codigo</value>")
    }

    override fun moverPonteiro(dx: Int, dy: Int) =
        comando("<name>HandleTouchMove</name><x>$dx</x><y>$dy</y>")

    override fun clicar() = comando("<name>HandleTouchClick</name>")

    override fun rolar(paraCima: Boolean) =
        comando("<name>HandleTouchWheel</name><value>${if (paraCima) "up" else "down"}</value>")

    /** Abre um app pelo AUID (ex.: o da loja de apps da TV). */
    fun abrirApp(auid: String) =
        comando("<name>AppExecute</name><auid>${Rede.xml(auid)}</auid><appname></appname><contentId></contentId>")

    override fun enviarTexto(valor: String) {
        // O campo da TV é substituído inteiro: "Editing" escreve, "EditEnd" confirma.
        val v = Rede.xml(valor)
        mandar("event", "<api type=\"event\"><name>TextEdited</name><state>Editing</state><value>$v</value></api>")
        mandar("event", "<api type=\"event\"><name>TextEdited</name><state>EditEnd</state><value>$v</value></api>")
    }
}

/**
 * LG NetCast 3.0 (2012), API ROAP: HTTP POST em `:8080/roap/api/{auth,command}`.
 * Muitas TVs 2013 também aceitam ROAP. Implementado pela documentação pública; não testado em aparelho real.
 */
class LgNetCastRoap(
    host: String,
    porta: Int = 8080,
    private val http: OkHttpClient = Rede.http,
) : Tradutor {
    override val marca = Marca.LG_NETCAST_ROAP
    override val recursos = setOf(Recurso.PONTEIRO, Recurso.CANAIS)

    private val base = "http://$host:$porta/roap/api/"
    private val trava = Any()
    private var chave: String? = null
    private var sessao: String? = null

    private fun enviar(caminho: String, corpo: String): Pair<Int, String> = Rede.post(
        http, base + caminho, "<?xml version=\"1.0\" encoding=\"utf-8\"?>$corpo",
        tipo = "application/atom+xml",
    )

    private fun autenticar(): Boolean {
        val c = chave ?: return false
        val (r, corpo) = enviar("auth", "<auth><type>AuthReq</type><value>${Rede.xml(c)}</value></auth>")
        if (r != 200) return false
        sessao = Regex("<session>([^<]*)</session>").find(corpo)?.groupValues?.get(1)
        return true
    }

    override fun conectar(credencial: String?): Pareamento = synchronized(trava) {
        if (credencial.isNullOrBlank()) {
            val (r, _) = enviar("auth", "<auth><type>AuthKeyReq</type></auth>")
            if (r != 200) throw ErroTv("TV não respondeu ao pedido de chave (HTTP $r)")
            return Pareamento.PedirCodigo
        }
        chave = credencial.trim()
        if (!autenticar()) throw ErroTv("TV recusou a chave de pareamento")
        Pareamento.Pronto(chave)
    }

    private fun comando(conteudo: String) = synchronized(trava) {
        fun tenta() = enviar("command", "<command><session>${sessao ?: ""}</session>$conteudo</command>").first
        var r = tenta()
        if (r != 200 && autenticar()) r = tenta()
        if (r != 200) throw ErroTv("TV não aceitou o comando (HTTP $r)")
    }

    override fun tecla(tecla: Tecla) {
        val codigo = TECLAS_NETCAST[tecla] ?: throw ErroTv("Tecla $tecla sem código na NetCast")
        comando("<name>HandleKeyInput</name><value>$codigo</value>")
    }

    override fun moverPonteiro(dx: Int, dy: Int) =
        comando("<name>HandleTouchMove</name><x>$dx</x><y>$dy</y>")

    override fun clicar() = comando("<name>HandleTouchClick</name>")
}
