package io.github.imkamaran.appguard.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import io.github.imkamaran.appguard.R
import io.github.imkamaran.appguard.policy.AuditAction
import io.github.imkamaran.appguard.policy.AuditEvent
import io.github.imkamaran.appguard.policy.NotificationThrottle

object NotificationHelper {
    const val CHANNEL_ID = "access_blocked"
    private val throttle = NotificationThrottle()

    fun createChannel(context: Context) {
        val channel = NotificationChannel(CHANNEL_ID, "Access blocked", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Alerts when a policy blocks an app's privacy access"
        }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    fun notifyRestricted(context: Context, events: List<AuditEvent>) {
        val nm = context.getSystemService(NotificationManager::class.java)
        val now = System.currentTimeMillis()
        for (e in events) {
            if (e.action != AuditAction.RESTRICTED) continue
            if (!throttle.allow(e.packageName, e.category, now)) continue
            val id = (e.packageName + "|" + e.category.name).hashCode()
            val n = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("${e.category.label} access blocked")
                .setContentText(e.packageName)
                .setSubText(e.api)
                .setAutoCancel(true)
                .setGroup("access_blocked")
                .build()
            try {
                nm.notify(id, n)
            } catch (_: SecurityException) { }
        }
    }
}
