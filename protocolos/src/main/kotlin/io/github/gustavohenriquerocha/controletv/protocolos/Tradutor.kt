package io.github.gustavohenriquerocha.controletv.protocolos

/** Teclas comuns a todas as marcas. Cada tradutor converte para o código da sua TV. */
enum class Tecla {
    CIMA, BAIXO, ESQUERDA, DIREITA, OK,
    VOLTAR, HOME, MENU, SAIR,
    VOLUME_MAIS, VOLUME_MENOS, MUDO,
    CANAL_MAIS, CANAL_MENOS,
    PLAY, PAUSA,
    ENERGIA,
}

/** O que um tradutor sabe fazer além das teclas. A tela esconde o que a TV não tem. */
enum class Recurso { PONTEIRO, TEXTO, CANAIS }

/** Marcas/protocolos suportados. [testado] = conferido em aparelho real. */
enum class Marca(val descricao: String, val testado: Boolean) {
    LG_NETCAST("LG NetCast 2013 (UDAP)", true),
    LG_NETCAST_ROAP("LG NetCast 2012 (ROAP)", false),
    LG_WEBOS("LG webOS 2014+", false),
    SAMSUNG_TIZEN("Samsung Tizen 2016+", false),
    ROKU("Roku / Roku TV", false),
}

/** Resultado de [Tradutor.conectar]. */
sealed interface Pareamento {
    /** Conectado. [credencial] é o que deve ser guardado para a próxima vez (chave, client-key, token). */
    data class Pronto(val credencial: String?) : Pareamento

    /** A TV mostrou um código na tela: chame [Tradutor.conectar] de novo passando esse código. */
    data object PedirCodigo : Pareamento
}

class ErroTv(mensagem: String, causa: Throwable? = null) : Exception(mensagem, causa)

/**
 * Interface comum dos drivers ("tradutores"). Todas as chamadas são bloqueantes e
 * devem rodar fora da thread de interface. Erros de rede ou recusa da TV viram [ErroTv].
 */
interface Tradutor : AutoCloseable {
    val marca: Marca
    val recursos: Set<Recurso>

    /** Conecta e, se preciso, pareia. [credencial] é a guardada de uma conexão anterior (ou o código digitado). */
    fun conectar(credencial: String?): Pareamento

    fun tecla(tecla: Tecla)

    /** Move o ponteiro relativo à posição atual (pixels da TV, aproximados). */
    fun moverPonteiro(dx: Int, dy: Int): Unit = throw ErroTv("${marca.descricao} não tem ponteiro")

    fun clicar() = tecla(Tecla.OK)

    fun rolar(paraCima: Boolean): Unit = tecla(if (paraCima) Tecla.CIMA else Tecla.BAIXO)

    /** Escreve [valor] no campo de texto aberto na TV e confirma. */
    fun enviarTexto(valor: String): Unit = throw ErroTv("${marca.descricao} não aceita texto")

    override fun close() {}
}

object Tradutores {
    /** Cria o tradutor da [marca] para a TV em [host] (IP ou nome), nas portas padrão. */
    fun criar(marca: Marca, host: String): Tradutor = when (marca) {
        Marca.LG_NETCAST -> LgNetCastUdap(host)
        Marca.LG_NETCAST_ROAP -> LgNetCastRoap(host)
        Marca.LG_WEBOS -> LgWebOs(host)
        Marca.SAMSUNG_TIZEN -> SamsungTizen(host)
        Marca.ROKU -> Roku(host)
    }
}
