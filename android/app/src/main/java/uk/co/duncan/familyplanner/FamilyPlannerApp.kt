package uk.co.duncan.familyplanner

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager

class FamilyPlannerApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannels(
            listOf(
                NotificationChannel(CHANNEL_DAILY, getString(R.string.channel_daily), NotificationManager.IMPORTANCE_HIGH)
                    .apply { description = getString(R.string.channel_daily_desc) },
                NotificationChannel(CHANNEL_CHANGES, getString(R.string.channel_changes), NotificationManager.IMPORTANCE_DEFAULT)
                    .apply { description = getString(R.string.channel_changes_desc) },
            ),
        )
    }

    companion object {
        const val CHANNEL_DAILY = "daily_plan"
        const val CHANNEL_CHANGES = "plan_changes"
    }
}
