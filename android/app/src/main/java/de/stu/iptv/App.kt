package de.stu.iptv

import android.app.Application
import android.net.VpnService
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import coil.ImageLoader
import coil.ImageLoaderFactory
import okhttp3.Call

class App : Application(), ImageLoaderFactory {

    private val main = Handler(Looper.getMainLooper())
    /** Closes the WireGuard tunnel a few seconds after rustTV left the screen. */
    private val stopTunnel = Runnable { Thread { Wg.down(this) }.start() }

    override fun onCreate() {
        super.onCreate()
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            // rustTV visible again → reconnect if it was closed automatically
            override fun onStart(owner: LifecycleOwner) {
                main.removeCallbacks(stopTunnel)
                if (Wg.autoStop(this@App) && Wg.enabled(this@App) && !Wg.isUp(this@App) && VpnService.prepare(this@App) == null) {
                    Thread { runCatching { Wg.up(this@App) } }.start()
                }
            }
            // rustTV closed / in the background (not picture-in-picture) → end the tunnel, if the user wants that
            override fun onStop(owner: LifecycleOwner) {
                if (Wg.autoStop(this@App) && Wg.enabled(this@App)) main.postDelayed(stopTunnel, 5_000)
            }
        })
    }

    /** Lets the picture loader use the same (VPN/proxy-aware) connection as the rest of the app. */
    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .callFactory(Call.Factory { request -> Net.client(this).newCall(request) })
        .crossfade(true)
        .build()
}
