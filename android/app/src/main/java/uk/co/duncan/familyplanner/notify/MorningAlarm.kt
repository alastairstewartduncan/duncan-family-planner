package uk.co.duncan.familyplanner.notify

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import uk.co.duncan.familyplanner.FamilyPlannerApp
import uk.co.duncan.familyplanner.MainActivity
import uk.co.duncan.familyplanner.R
import uk.co.duncan.familyplanner.data.Family
import uk.co.duncan.familyplanner.data.Repository
import uk.co.duncan.familyplanner.data.id
import uk.co.duncan.familyplanner.data.pretty
import uk.co.duncan.familyplanner.widget.WidgetUpdater
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * The phone schedules its own morning notification — no push service needed.
 * At the family's reminder time it fetches today's plan from the home server
 * (falling back to the last copy it saw), shows it, and schedules the next one.
 */
object MorningAlarm {
    private const val TAG = "MorningAlarm"
    private const val REQUEST = 7001
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun pending(context: Context) = PendingIntent.getBroadcast(
        context, REQUEST, Intent(context, MorningReceiver::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** Next reminder after now, honouring the chosen days; null if no days are ticked. */
    fun nextTime(family: Family, now: ZonedDateTime = ZonedDateTime.now(zone(family))): ZonedDateTime? {
        val days = family.reminderDays.ifEmpty { return null }
        val time = runCatching { LocalTime.parse(family.reminderTime) }.getOrDefault(LocalTime.of(7, 0))
        for (i in 0..7) {
            val candidate = now.toLocalDate().plusDays(i.toLong()).atTime(time).atZone(now.zone)
            if (candidate.isAfter(now) && candidate.dayOfWeek.value in days) return candidate
        }
        return null
    }

    private fun zone(family: Family): ZoneId = runCatching { ZoneId.of(family.timezone) }.getOrDefault(ZoneId.systemDefault())

    /** (Re)schedules the alarm from the family settings (cached copy if offline). */
    fun schedule(context: Context, family: Family?) {
        val am = context.getSystemService(AlarmManager::class.java)
        val pi = pending(context)
        am.cancel(pi)
        val fam = family ?: return
        if (Repository.credentials.value == null) return
        val at = nextTime(fam) ?: return
        val millis = at.toInstant().toEpochMilli()
        val exact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()
        if (exact) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, pi)
        else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, pi)
        Log.i(TAG, "Morning plan scheduled for $at (exact=$exact)")
    }

    /** Loads the family (server or cache) and schedules. Safe to call any time. */
    fun reschedule(context: Context) {
        val app = context.applicationContext
        scope.launch { schedule(app, Repository.loadFamily()) }
    }

    /** Builds and shows today's plan. Used by the alarm and the "Send a test" button. */
    suspend fun showToday(context: Context) {
        val family = Repository.loadFamily() ?: return
        val today = ZonedDateTime.now(zone(family)).toLocalDate()
        val day = withTimeoutOrNull(20_000) { Repository.loadDay(today.id()) }
        val lines = day?.bodyLines(family).orEmpty()
        notify(context, today.pretty(), if (lines.isEmpty()) "Nothing planned yet." else lines.joinToString("\n"), today.id())
        WidgetUpdater.refresh(context)
    }

    @SuppressLint("MissingPermission") // checked below
    private fun notify(context: Context, title: String, body: String, date: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_DATE, date)
        }
        val open = PendingIntent.getActivity(context, date.hashCode(), intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(context, FamilyPlannerApp.CHANNEL_DAILY)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(ContextCompat.getColor(context, R.color.brand))
            .setContentTitle(title)
            .setContentText(body.lineSequence().firstOrNull().orEmpty())
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(REQUEST, n)
    }

    internal fun onAlarm(context: Context, done: () -> Unit) {
        val app = context.applicationContext
        scope.launch {
            try {
                showToday(app)
            } catch (e: Exception) {
                Log.w(TAG, "Couldn't show morning plan", e)
            } finally {
                schedule(app, Repository.loadFamily())
                done()
            }
        }
    }
}

class MorningReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Repository.init(context)
        val result = goAsync()
        MorningAlarm.onAlarm(context) { result.finish() }
    }
}

/** Re-creates the alarm after a reboot, an app update or a clock/time-zone change. */
class RescheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Repository.init(context)
        MorningAlarm.reschedule(context)
    }
}
