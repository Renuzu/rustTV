package de.stu.iptv

import android.content.Context
import okhttp3.Credentials
import okhttp3.OkHttpClient
import java.net.Authenticator
import java.net.InetSocketAddress
import java.net.PasswordAuthentication
import java.net.Proxy
import java.util.concurrent.TimeUnit

/**
 * One HTTP client for everything rustTV loads from the provider (lists, pictures, videos).
 * If a VPN/proxy is set in the settings, ONLY this app goes through it – and there is
 * no fallback to the normal connection when the proxy does not work.
 */
object Net {
    @Volatile private var cached: OkHttpClient? = null
    @Volatile private var key = ""

    fun client(ctx: Context): OkHttpClient {
        val s = Store(ctx.applicationContext)
        val k = listOf(s.proxyKind, s.proxyHost, s.proxyPort, s.proxyUser, s.proxyPass, s.userAgent).joinToString("\u0000")
        cached?.let { if (k == key) return it }
        synchronized(this) {
            cached?.let { if (k == key) return it }
            val c = build(s)
            cached = c; key = k
            return c
        }
    }

    private fun build(s: Store): OkHttpClient {
        val ua = s.userAgent
        val appCtx = s.ctx.applicationContext
        val b = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .addInterceptor { chain ->
                // WireGuard switched on but not connected: block instead of using the normal line
                if (Wg.enabled(appCtx) && !Wg.isUp(appCtx)) {
                    // tunnel is (re)connecting – wait up to 10 s instead of using the normal line
                    var waited = 0
                    while (waited < 10_000 && !Wg.isUp(appCtx)) { Thread.sleep(250); waited += 250 }
                    if (!Wg.isUp(appCtx)) throw java.io.IOException("WireGuard ist nicht verbunden")
                }
                chain.proceed(chain.request().newBuilder().header("User-Agent", ua).build())
            }
        val host = s.proxyHost.trim()
        when {
            s.proxyKind == "socks5" && host.isNotEmpty() -> {
                // unresolved = also the name lookups go through the proxy
                b.proxy(Proxy(Proxy.Type.SOCKS, InetSocketAddress.createUnresolved(host, s.proxyPort)))
                val (user, pass) = s.proxyUser to s.proxyPass
                Authenticator.setDefault(object : Authenticator() {
                    override fun getPasswordAuthentication(): PasswordAuthentication? =
                        if (user.isNotEmpty()) PasswordAuthentication(user, pass.toCharArray()) else null
                })
            }
            s.proxyKind == "http" && host.isNotEmpty() -> {
                b.proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress.createUnresolved(host, s.proxyPort)))
                if (s.proxyUser.isNotEmpty()) {
                    val cred = Credentials.basic(s.proxyUser, s.proxyPass)
                    b.proxyAuthenticator { _, resp ->
                        if (resp.request.header("Proxy-Authorization") != null) null
                        else resp.request.newBuilder().header("Proxy-Authorization", cred).build()
                    }
                }
            }
            else -> b.proxy(Proxy.NO_PROXY)
        }
        return b.build()
    }

    /** Public IP address the provider sees (through the proxy if one is set). */
    fun publicIp(ctx: Context): String {
        val req = okhttp3.Request.Builder().url("https://api.ipify.org?format=json").build()
        client(ctx).newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw Exception("HTTP ${r.code}")
            return org.json.JSONObject(r.body!!.string()).optString("ip", "?")
        }
    }
}
