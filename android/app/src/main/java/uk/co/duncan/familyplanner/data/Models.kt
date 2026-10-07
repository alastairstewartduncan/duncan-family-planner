package uk.co.duncan.familyplanner.data

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

// Firestore data model. Mapped by hand (rather than toObject) so missing or
// older fields never crash the app. Mirrors firebase/functions/src/types.ts.

private fun Map<*, *>.str(key: String) = (this[key] as? String) ?: ""
private fun Map<*, *>.strOrNull(key: String) = (this[key] as? String)?.takeIf { it.isNotBlank() }
private fun Map<*, *>.strList(key: String) = (this[key] as? List<*>)?.filterIsInstance<String>() ?: emptyList()
private fun Map<*, *>.intList(key: String) = (this[key] as? List<*>)?.filterIsInstance<Number>()?.map { it.toInt() } ?: emptyList()
private fun Map<*, *>.bool(key: String, default: Boolean = false) = (this[key] as? Boolean) ?: default

val DATE_ID: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE // yyyy-MM-dd
fun LocalDate.id(): String = format(DATE_ID)
fun LocalDate.pretty(): String = format(DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.UK))
fun LocalDate.short(): String = format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.UK))

data class Person(
    val id: String,
    val name: String,
    val colour: String = "#607D8B",
    val email: String = "",
) {
    fun toMap() = mapOf("id" to id, "name" to name, "colour" to colour, "email" to email.lowercase().trim())

    companion object {
        fun from(m: Map<*, *>) = Person(m.str("id"), m.str("name"), m.str("colour").ifBlank { "#607D8B" }, m.str("email"))
    }
}

data class Family(
    val id: String,
    val name: String = "Family",
    val timezone: String = "Europe/London",
    val reminderTime: String = "07:00",
    val reminderDays: List<Int> = (1..7).toList(),
    val calendarId: String = "",
    val notifyOnChange: Boolean = true,
    val people: List<Person> = emptyList(),
) {
    fun person(id: String?): Person? = people.firstOrNull { it.id == id }

    companion object {
        fun from(id: String, m: Map<String, Any?>) = Family(
            id = id,
            name = m.str("name").ifBlank { "Family" },
            timezone = m.str("timezone").ifBlank { "Europe/London" },
            reminderTime = m.str("reminderTime").ifBlank { "07:00" },
            reminderDays = m.intList("reminderDays").ifEmpty { (1..7).toList() },
            calendarId = m.str("calendarId"),
            notifyOnChange = m.bool("notifyOnChange", true),
            people = (m["people"] as? List<*>)?.filterIsInstance<Map<*, *>>()?.map(Person::from) ?: emptyList(),
        )
    }
}

data class PersonDay(val status: String = "", val extras: String = "", val travel: String = "") {
    fun toMap() = mapOf("status" to status, "extras" to extras, "travel" to travel)
    fun isEmpty() = status.isBlank() && extras.isBlank() && travel.isBlank()
    fun summary() = listOf(status, extras, travel).map { it.trim() }.filter { it.isNotEmpty() }.joinToString(" / ")

    companion object {
        fun from(m: Map<*, *>?) = if (m == null) PersonDay() else PersonDay(m.str("status"), m.str("extras"), m.str("travel"))
    }
}

data class PlanItem(
    val id: String = UUID.randomUUID().toString(),
    val title: String = "",
    val time: String? = null, // "HH:mm"
    val durationMins: Int = 60,
    val ownerPersonId: String? = null,
    val attendees: List<String> = emptyList(),
    val notes: String = "",
    val recurringId: String? = null,
    val claimedBy: String? = null,
    val done: Boolean = false,
) {
    /** The person actually responsible: whoever claimed it, else the owner. */
    val responsible: String? get() = claimedBy ?: ownerPersonId

    fun toMap() = mapOf(
        "id" to id, "title" to title, "time" to time, "durationMins" to durationMins,
        "ownerPersonId" to ownerPersonId, "attendees" to attendees, "notes" to notes,
        "recurringId" to recurringId, "claimedBy" to claimedBy, "done" to done,
    )

    companion object {
        fun from(m: Map<*, *>) = PlanItem(
            id = m.str("id").ifBlank { UUID.randomUUID().toString() },
            title = m.str("title"),
            time = m.strOrNull("time"),
            durationMins = (m["durationMins"] as? Number)?.toInt() ?: 60,
            ownerPersonId = m.strOrNull("ownerPersonId"),
            attendees = m.strList("attendees"),
            notes = m.str("notes"),
            recurringId = m.strOrNull("recurringId"),
            claimedBy = m.strOrNull("claimedBy"),
            done = m.bool("done"),
        )
    }
}

fun List<PlanItem>.sortedForDisplay(): List<PlanItem> =
    filter { it.time == null } + filter { it.time != null }.sortedBy { it.time }

data class Day(
    val date: String,
    val people: Map<String, PersonDay> = emptyMap(),
    val tea: String = "",
    val items: List<PlanItem> = emptyList(),
    val notes: String = "",
    val skippedRecurring: List<String> = emptyList(),
    val updatedBy: String = "system",
    val userEdited: Boolean = false,
) {
    fun toMap(): Map<String, Any?> = mapOf(
        "date" to date,
        "people" to people.mapValues { it.value.toMap() },
        "tea" to tea,
        "items" to items.sortedForDisplay().map { it.toMap() },
        "notes" to notes,
        "skippedRecurring" to skippedRecurring.distinct(),
        "updatedBy" to updatedBy,
        "userEdited" to userEdited,
    )

    /** Plain-text version in the same shape as the old WhatsApp messages. */
    fun asText(family: Family): String {
        val lines = mutableListOf(LocalDate.parse(date).pretty().uppercase(Locale.UK))
        family.people.forEach { p ->
            val s = people[p.id]?.summary().orEmpty()
            if (s.isNotEmpty()) lines += "${p.name} – $s"
        }
        val extra = mutableListOf<String>()
        if (tea.isNotBlank()) extra += "Tea – ${tea.trim()}"
        items.sortedForDisplay().forEach { item ->
            val who = family.person(item.responsible)?.name
            extra += listOfNotNull(item.time, item.title + (who?.let { " ($it)" } ?: "")).joinToString(" ")
        }
        if (notes.isNotBlank()) extra += notes.trim()
        if (extra.isNotEmpty()) lines += listOf("") + extra
        return lines.joinToString("\n")
    }

    companion object {
        fun from(m: Map<String, Any?>) = Day(
            date = m.str("date"),
            people = (m["people"] as? Map<*, *>)?.entries
                ?.associate { (k, v) -> k.toString() to PersonDay.from(v as? Map<*, *>) } ?: emptyMap(),
            tea = m.str("tea"),
            items = (m["items"] as? List<*>)?.filterIsInstance<Map<*, *>>()?.map(PlanItem::from)?.sortedForDisplay()
                ?: emptyList(),
            notes = m.str("notes"),
            skippedRecurring = m.strList("skippedRecurring"),
            updatedBy = m.str("updatedBy").ifBlank { "system" },
            userEdited = m.bool("userEdited"),
        )
    }
}

data class RecurringItem(
    val id: String = "",
    val title: String = "",
    val time: String? = null,
    val durationMins: Int = 60,
    val daysOfWeek: List<Int> = emptyList(), // ISO 1 = Mon .. 7 = Sun
    val ownerPersonId: String? = null,
    val attendees: List<String> = emptyList(),
    val notes: String = "",
    val active: Boolean = true,
) {
    fun toMap() = mapOf(
        "title" to title, "time" to time, "durationMins" to durationMins, "daysOfWeek" to daysOfWeek.sorted(),
        "ownerPersonId" to ownerPersonId, "attendees" to attendees, "notes" to notes, "active" to active,
    )

    companion object {
        fun from(id: String, m: Map<String, Any?>) = RecurringItem(
            id = id,
            title = m.str("title"),
            time = m.strOrNull("time"),
            durationMins = (m["durationMins"] as? Number)?.toInt() ?: 60,
            daysOfWeek = m.intList("daysOfWeek"),
            ownerPersonId = m.strOrNull("ownerPersonId"),
            attendees = m.strList("attendees"),
            notes = m.str("notes"),
            active = m.bool("active", true),
        )
    }
}

data class ShoppingItem(val id: String, val text: String, val done: Boolean, val addedBy: String?) {
    companion object {
        fun from(id: String, m: Map<String, Any?>) =
            ShoppingItem(id, m.str("text"), m.bool("done"), m.strOrNull("addedBy"))
    }
}

data class Membership(val familyId: String, val personId: String?)

val WEEKDAY_SHORT = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
