package de.stu.iptv

import android.content.Context
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.Config
import com.wireguard.config.Interface

/**
 * WireGuard tunnel that is used ONLY by rustTV (Android per-app VPN).
 * The config comes from a .conf file of the user's VPN provider; we force
 * "IncludedApplications = rustTV" so the rest of the phone is not affected.
 */
object Wg {
    private const val PREFS = "wireguard"
    @Volatile private var backend: GoBackend? = null
    @Volatile var state: Tunnel.State = Tunnel.State.DOWN
        private set

    private val tunnel = object : Tunnel {
        override fun getName() = "rustTV"
        override fun onStateChange(newState: Tunnel.State) { state = newState }
    }

    private fun backend(ctx: Context): GoBackend =
        backend ?: synchronized(this) { backend ?: GoBackend(ctx.applicationContext).also { backend = it } }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun configText(ctx: Context): String = prefs(ctx).getString("conf", "") ?: ""
    fun enabled(ctx: Context): Boolean = prefs(ctx).getBoolean("enabled", false) && configText(ctx).isNotBlank()
    fun setEnabled(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean("enabled", on).apply()

    /** Checks and stores a .conf file. Returns a short description (server) or throws. */
    fun importConfig(ctx: Context, text: String): String {
        val cfg = Config.parse(text.byteInputStream())
        prefs(ctx).edit().putString("conf", text).apply()
        return describe(cfg)
    }

    fun removeConfig(ctx: Context) {
        prefs(ctx).edit().remove("conf").putBoolean("enabled", false).apply()
    }

    fun describe(ctx: Context): String? =
        configText(ctx).takeIf { it.isNotBlank() }?.let { runCatching { describe(Config.parse(it.byteInputStream())) }.getOrNull() }

    private fun describe(cfg: Config): String =
        cfg.peers.firstOrNull()?.endpoint?.map { it.host + ":" + it.port }?.orElse(null) ?: "WireGuard"

    /** Same config, but only for this app. */
    private fun onlyThisApp(ctx: Context, cfg: Config): Config {
        val i = cfg.`interface`
        val b = Interface.Builder()
            .addAddresses(i.addresses)
            .addDnsServers(i.dnsServers)
            .addDnsSearchDomains(i.dnsSearchDomains)
            .setKeyPair(i.keyPair)
            .includeApplication(ctx.packageName)
        i.listenPort.ifPresent { b.setListenPort(it) }
        i.mtu.ifPresent { b.setMtu(it) }
        return Config.Builder().setInterface(b.build()).addPeers(cfg.peers).build()
    }

    /** Start the tunnel (blocking – call from a background thread). VPN permission must be granted. */
    fun up(ctx: Context) {
        val cfg = onlyThisApp(ctx, Config.parse(configText(ctx).byteInputStream()))
        state = backend(ctx).setState(tunnel, Tunnel.State.UP, cfg)
    }

    fun down(ctx: Context) {
        runCatching { state = backend(ctx).setState(tunnel, Tunnel.State.DOWN, null) }
    }

    fun isUp(ctx: Context): Boolean =
        runCatching { backend(ctx).getState(tunnel) == Tunnel.State.UP }.getOrDefault(state == Tunnel.State.UP)
}
