package uk.co.duncan.familyplanner.messaging

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import uk.co.duncan.familyplanner.FamilyPlannerApp
import uk.co.duncan.familyplanner.MainActivity
import uk.co.duncan.familyplanner.R
import uk.co.duncan.familyplanner.data.Repository
import uk.co.duncan.familyplanner.widget.WidgetUpdater

/**
 * Receives data messages from the Cloud Functions ("morning" and "changed"),
 * shows an expandable notification and refreshes the home screen widget.
 */
class PlannerMessagingService : FirebaseMessagingService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onNewToken(token: String) {
        scope.launch { Repository.registerPushToken() }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val data = message.data
        val title = data["title"] ?: message.notification?.title ?: getString(R.string.app_name)
        val body = data["body"] ?: message.notification?.body ?: ""
        val date = data["date"]
        val type = data["type"] ?: "morning"
        showNotification(type, title, body, date)
        WidgetUpdater.refreshAsync(this)
    }

    @SuppressLint("MissingPermission") // checked at the top of the function
    private fun showNotification(type: String, title: String, body: String, date: String?) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
            android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU
        ) return

        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_DATE, date)
        }
        val requestCode = (date ?: "").hashCode()
        val pending = PendingIntent.getActivity(this, requestCode, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val channel = if (type == "changed") FamilyPlannerApp.CHANNEL_CHANGES else FamilyPlannerApp.CHANNEL_DAILY
        val notification = NotificationCompat.Builder(this, channel)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(ContextCompat.getColor(this, R.color.brand))
            .setContentTitle(title)
            .setContentText(body.lineSequence().firstOrNull().orEmpty())
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()
        // One notification per day per type: a later edit replaces the earlier one.
        NotificationManagerCompat.from(this).notify("$type-$date".hashCode(), notification)
    }
}
