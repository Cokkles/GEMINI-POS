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
import com.cokkles.gpos.GposActivity
import com.cokkles.gpos.R

class GposNotificationPublisher(
    private val context: Context,
) {
    fun ensureChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                GposNotificationChannels.GENERAL,
                "GPOS alerts",
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = "Privacy-safe GPOS alert notifications"
            },
        )
    }

    fun canPostNotifications(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED

    fun publishGenericCriticalAlert(stableEventId: String) {
        if (!canPostNotifications()) return
        ensureChannels()

        val target = GposDeepLinkTarget.NOTIFICATIONS
        val intent = Intent(
            Intent.ACTION_VIEW,
            DeepLinkRouter.uriFor(target),
            context,
            GposActivity::class.java,
        ).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            stableEventId.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(context, GposNotificationChannels.GENERAL)
            .setSmallIcon(R.drawable.ic_gpos_notification)
            .setContentTitle("GPOS has a critical alert")
            .setContentText("Open GPOS to review the alert securely.")
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText("Open GPOS to review the alert securely. Private server content is hidden from the lock-screen notification."),
            )
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        NotificationManagerCompat.from(context).notify(stableEventId.hashCode(), notification)
    }
}
