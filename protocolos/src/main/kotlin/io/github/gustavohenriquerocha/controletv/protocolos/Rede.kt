package io.github.gustavohenriquerocha.controletv.protocolos

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

object Rede {
    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .writeTimeout(5, TimeUnit.SECONDS)
        .build()

    /**
     * Cliente para as portas seguras das TVs (webOS 3001, Samsung 8002), que usam
     * certificado autoassinado. Só é usado para falar com a TV escolhida na rede local.
     */
    val tvAutoassinada: OkHttpClient by lazy {
        val confia = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        val ssl = SSLContext.getInstance("TLS").apply { init(null, arrayOf<TrustManager>(confia), SecureRandom()) }
        http.newBuilder()
            .sslSocketFactory(ssl.socketFactory, confia)
            .hostnameVerifier { _, _ -> true }
            .build()
    }

    /** POST com corpo; devolve o código HTTP e o corpo da resposta. Falha de rede = código 0. */
    fun post(
        cliente: OkHttpClient,
        url: String,
        corpo: String,
        tipo: String = "text/xml; charset=utf-8",
        cabecalhos: Map<String, String> = emptyMap(),
    ): Pair<Int, String> {
        val req = Request.Builder().url(url).post(corpo.toRequestBody(tipo.toMediaType()))
        cabecalhos.forEach { (k, v) -> req.header(k, v) }
        return try {
            cliente.newCall(req.build()).execute().use { it.code to (it.body?.string() ?: "") }
        } catch (e: IOException) {
            0 to (e.message ?: "")
        }
    }

    fun get(cliente: OkHttpClient, url: String): Pair<Int, String> = try {
        cliente.newCall(Request.Builder().url(url).build()).execute().use { it.code to (it.body?.string() ?: "") }
    } catch (e: IOException) {
        0 to (e.message ?: "")
    }

    /** Escapa texto para dentro de XML. */
    fun xml(s: String): String = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}
