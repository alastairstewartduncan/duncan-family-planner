package uk.co.duncan.familyplanner

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import uk.co.duncan.familyplanner.data.Repository
import uk.co.duncan.familyplanner.notify.MorningAlarm

class FamilyPlannerApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Repository.init(this)
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_DAILY, getString(R.string.channel_daily), NotificationManager.IMPORTANCE_HIGH)
                .apply { description = getString(R.string.channel_daily_desc) },
        )
        MorningAlarm.reschedule(this)
    }

    companion object {
        const val CHANNEL_DAILY = "daily_plan"
    }
}
