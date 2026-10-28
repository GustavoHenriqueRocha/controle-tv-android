package io.github.gustavohenriquerocha.controletv

import android.app.Application
import android.content.Context
import android.net.wifi.WifiManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.gustavohenriquerocha.controletv.protocolos.ErroTv
import io.github.gustavohenriquerocha.controletv.protocolos.Marca
import io.github.gustavohenriquerocha.controletv.protocolos.Pareamento
import io.github.gustavohenriquerocha.controletv.protocolos.Recurso
import io.github.gustavohenriquerocha.controletv.protocolos.Ssdp
import io.github.gustavohenriquerocha.controletv.protocolos.Tecla
import io.github.gustavohenriquerocha.controletv.protocolos.TvEncontrada
import io.github.gustavohenriquerocha.controletv.protocolos.Tradutor
import io.github.gustavohenriquerocha.controletv.protocolos.Tradutores
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import java.util.concurrent.Executors
import kotlin.math.roundToInt

class ControleViewModel(app: Application) : AndroidViewModel(app) {
    private val armazem = Armazem(app)

    /** Uma thread só para falar com a TV: os comandos chegam na ordem em que foram apertados. */
    private val fila = Executors.newSingleThreadExecutor().asCoroutineDispatcher()

    var tvs by mutableStateOf(armazem.carregar())
        private set
    var atual by mutableStateOf<TvSalva?>(null)
        private set
    var conectado by mutableStateOf(false)
        private set
    var recursos by mutableStateOf<Set<Recurso>>(emptySet())
        private set
    var status by mutableStateOf("")
        private set
    var pedindoCodigo by mutableStateOf(false)
        private set
    var procurando by mutableStateOf(false)
        private set
    var encontradas by mutableStateOf<List<TvEncontrada>>(emptyList())
        private set

    private var tradutor: Tradutor? = null

    // Movimento do ponteiro acumulado e enviado a cada 50 ms, como o Magic Remote.
    private var acumX = 0f
    private var acumY = 0f
    private val travaPonteiro = Any()

    init {
        (tvs.firstOrNull { it.id == armazem.ultima } ?: tvs.firstOrNull())?.let { escolher(it) }
        viewModelScope.launch(fila) {
            while (isActive) {
                delay(50)
                val (x, y) = synchronized(travaPonteiro) {
                    val x = acumX.roundToInt()
                    val y = acumY.roundToInt()
                    acumX -= x
                    acumY -= y
                    x to y
                }
                if ((x != 0 || y != 0) && conectado) rodar { it.moverPonteiro(x, y) }
            }
        }
    }

    fun escolher(tv: TvSalva) {
        armazem.ultima = tv.id
        atual = tv
        conectar(tv.credencial)
    }

    private fun conectar(credencial: String?) {
        val tv = atual ?: return
        conectado = false
        status = "Conectando a ${tv.nome}…"
        viewModelScope.launch(fila) {
            tradutor?.close()
            val t = Tradutores.criar(tv.marca, tv.host)
            tradutor = t
            try {
                when (val r = t.conectar(credencial)) {
                    is Pareamento.PedirCodigo -> {
                        pedindoCodigo = true
                        status = "Digite o código que apareceu na TV"
                    }
                    is Pareamento.Pronto -> {
                        if (r.credencial != tv.credencial) guardar(tv.copy(credencial = r.credencial))
                        pedindoCodigo = false
                        conectado = true
                        recursos = t.recursos
                        status = ""
                    }
                }
            } catch (e: ErroTv) {
                status = e.message ?: "Erro"
            } catch (e: Exception) {
                status = "Erro: ${e.message}"
            }
        }
    }

    fun informarCodigo(codigo: String) {
        pedindoCodigo = false
        conectar(codigo)
    }

    fun cancelarCodigo() {
        pedindoCodigo = false
    }

    fun reconectar() = atual?.let { conectar(it.credencial) }

    /** Roda um comando na fila. Se falhar, reconecta uma vez com a credencial guardada e repete. */
    private fun rodar(acao: (Tradutor) -> Unit) {
        viewModelScope.launch(fila) {
            val t = tradutor ?: return@launch
            try {
                acao(t)
                if (status.isNotEmpty()) status = ""
            } catch (e: ErroTv) {
                val tv = atual
                val ok = tv != null && runCatching {
                    t.conectar(tv.credencial) is Pareamento.Pronto && run { acao(t); true }
                }.getOrDefault(false)
                if (!ok) status = e.message ?: "Erro"
            } catch (e: Exception) {
                status = "Erro: ${e.message}"
            }
        }
    }

    fun tecla(tecla: Tecla) = rodar { it.tecla(tecla) }
    fun clicar() = rodar { it.clicar() }
    fun rolar(paraCima: Boolean) = rodar { it.rolar(paraCima) }
    fun enviarTexto(texto: String) = rodar { it.enviarTexto(texto) }

    fun mover(dx: Float, dy: Float) = synchronized(travaPonteiro) {
        acumX += dx
        acumY += dy
    }

    fun adicionar(nome: String, marca: Marca, host: String) {
        val tv = TvSalva(UUID.randomUUID().toString(), nome.ifBlank { "${marca.descricao} ($host)" }, marca, host.trim())
        tvs = tvs + tv
        armazem.salvar(tvs)
        escolher(tv)
    }

    fun remover(tv: TvSalva) {
        tvs = tvs.filterNot { it.id == tv.id }
        armazem.salvar(tvs)
        if (atual?.id == tv.id) {
            viewModelScope.launch(fila) { tradutor?.close(); tradutor = null }
            atual = null
            conectado = false
            status = ""
        }
    }

    private fun guardar(tv: TvSalva) {
        tvs = tvs.map { if (it.id == tv.id) tv else it }
        armazem.salvar(tvs)
        if (atual?.id == tv.id) atual = tv
    }

    fun procurar() {
        if (procurando) return
        procurando = true
        encontradas = emptyList()
        viewModelScope.launch {
            val wifi = getApplication<Application>().applicationContext
                .getSystemService(Context.WIFI_SERVICE) as WifiManager
            val trava = wifi.createMulticastLock("controle-tv-ssdp").apply { setReferenceCounted(false) }
            try {
                trava.acquire()
                encontradas = withContext(Dispatchers.IO) { Ssdp.procurar() }
                    .filterNot { e -> tvs.any { it.host == e.host && it.marca == e.marca } }
                if (encontradas.isEmpty()) status = "Nenhuma TV nova encontrada. Adicione pelo IP."
            } catch (e: Exception) {
                status = "Busca falhou: ${e.message}"
            } finally {
                trava.release()
                procurando = false
            }
        }
    }

    fun limparEncontradas() {
        encontradas = emptyList()
    }

    override fun onCleared() {
        val t = tradutor
        tradutor = null
        Thread { t?.close() }.start()
        fila.close()
    }
}
