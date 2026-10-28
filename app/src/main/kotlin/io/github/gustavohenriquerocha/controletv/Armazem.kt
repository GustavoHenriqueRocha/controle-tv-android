package io.github.gustavohenriquerocha.controletv

import android.content.Context
import io.github.gustavohenriquerocha.controletv.protocolos.Marca
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** TV cadastrada no aparelho. [credencial] é a chave/client-key/token devolvida no pareamento. */
data class TvSalva(
    val id: String,
    val nome: String,
    val marca: Marca,
    val host: String,
    val credencial: String? = null,
)

/** Lista de TVs guardada em SharedPreferences, como JSON. */
class Armazem(contexto: Context) {
    private val prefs = contexto.getSharedPreferences("tvs", Context.MODE_PRIVATE)

    fun carregar(): List<TvSalva> {
        val texto = prefs.getString("lista", null) ?: return emptyList()
        val lista = runCatching { Json.parseToJsonElement(texto) as JsonArray }.getOrNull() ?: return emptyList()
        return lista.mapNotNull { el ->
            runCatching {
                val o = el.jsonObject
                TvSalva(
                    id = o["id"]!!.jsonPrimitive.content,
                    nome = o["nome"]!!.jsonPrimitive.content,
                    marca = Marca.valueOf(o["marca"]!!.jsonPrimitive.content),
                    host = o["host"]!!.jsonPrimitive.content,
                    credencial = o["credencial"]?.jsonPrimitive?.contentOrNull,
                )
            }.getOrNull()
        }
    }

    fun salvar(tvs: List<TvSalva>) {
        val json = buildJsonArray {
            tvs.forEach { tv ->
                add(buildJsonObject {
                    put("id", tv.id)
                    put("nome", tv.nome)
                    put("marca", tv.marca.name)
                    put("host", tv.host)
                    put("credencial", tv.credencial)
                })
            }
        }
        prefs.edit().putString("lista", json.toString()).apply()
    }

    var ultima: String?
        get() = prefs.getString("ultima", null)
        set(v) = prefs.edit().putString("ultima", v).apply()
}
