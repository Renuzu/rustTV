package de.stu.iptv

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import okhttp3.Call

/** Lets the picture loader use the same (VPN/proxy-aware) connection as the rest of the app. */
class App : Application(), ImageLoaderFactory {
    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .callFactory(Call.Factory { request -> Net.client(this).newCall(request) })
        .crossfade(true)
        .build()
}
