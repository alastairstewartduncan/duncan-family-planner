package uk.co.duncan.familyplanner.brick

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import uk.co.duncan.familyplanner.data.BrickSchedule
import uk.co.duncan.familyplanner.data.BrickSnapshot
import uk.co.duncan.familyplanner.data.Repository
import uk.co.duncan.familyplanner.data.TagKind
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/** Why the phone is locked (or not). */
enum class LockReason { None, Manual, Focus, Schedule }

data class LockState(
    val locked: Boolean,
    val reason: LockReason = LockReason.None,
    val until: Instant? = null, // when it unlocks on its own; null = when someone taps a brick
    val label: String = "", // schedule name, "Family Focus", …
    val canSelfUnlock: Boolean = true, // a brick tap on this phone unlocks it
    val since: Instant? = null,
    val overrideKey: String? = null, // identifies this lock window, for a parent's "unlock just me"
    val emergencyUntil: Instant? = null,
    val passUntil: Instant? = null,
) {
    companion object {
        val Open = LockState(false)
    }
}

/** What happened when the phone touched a tag, for the screen to show. */
sealed interface TapResult {
    data class Bricked(val tagName: String) : TapResult
    data class Unbricked(val tagName: String, val minutes: Long) : TapResult
    data class EmergencyUnlocked(val minutes: Int) : TapResult
    data class ParentChoice(val state: LockState, val tagName: String) : TapResult // focus/schedule + a parent tapped
    data class Denied(val message: String) : TapResult
    data class Unknown(val message: String) : TapResult
}

/** What happened when someone typed the recovery code on a locked phone. */
sealed interface RecoveryResult {
    data class Unlocked(val label: String) : RecoveryResult
    data class Wrong(val triesLeft: Int) : RecoveryResult
    data class Waiting(val seconds: Long) : RecoveryResult
    object NotSet : RecoveryResult
    object NotLocked : RecoveryResult
}

/**
 * The phone's own lock logic. Everything is decided on the phone from its last
 * copy of the family's rules, so locking and unlocking work without a signal.
 * Changes are queued and sent to the home server when it can be reached.
 */
object BrickEngine {
    private const val TAG = "BrickEngine"
    private lateinit var prefs: SharedPreferences
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()

    private val _state = MutableStateFlow(LockState.Open)
    val state: StateFlow<LockState> = _state

    fun init(context: Context) {
        if (::prefs.isInitialized) return
        Repository.init(context)
        prefs = context.applicationContext.getSharedPreferences("brick", Context.MODE_PRIVATE)
        _state.value = evaluate()
    }

    // ---- Local state ------------------------------------------------------------------

    private fun instant(key: String): Instant? = prefs.getString(key, null)?.let { runCatching { Instant.parse(it) }.getOrNull() }
    private fun SharedPreferences.Editor.putInstant(key: String, v: Instant?) = if (v == null) remove(key) else putString(key, v.toString())

    private val manualSince get() = instant("manualSince")
    private val emergencyUntil get() = instant("emergencyUntil")?.takeIf { it.isAfter(Instant.now()) }
    private val overrideKey get() = prefs.getString("overrideKey", null)

    /**
     * Packages kept open while bricked. A list a parent chose from the server PC wins;
     * otherwise it is whatever the person chose on this phone.
     */
    var allowedApps: Set<String>
        get() = snapshot().managedApps ?: (prefs.getStringSet("allowedApps", null) ?: emptySet())
        set(v) { prefs.edit().putStringSet("allowedApps", v).putBoolean("allowedChosen", true).apply() }

    /** True when a parent chooses this phone's essential apps, so the picker here is switched off. */
    val appsManaged: Boolean get() = snapshot().managedApps != null

    val allowedChosen: Boolean get() = appsManaged || prefs.getBoolean("allowedChosen", false)

    /** Tells the server which apps this phone has (at most once a day unless the list changes). */
    suspend fun reportInstalledApps(context: Context) {
        val apps = BrickSetup.launchableLabels(context)
        val fingerprint = apps.joinToString("|") { "${it.first}=${it.second}" }.hashCode().toString()
        val now = System.currentTimeMillis()
        if (prefs.getString("appsReported", null) == fingerprint && now - prefs.getLong("appsReportedAt", 0L) < 24 * 3_600_000L) return
        runCatching { Repository.reportBrickApps(apps.map { mapOf("pkg" to it.first, "label" to it.second) }) }
            .onSuccess { prefs.edit().putString("appsReported", fingerprint).putLong("appsReportedAt", now).apply() }
            .onFailure { Log.w(TAG, "Couldn't report installed apps: ${it.message}") }
    }

    /** Emergency tags this phone has used, so a spent tag stays spent even offline. */
    private val usedEmergencyTags: Set<String> get() = prefs.getStringSet("usedEmergency", null) ?: emptySet()

    var lastSeenEventId: Long
        get() = prefs.getLong("lastSeenEventId", -1L)
        set(v) { prefs.edit().putLong("lastSeenEventId", v).apply() }

    fun snapshot(): BrickSnapshot = Repository.cachedBrick() ?: BrickSnapshot()

    private fun zone(): ZoneId = runCatching {
        Repository.cachedFamilyTimezone()?.let(ZoneId::of)
    }.getOrNull() ?: ZoneId.systemDefault()

    // ---- Deciding ---------------------------------------------------------------------

    /** Works out whether this phone should be locked right now. */
    fun evaluate(
        now: Instant = Instant.now(),
        snap: BrickSnapshot = snapshot(),
        me: String? = Repository.personId,
        ignoreOverride: Boolean = false,
    ): LockState {
        if (me == null) return LockState.Open
        val overrideKey = if (ignoreOverride) null else this.overrideKey
        // Bricking yourself (Brick button or a tag) always wins: you chose it, and a tag undoes it.
        val brickedAt = manualSince
        if (brickedAt != null) {
            val max = snap.settings.maxBrickMinutes
            val endsAt = if (max > 0) brickedAt.plusSeconds(max * 60L) else null
            if (endsAt == null || endsAt.isAfter(now)) return LockState(true, LockReason.Manual, endsAt, "Bricked", true, brickedAt)
            // A parent's maximum brick length has passed, so this brick is over.
        }

        val emergency = emergencyUntil
        if (emergency != null) return LockState(false, emergencyUntil = emergency)
        val pass = snap.passes[me]?.until?.takeIf { it.isAfter(now) }
        if (pass != null) return LockState(false, passUntil = pass)

        val guardian = snap.isGuardian
        val focus = snap.focus
        if (focus != null && me in focus.personIds && (focus.until == null || focus.until.isAfter(now))) {
            val key = "focus:${focus.startedAt}"
            if (overrideKey != key) {
                return LockState(true, LockReason.Focus, focus.until, "Family Focus", guardian, parseStart(focus.startedAt), key)
            }
        }

        val zone = zone()
        for (s in snap.schedules) {
            if (!s.active || me !in s.personIds) continue
            val window = currentWindow(s, now.atZone(zone)) ?: continue
            val key = "schedule:${s.id}:${window.first.toLocalDate()}"
            if (overrideKey == key) continue
            return LockState(true, LockReason.Schedule, window.second.toInstant(), s.label, guardian, window.first.toInstant(), key)
        }
        return LockState.Open
    }

    private fun parseStart(s: String): Instant? = runCatching { Instant.parse(s) }.getOrNull()

    /** The start and end of the schedule's lock window that [now] falls in, if any. */
    fun currentWindow(s: BrickSchedule, now: ZonedDateTime): Pair<ZonedDateTime, ZonedDateTime>? {
        val start = runCatching { LocalTime.parse(s.start) }.getOrNull() ?: return null
        val end = runCatching { LocalTime.parse(s.end) }.getOrNull() ?: return null
        for (back in 0L..1L) {
            val day = now.toLocalDate().minusDays(back)
            if (day.dayOfWeek.value !in s.days) continue
            val from = day.atTime(start).atZone(now.zone)
            val to = (if (end.isAfter(start)) day else day.plusDays(1)).atTime(end).atZone(now.zone)
            if (!now.isBefore(from) && now.isBefore(to)) return from to to
        }
        return null
    }

    /** The next time a schedule will lock this phone, for the screen. */
    fun nextScheduled(snap: BrickSnapshot, me: String?, now: ZonedDateTime = ZonedDateTime.now(zone())): Pair<BrickSchedule, ZonedDateTime>? {
        me ?: return null
        return snap.schedules.filter { it.active && me in it.personIds }.mapNotNull { s ->
            val start = runCatching { LocalTime.parse(s.start) }.getOrNull() ?: return@mapNotNull null
            (0L..7L).map { now.toLocalDate().plusDays(it) }
                .filter { it.dayOfWeek.value in s.days }
                .map { it.atTime(start).atZone(now.zone) }
                .firstOrNull { it.isAfter(now) }?.let { s to it }
        }.minByOrNull { it.second }
    }

    /**
     * Re-checks the rules and reacts to any change: starts or ends a session,
     * tells the server, updates the notification. Called by the blocker every
     * few seconds and after every tap or sync.
     */
    fun refresh(context: Context): LockState {
        init(context)
        expireManualBrick()
        val next = evaluate()
        val before = _state.value
        _state.value = next
        val wasLocked = prefs.getBoolean("wasLocked", false)
        if (next.locked != wasLocked) {
            val now = Instant.now()
            if (next.locked) {
                prefs.edit().putBoolean("wasLocked", true).putInstant("sessionStart", next.since ?: now).apply()
            } else {
                val start = instant("sessionStart")
                prefs.edit().putBoolean("wasLocked", false).remove("sessionStart").apply()
                if (start != null) {
                    val mins = Duration.between(start, now).toMinutes()
                    if (mins >= 1) queueEvent("session", minutes = mins)
                }
                // An expired emergency or parent override is cleared once it's no longer needed.
                if (instant("emergencyUntil")?.isAfter(now) == false) prefs.edit().remove("emergencyUntil").apply()
            }
            BrickNotifications.showLockState(context, next)
            pushStatus(context)
            uk.co.duncan.familyplanner.widget.BrickWidgetUpdater.refreshAsync(context)
        } else if (before.reason != next.reason || before.until != next.until) {
            BrickNotifications.showLockState(context, next)
            pushStatus(context)
            uk.co.duncan.familyplanner.widget.BrickWidgetUpdater.refreshAsync(context)
        }
        // Forget a parent's override once that lock window has gone.
        val key = overrideKey
        if (key != null && evaluate(ignoreOverride = true).overrideKey != key) {
            prefs.edit().remove("overrideKey").apply()
        }
        return next
    }

    /** A brick someone started themselves ends by itself once a parent's maximum length has passed. */
    private fun expireManualBrick() {
        val since = manualSince ?: return
        val max = snapshot().settings.maxBrickMinutes
        if (max > 0 && !since.plusSeconds(max * 60L).isAfter(Instant.now())) {
            prefs.edit().remove("manualSince").apply()
            queueEvent("unbrick", detail = "Maximum brick length reached")
        }
    }

    // ---- Recovery code -----------------------------------------------------------------

    private const val RECOVERY_TRIES = 5

    /**
     * The family's recovery code unlocks this phone whatever is locking it (a brick, Family Focus or a
     * schedule), with no signal needed: the typed code is checked here against the salted hash the
     * home server sent earlier. Wrong guesses are limited and every use is reported to the family.
     */
    suspend fun useRecoveryCode(context: Context, typed: String): RecoveryResult = mutex.withLock {
        init(context)
        val now = System.currentTimeMillis()
        val waitUntil = prefs.getLong("recoveryWaitUntil", 0L)
        if (waitUntil > now) return RecoveryResult.Waiting((waitUntil - now + 999) / 1000)
        val recovery = snapshot().recovery ?: return RecoveryResult.NotSet
        val locked = evaluate()
        if (!locked.locked) return RecoveryResult.NotLocked

        val ok = withContext(Dispatchers.Default) { recoveryMatches(typed.trim(), recovery) }
        if (!ok) {
            val fails = prefs.getInt("recoveryFails", 0) + 1
            if (fails >= RECOVERY_TRIES) {
                val rounds = prefs.getInt("recoveryLockouts", 0)
                val minutes = minOf(5L shl rounds.coerceAtMost(5), 120L) // 5, 10, 20, 40, 80, then 120
                prefs.edit().putInt("recoveryFails", 0).putInt("recoveryLockouts", rounds + 1)
                    .putLong("recoveryWaitUntil", now + minutes * 60_000L).apply()
                queueEvent("denied", detail = "Wrong recovery code, locked out for $minutes min")
                return RecoveryResult.Waiting(minutes * 60)
            }
            prefs.edit().putInt("recoveryFails", fails).apply()
            queueEvent("denied", detail = "Wrong recovery code")
            return RecoveryResult.Wrong(RECOVERY_TRIES - fails)
        }

        prefs.edit().putInt("recoveryFails", 0).putInt("recoveryLockouts", 0).remove("recoveryWaitUntil").apply()
        // Undo a brick someone started, then any Family Focus or schedule that would still lock the phone.
        val label = locked.label
        if (locked.reason == LockReason.Manual) prefs.edit().remove("manualSince").apply()
        val still = evaluate()
        if (still.locked) prefs.edit().putString("overrideKey", still.overrideKey).apply()
        queueEvent("recovery", detail = label)
        refresh(context)
        RecoveryResult.Unlocked(label)
    }

    private fun recoveryMatches(typed: String, r: uk.co.duncan.familyplanner.data.BrickRecovery): Boolean {
        val salt = hexToBytes(r.salt) ?: return false
        val expected = hexToBytes(r.hash) ?: return false
        if (typed.length !in 8..32 || r.iterations !in 10_000..2_000_000) return false
        val spec = PBEKeySpec(typed.toCharArray(), salt, r.iterations, expected.size * 8)
        return try {
            val out = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
            MessageDigest.isEqual(out, expected)
        } catch (e: Exception) {
            Log.w(TAG, "Recovery check failed: ${e.message}")
            false
        } finally {
            spec.clearPassword()
        }
    }

    private fun hexToBytes(s: String): ByteArray? {
        if (s.isEmpty() || s.length % 2 != 0 || !s.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return null
        return ByteArray(s.length / 2) { i -> s.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
    }

    // ---- Tapping a tag -----------------------------------------------------------------

    suspend fun onTap(context: Context, scan: BrickTags.Scan): TapResult = mutex.withLock {
        init(context)
        if (Repository.personId == null) return TapResult.Unknown("Sign in to the planner first.")
        val payload = scan.payload ?: return TapResult.Unknown("That tag isn't a Brick. Register it under More → Brick first.")
        var snap = snapshot()
        var tag = snap.tags.firstOrNull { it.id == payload.tagId }
        if (tag == null) {
            // Maybe it was registered on another phone a moment ago.
            snap = Repository.loadBrick() ?: snap
            tag = snap.tags.firstOrNull { it.id == payload.tagId }
        }
        if (tag == null || tag.hash != BrickTags.hash(scan.uid, payload.secret)) {
            queueEvent("denied", detail = "Unrecognised tag")
            return TapResult.Unknown("That tag isn't one of the family's Bricks (or it's a copy).")
        }

        val now = Instant.now()
        if (tag.kind == TagKind.Emergency) {
            if (tag.spent || tag.id in usedEmergencyTags) {
                return TapResult.Denied("The emergency tag has already been used. A parent needs to re-arm it in the app.")
            }
            val mins = snap.settings.emergencyMinutes
            prefs.edit()
                .putInstant("emergencyUntil", now.plusSeconds(mins * 60L))
                .remove("manualSince")
                .putStringSet("usedEmergency", usedEmergencyTags + tag.id)
                .apply()
            queueEvent("emergency", tagId = tag.id, detail = tag.name)
            refresh(context)
            return TapResult.EmergencyUnlocked(mins)
        }

        val current = evaluate(now, snap)
        return when {
            // Unlocked → brick it. Also ends an emergency or parent override early.
            !current.locked -> {
                prefs.edit().putInstant("manualSince", now).remove("emergencyUntil").remove("overrideKey").apply()
                queueEvent("brick", tagId = tag.id)
                refresh(context)
                TapResult.Bricked(tag.name)
            }
            current.reason == LockReason.Manual -> {
                val mins = Duration.between(current.since ?: now, now).toMinutes()
                prefs.edit().remove("manualSince").apply()
                queueEvent("unbrick", tagId = tag.id)
                val after = refresh(context)
                if (after.locked) TapResult.Denied(lockedMessage(after, snap)) else TapResult.Unbricked(tag.name, mins)
            }
            current.canSelfUnlock -> TapResult.ParentChoice(current, tag.name)
            else -> {
                queueEvent("denied", tagId = tag.id, detail = current.label)
                TapResult.Denied(lockedMessage(current, snap))
            }
        }
    }

    /**
     * The Brick button (widget or app): bricks the phone straight away, no tag
     * needed. Scanning a Brick is the only way back. Ends an emergency unlock,
     * a pass is left alone (it unlocks anyway until it runs out).
     */
    fun brickNow(context: Context): Boolean {
        init(context)
        if (Repository.personId == null) return false
        if (evaluate().locked) return false
        prefs.edit().putInstant("manualSince", Instant.now()).remove("emergencyUntil").remove("overrideKey").apply()
        queueEvent("brick", detail = "Brick button")
        refresh(context)
        return true
    }

    /** A parent tapped during Family Focus or a schedule and chose to unlock only their own phone. */
    fun unlockJustMe(context: Context, state: LockState) {
        prefs.edit().putString("overrideKey", state.overrideKey).apply()
        queueEvent("unbrick", detail = "Parent unlocked own phone during ${state.label}")
        refresh(context)
    }

    /** Leaves a manual brick (used when signing out, so a phone can't get stuck). */
    fun clearLocal(context: Context) {
        init(context)
        prefs.edit().remove("manualSince").remove("emergencyUntil").remove("overrideKey").apply()
        refresh(context)
    }

    fun lockedMessage(state: LockState, snap: BrickSnapshot = snapshot()): String {
        val parents = snap.settings.guardians.mapNotNull { id -> Repository.cachedPeople().firstOrNull { it.id == id }?.name }
        val ask = if (parents.isEmpty()) "a parent" else parents.joinToString(" or ")
        val until = state.until?.let { " until ${formatTime(it)}" }.orEmpty()
        return when (state.reason) {
            LockReason.Focus -> "Family Focus is on$until. Ask $ask to end it or give you a pass."
            LockReason.Schedule -> "${state.label}$until. Ask $ask for a pass."
            LockReason.Manual -> "Tap your phone on a Brick to unlock." + (state.until?.let { " It also ends by itself at ${formatTime(it)}." } ?: "")
            LockReason.None -> ""
        }
    }

    fun formatTime(i: Instant): String {
        val z = i.atZone(zone())
        val today = ZonedDateTime.now(zone()).toLocalDate()
        val t = "%02d:%02d".format(z.hour, z.minute)
        return when (z.toLocalDate()) {
            today -> t
            today.plusDays(1) -> "$t tomorrow"
            else -> "$t ${z.dayOfWeek.getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.UK)}"
        }
    }

    // ---- Talking to the server ------------------------------------------------------------

    private fun queueEvent(type: String, tagId: String? = null, detail: String = "", minutes: Long = 0) {
        val list = runCatching { JSONArray(prefs.getString("outbox", "[]")) }.getOrDefault(JSONArray())
        list.put(JSONObject().apply {
            put("type", type); put("detail", detail); put("minutes", minutes); put("at", Instant.now().toString())
            if (tagId != null) put("tagId", tagId)
        })
        // Keep the queue bounded if the phone is offline for a long time.
        while (list.length() > 200) list.remove(0)
        prefs.edit().putString("outbox", list.toString()).apply()
        scope.launch { flush() }
    }

    /** Sends queued events, oldest first; stops at the first failure and tries again later. */
    suspend fun flush() {
        if (Repository.personId == null) return
        mutexOutbox.withLock {
            while (true) {
                val list = runCatching { JSONArray(prefs.getString("outbox", "[]")) }.getOrDefault(JSONArray())
                if (list.length() == 0) return
                val first = list.getJSONObject(0)
                val fields = first.keys().asSequence().associateWith { first.get(it) }
                try {
                    Repository.reportBrickEvent(fields)
                } catch (e: uk.co.duncan.familyplanner.data.ApiException) {
                    if (e.status in 500..599 || e.status == 401) return
                    Log.w(TAG, "Dropping event the server refused: ${e.message}")
                } catch (e: Exception) {
                    return
                }
                list.remove(0)
                prefs.edit().putString("outbox", list.toString()).apply()
            }
        }
    }

    private val mutexOutbox = Mutex()

    fun pushStatus(context: Context) {
        val app = context.applicationContext
        scope.launch {
            val s = _state.value
            runCatching {
                Repository.reportBrickStatus(
                    mapOf(
                        "locked" to s.locked,
                        "reason" to when (s.reason) {
                            LockReason.Manual -> "manual"; LockReason.Focus -> "focus"; LockReason.Schedule -> "schedule"; LockReason.None -> ""
                        },
                        "until" to s.until?.toString(),
                        "since" to s.since?.toString(),
                        "serviceOn" to BrickSetup.serviceEnabled(app),
                        "adminOn" to BrickSetup.adminActive(app),
                        "nfcOn" to (BrickTags.nfcState(app) == BrickTags.NfcState.On),
                        "appVersion" to BrickSetup.appVersion(app),
                    ),
                )
            }
        }
    }
}
