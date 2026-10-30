package uk.co.duncan.familyplanner.data

import java.time.Instant

// Brick (phone-free time) data from GET /api/brick. Mirrors server/src/brick.ts.
// Parsed by hand like the rest of the app, so older servers never crash it.

private fun Map<*, *>.s(key: String) = (this[key] as? String) ?: ""
private fun Map<*, *>.sOrNull(key: String) = (this[key] as? String)?.takeIf { it.isNotBlank() }
private fun Map<*, *>.b(key: String, default: Boolean = false) = (this[key] as? Boolean) ?: default
private fun Map<*, *>.n(key: String, default: Int = 0) = (this[key] as? Number)?.toInt() ?: default
private fun Map<*, *>.strings(key: String) = (this[key] as? List<*>)?.filterIsInstance<String>() ?: emptyList()
private fun Map<*, *>.ints(key: String) = (this[key] as? List<*>)?.filterIsInstance<Number>()?.map { it.toInt() } ?: emptyList()
private fun Map<*, *>.maps(key: String) = (this[key] as? List<*>)?.filterIsInstance<Map<*, *>>() ?: emptyList()

fun parseInstant(s: String?): Instant? = s?.let { runCatching { Instant.parse(it) }.getOrNull() }

enum class TagKind { Brick, Emergency }

data class BrickTagInfo(
    val id: String,
    val kind: TagKind,
    val name: String,
    val hash: String,
    val createdBy: String,
    val lastUsedAt: Instant?,
    val lastUsedBy: String?,
    val spent: Boolean,
) {
    companion object {
        fun from(m: Map<*, *>) = BrickTagInfo(
            id = m.s("id"),
            kind = if (m.s("kind") == "emergency") TagKind.Emergency else TagKind.Brick,
            name = m.s("name").ifBlank { "Brick" },
            hash = m.s("hash").lowercase(),
            createdBy = m.s("createdBy"),
            lastUsedAt = parseInstant(m.sOrNull("lastUsedAt")),
            lastUsedBy = m.sOrNull("lastUsedBy"),
            spent = m.b("spent"),
        )
    }
}

data class BrickSettings(
    val guardians: List<String> = emptyList(),
    val emergencyMinutes: Int = 60,
    val rearmAfterEmergency: Boolean = true,
    /** A brick someone started themselves ends by itself after this many minutes (0 = no limit). */
    val maxBrickMinutes: Int = 0,
) {
    companion object {
        fun from(m: Map<*, *>?) = if (m == null) BrickSettings() else BrickSettings(
            guardians = m.strings("guardians"),
            emergencyMinutes = m.n("emergencyMinutes", 60),
            rearmAfterEmergency = m.b("rearmAfterEmergency", true),
            maxBrickMinutes = m.n("maxBrickMinutes", 0),
        )
    }
}

/** Salted hash of the family's recovery code, so a phone can check a typed code with no signal. */
data class BrickRecovery(val salt: String, val iterations: Int, val hash: String)

data class BrickSchedule(
    val id: String = "",
    val label: String = "Phone-free time",
    val personIds: List<String> = emptyList(),
    val days: List<Int> = listOf(1, 2, 3, 4, 7), // ISO weekday the lock starts on
    val start: String = "21:30",
    val end: String = "07:00",
    val active: Boolean = true,
) {
    fun toMap() = mapOf(
        "id" to id.ifBlank { null }, "label" to label, "personIds" to personIds, "days" to days.sorted(),
        "start" to start, "end" to end, "active" to active,
    )

    companion object {
        fun from(m: Map<*, *>) = BrickSchedule(
            id = m.s("id"), label = m.s("label").ifBlank { "Phone-free time" }, personIds = m.strings("personIds"),
            days = m.ints("days"), start = m.s("start"), end = m.s("end"), active = m.b("active", true),
        )
    }
}

data class FamilyFocus(val personIds: List<String>, val startedBy: String, val startedAt: String, val until: Instant?) {
    companion object {
        fun from(m: Map<*, *>?) = m?.takeIf { it.b("active") }?.let {
            FamilyFocus(it.strings("personIds"), it.s("startedBy"), it.s("startedAt"), parseInstant(it.sOrNull("until")))
        }
    }
}

data class BrickPass(val until: Instant, val grantedBy: String)

data class PhoneStatus(
    val locked: Boolean,
    val reason: String,
    val until: Instant?,
    val since: Instant?,
    val serviceOn: Boolean,
    val adminOn: Boolean,
    val nfcOn: Boolean,
    val updatedAt: Instant?,
) {
    companion object {
        fun from(m: Map<*, *>) = PhoneStatus(
            locked = m.b("locked"), reason = m.s("reason"), until = parseInstant(m.sOrNull("until")),
            since = parseInstant(m.sOrNull("since")), serviceOn = m.b("serviceOn"), adminOn = m.b("adminOn"),
            nfcOn = m.b("nfcOn", true), updatedAt = parseInstant(m.sOrNull("updatedAt")),
        )
    }
}

data class BrickEvent(val id: Long, val at: Instant?, val personId: String, val type: String, val detail: String) {
    companion object {
        fun from(m: Map<*, *>) = BrickEvent(
            (m["id"] as? Number)?.toLong() ?: 0L, parseInstant(m.sOrNull("at")), m.s("personId"), m.s("type"), m.s("detail"),
        )
    }
}

data class BrickStats(val today: Int, val week: Int, val streakDays: Int, val byDay: List<Pair<String, Int>>) {
    companion object {
        fun from(m: Map<*, *>) = BrickStats(
            today = m.n("today"), week = m.n("week"), streakDays = m.n("streakDays"),
            byDay = (m["byDay"] as? Map<*, *>)?.entries?.map { (k, v) -> k.toString() to ((v as? Number)?.toInt() ?: 0) }
                ?.sortedBy { it.first } ?: emptyList(),
        )
    }
}

/** A finished stretch of phone-free time: when it ended and how long it lasted. */
data class BrickSession(val end: Instant, val minutes: Int) {
    val start: Instant get() = end.minusSeconds(minutes * 60L)
}

data class BrickSnapshot(
    val settings: BrickSettings = BrickSettings(),
    val isGuardian: Boolean = false,
    val hasParentPin: Boolean = false,
    val recovery: BrickRecovery? = null,
    /** Essential apps a parent chose from the server PC for this person, or null when the person chooses on their own phone. */
    val managedApps: Set<String>? = null,
    val tags: List<BrickTagInfo> = emptyList(),
    val schedules: List<BrickSchedule> = emptyList(),
    val focus: FamilyFocus? = null,
    val passes: Map<String, BrickPass> = emptyMap(),
    val statuses: Map<String, PhoneStatus> = emptyMap(),
    val events: List<BrickEvent> = emptyList(),
    val stats: Map<String, BrickStats> = emptyMap(),
    val mySessions: List<BrickSession> = emptyList(),
) {
    val emergencyTag: BrickTagInfo? get() = tags.firstOrNull { it.kind == TagKind.Emergency }
    val bricks: List<BrickTagInfo> get() = tags.filter { it.kind == TagKind.Brick }

    companion object {
        fun from(m: Map<*, *>) = BrickSnapshot(
            settings = BrickSettings.from(m["settings"] as? Map<*, *>),
            isGuardian = m.b("isGuardian"),
            hasParentPin = m.b("hasParentPin"),
            recovery = (m["recovery"] as? Map<*, *>)?.let { r ->
                val salt = r.sOrNull("salt")
                val hash = r.sOrNull("hash")
                if (salt != null && hash != null) BrickRecovery(salt, r.n("iterations", 200_000), hash.lowercase()) else null
            },
            managedApps = (m["managedApps"] as? List<*>)?.map { it.toString() }?.toSet(),
            tags =m.maps("tags").map(BrickTagInfo::from),
            schedules = m.maps("schedules").map(BrickSchedule::from),
            focus = FamilyFocus.from(m["focus"] as? Map<*, *>),
            passes = (m["passes"] as? Map<*, *>)?.entries?.mapNotNull { (k, v) ->
                val p = v as? Map<*, *> ?: return@mapNotNull null
                val until = parseInstant(p.sOrNull("until")) ?: return@mapNotNull null
                k.toString() to BrickPass(until, p.s("grantedBy"))
            }?.toMap() ?: emptyMap(),
            statuses = (m["statuses"] as? Map<*, *>)?.entries?.mapNotNull { (k, v) ->
                (v as? Map<*, *>)?.let { k.toString() to PhoneStatus.from(it) }
            }?.toMap() ?: emptyMap(),
            events = m.maps("events").map(BrickEvent::from),
            stats = (m["stats"] as? Map<*, *>)?.entries?.mapNotNull { (k, v) ->
                (v as? Map<*, *>)?.let { k.toString() to BrickStats.from(it) }
            }?.toMap() ?: emptyMap(),
            mySessions = m.maps("mySessions").mapNotNull { x ->
                parseInstant(x.sOrNull("at"))?.let { BrickSession(it, x.n("minutes")) }
            },
        )
    }
}
