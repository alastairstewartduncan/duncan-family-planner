package uk.co.duncan.familyplanner.brick

import android.Manifest
import android.annotation.SuppressLint
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
import uk.co.duncan.familyplanner.MainActivity
import uk.co.duncan.familyplanner.R

object BrickNotifications {
    const val CHANNEL_STATE = "brick_state"
    const val CHANNEL_ALERTS = "brick_alerts"
    private const val ID_STATE = 7101
    private const val ID_ALERT_BASE = 7200

    fun createChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_STATE, "Brick status", NotificationManager.IMPORTANCE_LOW)
                .apply { description = "Shows while your phone is bricked"; setShowBadge(false) },
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ALERTS, "Brick alerts", NotificationManager.IMPORTANCE_HIGH)
                .apply { description = "Emergency unlocks and the blocker being switched off" },
        )
    }

    private fun allowed(context: Context) = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun openBrick(context: Context, request: Int) = PendingIntent.getActivity(
        context, request,
        Intent(context, MainActivity::class.java)
            .setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(MainActivity.EXTRA_OPEN, "brick"),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    @SuppressLint("MissingPermission") // checked in allowed()
    fun showLockState(context: Context, state: LockState) {
        val nm = NotificationManagerCompat.from(context)
        if (!state.locked) {
            nm.cancel(ID_STATE)
            return
        }
        if (!allowed(context)) return
        val title = when (state.reason) {
            LockReason.Focus -> "Family Focus"
            LockReason.Schedule -> state.label
            else -> "Phone bricked"
        }
        val text = state.until?.let { "Until ${BrickEngine.formatTime(it)}" } ?: "Tap a Brick to unlock"
        val n = NotificationCompat.Builder(context, CHANNEL_STATE)
            .setSmallIcon(R.drawable.ic_brick)
            .setColor(ContextCompat.getColor(context, R.color.brand))
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setSilent(true)
            .setUsesChronometer(state.since != null)
            .setWhen(state.since?.toEpochMilli() ?: System.currentTimeMillis())
            .setContentIntent(
                PendingIntent.getActivity(
                    context, ID_STATE, Intent(context, BlockActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
            .build()
        nm.notify(ID_STATE, n)
    }

    @SuppressLint("MissingPermission")
    fun alert(context: Context, id: Long, title: String, body: String) {
        if (!allowed(context)) return
        val n = NotificationCompat.Builder(context, CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_brick)
            .setColor(ContextCompat.getColor(context, R.color.brand))
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(openBrick(context, ID_ALERT_BASE + (id % 100).toInt()))
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(ID_ALERT_BASE + (id % 100).toInt(), n)
    }
}
