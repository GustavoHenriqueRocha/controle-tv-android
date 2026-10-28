package io.github.gustavohenriquerocha.controletv.protocolos

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.net.URI

/** TV achada na rede. */
data class TvEncontrada(val marca: Marca, val host: String, val nome: String)

/**
 * Descoberta por SSDP (UPnP): manda um M-SEARCH para o grupo multicast 239.255.255.250:1900
 * para cada tipo de serviço conhecido e classifica as respostas.
 *
 * No Android é preciso segurar um `WifiManager.MulticastLock` enquanto procura.
 */
object Ssdp {
    val GRUPO = InetSocketAddress("239.255.255.250", 1900)

    /** Tipos de serviço (ST) procurados, um por marca. */
    val BUSCAS = listOf(
        "roku:ecp",
        "urn:lge-com:service:webos-second-screen:1",
        "urn:samsung.com:device:RemoteControlReceiver:1",
        "urn:schemas-udap:service:netrcu:1",
    )

    fun mensagem(st: String): String =
        "M-SEARCH * HTTP/1.1\r\n" +
            "HOST: 239.255.255.250:1900\r\n" +
            "MAN: \"ssdp:discover\"\r\n" +
            "MX: 2\r\n" +
            "ST: $st\r\n" +
            (if (st.contains("udap")) "USER-AGENT: UDAP/2.0\r\n" else "") +
            "\r\n"

    /** Lê os cabeçalhos de uma resposta SSDP (nomes em minúsculas). */
    fun cabecalhos(resposta: String): Map<String, String> = resposta.split("\r\n", "\n")
        .drop(1)
        .mapNotNull { linha ->
            val i = linha.indexOf(':')
            if (i <= 0) null else linha.substring(0, i).trim().lowercase() to linha.substring(i + 1).trim()
        }.toMap()

    /** Classifica uma resposta; devolve null se não for de uma TV conhecida. */
    fun interpretar(resposta: String, remetente: String): TvEncontrada? {
        val h = cabecalhos(resposta)
        val tipo = listOfNotNull(h["st"], h["nt"], h["usn"]).joinToString(" ").lowercase()
        val servidor = h["server"].orEmpty()
        val marca = when {
            "roku:ecp" in tipo -> Marca.ROKU
            "webos-second-screen" in tipo -> Marca.LG_WEBOS
            "samsung.com:device:remotecontrolreceiver" in tipo -> Marca.SAMSUNG_TIZEN
            "udap" in tipo -> Marca.LG_NETCAST
            else -> return null
        }
        val host = h["location"]?.let { runCatching { URI(it).host }.getOrNull() } ?: remetente
        val nome = when {
            marca == Marca.ROKU -> "Roku"
            servidor.isNotBlank() -> servidor.substringBefore(',').take(40)
            else -> marca.descricao
        }
        return TvEncontrada(marca, host, "$nome ($host)")
    }

    /**
     * Procura TVs por [prazoMs]. [destino] só muda nos testes (servidor falso local).
     * Bloqueante; devolve a lista sem repetição.
     */
    fun procurar(prazoMs: Long = 3_000, destino: InetSocketAddress = GRUPO): List<TvEncontrada> {
        val achadas = LinkedHashMap<Pair<Marca, String>, TvEncontrada>()
        DatagramSocket().use { s ->
            for (st in BUSCAS) {
                val dados = mensagem(st).toByteArray(Charsets.US_ASCII)
                repeat(2) { s.send(DatagramPacket(dados, dados.size, destino)) }
            }
            val fim = System.currentTimeMillis() + prazoMs
            val buf = ByteArray(2048)
            while (true) {
                val resta = fim - System.currentTimeMillis()
                if (resta <= 0) break
                s.soTimeout = resta.toInt().coerceAtLeast(1)
                val p = DatagramPacket(buf, buf.size)
                try {
                    s.receive(p)
                } catch (e: SocketTimeoutException) {
                    break
                }
                val texto = String(p.data, 0, p.length, Charsets.UTF_8)
                interpretar(texto, p.address.hostAddress)?.let { achadas.putIfAbsent(it.marca to it.host, it) }
            }
        }
        return achadas.values.toList()
    }
}
