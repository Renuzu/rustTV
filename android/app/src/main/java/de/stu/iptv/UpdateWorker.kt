package de.stu.iptv

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.Constraints
import androidx.work.NetworkType
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Checks GitHub in the background (every 6 hours) and shows a notification for a new version. */
class UpdateWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    companion object {
        private const val CHANNEL = "updates"

        fun schedule(ctx: Context) {
            val req = PeriodicWorkRequestBuilder<UpdateWorker>(6, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork("update-check", ExistingPeriodicWorkPolicy.KEEP, req)
        }
    }

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val info = try {
            JSONObject(Updater.get(Updater.VERSION_URL).inputStream.bufferedReader().use { it.readText() })
        } catch (e: Exception) { return Result.retry() }
        val code = info.optLong("versionCode", 0)
        val p = ctx.getSharedPreferences("updater", Context.MODE_PRIVATE)
        if (code <= Updater.currentCode(ctx) || p.getLong("notified", 0) == code) return Result.success()

        if (Build.VERSION.SDK_INT >= 26) {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Updates", NotificationManager.IMPORTANCE_DEFAULT))
        }
        val open = PendingIntent.getActivity(ctx, 0,
            Intent(Intent.ACTION_VIEW, Uri.parse(info.optString("apk"))),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("rustTV ${info.optString("versionName")} ist verfügbar")
            .setContentText(info.optString("notes").ifBlank { "Antippen zum Herunterladen" })
            .setStyle(NotificationCompat.BigTextStyle().bigText(info.optString("notes") + "\nAntippen zum Herunterladen."))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(ctx).notify(42, n)
            p.edit().putLong("notified", code).apply()
        } catch (e: SecurityException) { /* Benachrichtigungen nicht erlaubt */ }
        return Result.success()
    }
}
