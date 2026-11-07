package de.stu.iptv

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast

object Play {
    /** Liste, durch die man im Player umschalten kann (Sender oder Folgen). */
    var queue: List<Entry> = emptyList()
    var queueKind: Kind = Kind.LIVE

    fun url(ctx: Context, kind: Kind, e: Entry): String {
        val s = Store(ctx)
        return Xtream.from(s).streamUrl(kind, e, s.liveFormat)
    }

    /** Startet eine Liste ab Position [index] – je nach Einstellung intern oder extern. */
    fun start(ctx: Context, kind: Kind, list: List<Entry>, index: Int, forceInternal: Boolean = false) {
        val e = list.getOrNull(index) ?: return
        val store = Store(ctx)
        if (store.external && !forceInternal) {
            store.addHistory(kind, e.id)
            if (kind == Kind.LIVE) store.lastLive = e.id
            external(ctx, url(ctx, kind, e), e.name)
            return
        }
        queue = list
        queueKind = kind
        ctx.startActivity(Intent(ctx, PlayerActivity::class.java).putExtra("index", index))
    }

    /** Einzelne Adresse abspielen (z. B. Catch-up-Sendung). */
    fun single(ctx: Context, url: String, title: String) {
        ctx.startActivity(Intent(ctx, PlayerActivity::class.java)
            .putExtra("url", url).putExtra("title", title))
    }

    fun external(ctx: Context, url: String, title: String) {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(Uri.parse(url), "video/*")
            putExtra("title", title)
        }
        try {
            ctx.startActivity(Intent.createChooser(intent, "Abspielen mit …"))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(ctx, "Kein externer Player installiert (z. B. VLC).", Toast.LENGTH_LONG).show()
        }
    }
}
