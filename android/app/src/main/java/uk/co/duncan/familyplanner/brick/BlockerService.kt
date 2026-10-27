package uk.co.duncan.familyplanner.brick

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uk.co.duncan.familyplanner.data.Repository

/**
 * The app blocker. Android tells it whenever a new app comes to the front;
 * while the phone is bricked, anything not on the allowed list is covered by
 * the Brick screen. It also keeps the phone's copy of the family rules fresh
 * and raises alerts (emergency unlocks, someone switching their blocker off).
 */
class BlockerService : AccessibilityService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var loop: Job? = null
    private var foreground: String? = null
    private var allowed: Set<String> = emptySet()
    private var allowedBuiltAt = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        BrickEngine.init(this)
        running = true
        rebuildAllowed()
        loop = scope.launch {
            var tick = 0
            while (isActive) {
                val state = BrickEngine.refresh(this@BlockerService)
                if (state.locked) foreground?.let { if (shouldBlock(it)) block() }
                if (tick % SYNC_EVERY == 0) sync()
                tick++
                delay(TICK_MS)
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        // Our own Brick screen covering an app isn't "the app the person is using".
        if (pkg == packageName && event.className?.toString() == BlockActivity::class.java.name) return
        foreground = pkg
        if (BrickEngine.state.value.locked && shouldBlock(pkg)) block()
    }

    private fun shouldBlock(pkg: String): Boolean {
        if (System.currentTimeMillis() - allowedBuiltAt > 60_000) rebuildAllowed()
        return pkg !in allowed
    }

    private fun rebuildAllowed() {
        val never = BrickSetup.neverAllowed(packageManager)
        allowed = BrickSetup.alwaysAllowed(this) + (BrickEngine.allowedApps - never)
        allowedBuiltAt = System.currentTimeMillis()
    }

    private var lastBlock = 0L

    private fun block() {
        val now = System.currentTimeMillis()
        if (now - lastBlock < 400) return
        lastBlock = now
        startActivity(
            Intent(this, BlockActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NO_ANIMATION)
                .putExtra(BlockActivity.EXTRA_BLOCKED, foreground),
        )
    }

    /** Pulls the latest rules, sends anything queued and raises alerts for new events. */
    private suspend fun sync() = withContext(Dispatchers.IO) {
        if (Repository.personId == null) return@withContext
        val snap = Repository.loadBrick() ?: return@withContext
        BrickEngine.flush()
        BrickEngine.pushStatus(this@BlockerService)
        val me = Repository.personId
        val people = Repository.cachedPeople().associate { it.id to it.name }
        val lastSeen = BrickEngine.lastSeenEventId
        val newest = snap.events.maxOfOrNull { it.id } ?: lastSeen
        if (lastSeen >= 0) {
            snap.events.filter { it.id > lastSeen && it.personId != me }.sortedBy { it.id }.forEach { e ->
                val who = people[e.personId] ?: e.personId
                when (e.type) {
                    "emergency" -> BrickNotifications.alert(
                        this@BlockerService, e.id, "$who used the emergency tag",
                        "$who's phone is unlocked for ${snap.settings.emergencyMinutes} minutes. Check they're OK.",
                    )
                    "tamper" -> if (snap.isGuardian) BrickNotifications.alert(
                        this@BlockerService, e.id, "$who's app blocker was switched off",
                        "Brick can't lock $who's phone until it's switched back on.",
                    )
                }
            }
        }
        BrickEngine.lastSeenEventId = newest
        withContext(Dispatchers.Main) { rebuildAllowed() }
    }

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        running = false
        Log.i(TAG, "Blocker switched off")
        BrickEngine.pushStatus(this)
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        running = false
        loop?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "BlockerService"
        private const val TICK_MS = 3_000L
        private const val SYNC_EVERY = 10 // ticks: about every 30 seconds

        /** True while the blocker is connected in this process. */
        @Volatile var running = false
            private set
    }
}
