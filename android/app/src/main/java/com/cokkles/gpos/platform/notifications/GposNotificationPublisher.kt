package com.cokkles.gpos.platform.notifications

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.cokkles.gpos.ParityActivity
import com.cokkles.gpos.R

class GposNotificationPublisher(
    private val context: Context,
) {
    fun ensureChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java)
        listOf(
            NotificationChannel(
                GposNotificationChannels.GENERAL,
                "AEGIS alerts & receipts",
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply { description = "Meaningful AEGIS outcomes and alerts" },
            NotificationChannel(
                GposNotificationChannels.TASKS,
                "AEGIS task synchronization",
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply { description = "Delayed Google Tasks synchronization outcomes" },
            NotificationChannel(
                GposNotificationChannels.SYSTEM,
                "AEGIS system",
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply { description = "Authentication and backend attention states" },
        ).forEach(manager::createNotificationChannel)
    }

    fun canPostNotifications(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED

    fun publishGenericCriticalAlert(stableEventId: String) {
        publishOutcome(
            stableEventId = stableEventId,
            title = "AEGIS has a critical alert",
            body = "Open AEGIS to review the alert securely.",
            target = GposDeepLinkTarget.ALERTS,
            isError = true,
        )
    }

    fun publishOutcome(
        stableEventId: String,
        title: String,
        body: String,
        target: GposDeepLinkTarget,
        isError: Boolean,
        channelId: String = GposNotificationChannels.GENERAL,
    ) {
        if (!canPostNotifications()) return
        ensureChannels()
        val safeTitle = title.trim().take(90).ifBlank { "AEGIS" }
        val safeBody = body.trim().replace(Regex("\\s+"), " ").take(240).ifBlank { "Open AEGIS for details." }
        val intent = Intent(
            Intent.ACTION_VIEW,
            DeepLinkRouter.uriFor(target),
            context,
            ParityActivity::class.java,
        ).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            stableEventId.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_gpos_notification)
            .setContentTitle(safeTitle)
            .setContentText(safeBody)
            .setStyle(NotificationCompat.BigTextStyle().bigText(safeBody))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setCategory(if (isError) NotificationCompat.CATEGORY_ERROR else NotificationCompat.CATEGORY_STATUS)
            .setPriority(if (isError) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
            .build()

        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) != PackageManager.PERMISSION_GRANTED
        ) return

        try {
            NotificationManagerCompat.from(context).notify(stableEventId.hashCode(), notification)
        } catch (_: SecurityException) {
            // Permission can be revoked between the explicit check and dispatch. Fail closed.
        }
    }

    fun cancel(stableEventId: String) {
        NotificationManagerCompat.from(context).cancel(stableEventId.hashCode())
    }
}
