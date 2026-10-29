package io.github.gustavohenriquerocha.controletv.protocolos

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/** Liga a TV pela rede: "pacote mágico" com o MAC dela (6 x 0xFF + 16 x o MAC) em UDP broadcast. */
object WakeOnLan {
    private val formato = Regex("^([0-9A-Fa-f]{2}[:-]){5}[0-9A-Fa-f]{2}$")

    fun valido(mac: String) = formato.matches(mac.trim())

    fun pacote(mac: String): ByteArray {
        if (!valido(mac)) throw ErroTv("MAC inválido: $mac")
        val bytes = mac.trim().split(':', '-').map { it.toInt(16).toByte() }
        return ByteArray(6) { 0xFF.toByte() } + List(16) { bytes }.flatten()
    }

    /** Destinos: broadcast geral, broadcast da /24 da TV e a própria TV (alguns roteadores barram broadcast). */
    internal fun destinos(host: String): List<String> {
        val partes = host.split('.')
        val rede = if (partes.size == 4 && partes.all { it.toIntOrNull() != null }) {
            listOf(partes.take(3).joinToString(".") + ".255", host)
        } else listOf(host)
        return listOf("255.255.255.255") + rede
    }

    fun acordar(mac: String, host: String, porta: Int = 9) {
        val dados = pacote(mac)
        DatagramSocket().use { s ->
            s.broadcast = true
            for (destino in destinos(host)) {
                runCatching {
                    val endereco = InetAddress.getByName(destino)
                    repeat(3) { s.send(DatagramPacket(dados, dados.size, endereco, porta)) }
                }
            }
        }
    }
}
