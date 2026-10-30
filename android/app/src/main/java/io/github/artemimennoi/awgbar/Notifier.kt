package io.github.artemimennoi.awgbar

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.text.format.Formatter
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.compose.ui.graphics.toArgb

/** The optional ongoing notification: tunnel, server, traffic and a Disconnect action. */
object Notifier {
    private const val CHANNEL = "status"
    private const val ID = 1

    fun update(context: Context, state: VpnState, endpoint: String?) {
        val nm = NotificationManagerCompat.from(context)
        if (!nm.areNotificationsEnabled()) return
        val active = state.active ?: return cancel(context)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, context.getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW).apply {
                setShowBadge(false)
            },
        )
        val open = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE,
        )
        val disconnect = PendingIntent.getBroadcast(
            context, 0, Intent(context, DisconnectReceiver::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        fun size(n: Long) = Formatter.formatShortFileSize(context, n)
        val traffic = "↓ ${size(state.rxRate)}/s  ↑ ${size(state.txRate)}/s · ${size(state.rx + state.tx)}"
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_status)
            .setColor(ShieldGreen.toArgb())
            .setContentTitle(context.getString(R.string.connected, Vpn.store.title(active)))
            .setContentText(listOfNotNull(endpoint, traffic).joinToString(" · "))
            .setStyle(NotificationCompat.BigTextStyle().bigText(listOfNotNull(endpoint, traffic).joinToString("\n")))
            .setContentIntent(open)
            .addAction(0, context.getString(R.string.disconnect), disconnect)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
        runCatching { nm.notify(ID, n) } // SecurityException if POST_NOTIFICATIONS was revoked.
    }

    fun cancel(context: Context) = NotificationManagerCompat.from(context).cancel(ID)
}

class DisconnectReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Vpn.init(context)
        Vpn.disconnect()
    }
}
