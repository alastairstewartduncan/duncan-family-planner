package uk.co.duncan.familyplanner.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withTimeoutOrNull
import java.time.LocalDate

/**
 * Single access point for the home server. Screens observe "live" flows: each
 * emits the cached copy straight away, then fresh data from the server, and
 * re-fetches whenever the server's revision changes (checked every 15 s while
 * the screen is visible) or this phone saves something.
 */
object Repository {
    private const val TAG = "Repository"
    private const val POLL_MS = 15_000L
    private lateinit var api: Api

    /** Bumped after every local write so open screens refresh immediately. */
    private val localChanges = MutableStateFlow(0L)

    fun init(context: Context) {
        if (!::api.isInitialized) api = Api(context.applicationContext)
    }

    val credentials: StateFlow<Credentials?> get() = api.credentials
    val lastServerUrl: String get() = api.lastServerUrl

    private fun changed() { localChanges.value = localChanges.value + 1 }

    private fun <T> live(path: String, parse: (Any?) -> T): Flow<T> = flow {
        api.cached(path)?.let { emit(parse(it)) }
        var lastRevision: Any? = null
        var lastLocal = -1L
        while (true) {
            val local = localChanges.value
            val revision = runCatching { (api.get("/api/version") as? Map<*, *>)?.get("revision") }.getOrNull()
            if (revision == null || revision != lastRevision || local != lastLocal) {
                runCatching { api.get(path) }
                    .onSuccess { emit(parse(it)); lastRevision = revision; lastLocal = local }
                    .onFailure { Log.w(TAG, "GET $path failed: ${it.message}") }
            }
            withTimeoutOrNull(POLL_MS) { localChanges.first { it != local } }
        }
    }

    // ---- Sign-in ------------------------------------------------------------------

    /** Checks the server is reachable and returns the family members to choose from. */
    suspend fun people(serverUrl: String): List<Person> {
        val list = api.anonymous(serverUrl, "GET", "/api/people") as? List<*> ?: emptyList<Any>()
        return list.filterIsInstance<Map<*, *>>().map(Person::from)
    }

    suspend fun signIn(serverUrl: String, personId: String, pin: String) {
        val url = Api.normaliseUrl(serverUrl)
        val res = api.anonymous(url, "POST", "/api/login", mapOf("personId" to personId, "pin" to pin)) as Map<*, *>
        api.saveCredentials(Credentials(url, res["token"] as String, personId))
    }

    suspend fun signOut() {
        runCatching { api.post("/api/logout") }
        api.clearCredentials()
    }

    // ---- Family ---------------------------------------------------------------------

    private fun parseFamily(v: Any?): Family? = (v as? Map<*, *>)?.let { m ->
        @Suppress("UNCHECKED_CAST")
        Family.from("home", m as Map<String, Any?>)
    }

    fun familyFlow(): Flow<Family?> = live("/api/family", ::parseFamily)

    /** Fresh from the server, or the cached copy when offline. */
    suspend fun loadFamily(): Family? =
        runCatching { parseFamily(api.get("/api/family")) }.getOrElse { parseFamily(api.cached("/api/family")) }

    suspend fun updateFamily(fields: Map<String, Any?>) {
        api.put("/api/family/settings", fields); changed()
    }

    suspend fun savePeople(people: List<Person>) {
        api.put("/api/family/people", people.map { it.toMap() }); changed()
    }

    // ---- Days -------------------------------------------------------------------------

    @Suppress("UNCHECKED_CAST")
    private fun parseDay(v: Any?): Day? = (v as? Map<String, Any?>)?.let(Day::from)

    fun dayFlow(date: String): Flow<Day?> = live("/api/days/$date", ::parseDay)

    /** Fresh from the server (which creates the day if needed), or the cached copy. */
    suspend fun loadDay(date: String): Day? =
        runCatching { parseDay(api.get("/api/days/$date")) }.getOrElse { parseDay(api.cached("/api/days/$date")) }

    fun daysFlow(from: LocalDate, to: LocalDate): Flow<Map<String, Day>> =
        live("/api/days?from=${from.id()}&to=${to.id()}") { v ->
            (v as? List<*>)?.mapNotNull { parseDay(it) }?.associateBy { it.date } ?: emptyMap()
        }

    suspend fun saveDay(day: Day) {
        api.put("/api/days/${day.date}", day.toMap()); changed()
    }

    /** Changes one item (done / claimed) without overwriting other people's edits. */
    suspend fun updateItem(date: String, item: PlanItem, change: (PlanItem) -> PlanItem) {
        val next = change(item)
        api.patch("/api/days/$date/items/${item.id}", mapOf("done" to next.done, "claimedBy" to next.claimedBy)); changed()
    }

    suspend fun copyPeople(fromDate: String, toDate: String) {
        api.post("/api/days/$toDate/copy-people", mapOf("from" to fromDate)); changed()
    }

    // ---- Recurring & weekday defaults ---------------------------------------------------

    fun recurringFlow(): Flow<List<RecurringItem>> = live("/api/recurring") { v ->
        @Suppress("UNCHECKED_CAST")
        (v as? List<*>)?.filterIsInstance<Map<String, Any?>>()
            ?.map { RecurringItem.from(it["id"] as String, it) }
            ?.sortedWith(compareBy({ it.daysOfWeek.minOrNull() ?: 8 }, { it.time ?: "" })) ?: emptyList()
    }

    suspend fun saveRecurring(item: RecurringItem) {
        if (item.id.isBlank()) api.post("/api/recurring", item.toMap()) else api.put("/api/recurring/${item.id}", item.toMap())
        changed()
    }

    suspend fun deleteRecurring(id: String) {
        api.delete("/api/recurring/$id"); changed()
    }

    fun defaultsFlow(): Flow<Map<Int, Map<String, PersonDay>>> = live("/api/defaults") { v ->
        (v as? Map<*, *>)?.entries?.mapNotNull { (k, value) ->
            val weekday = k.toString().toIntOrNull() ?: return@mapNotNull null
            val people = ((value as? Map<*, *>)?.get("people") as? Map<*, *>)?.entries
                ?.associate { (id, pd) -> id.toString() to PersonDay.from(pd as? Map<*, *>) } ?: emptyMap()
            weekday to people
        }?.toMap() ?: emptyMap()
    }

    suspend fun saveDefaults(weekday: Int, people: Map<String, PersonDay>) {
        api.put("/api/defaults/$weekday", mapOf("people" to people.mapValues { it.value.toMap() })); changed()
    }

    // ---- Shopping list --------------------------------------------------------------------

    fun shoppingFlow(): Flow<List<ShoppingItem>> = live("/api/shopping") { v ->
        @Suppress("UNCHECKED_CAST")
        (v as? List<*>)?.filterIsInstance<Map<String, Any?>>()?.map { ShoppingItem.from(it["id"] as String, it) } ?: emptyList()
    }

    suspend fun addShopping(text: String) {
        api.post("/api/shopping", mapOf("text" to text.trim())); changed()
    }

    suspend fun setShoppingDone(id: String, done: Boolean) {
        api.patch("/api/shopping/$id", mapOf("done" to done)); changed()
    }

    suspend fun deleteShopping(id: String) {
        api.delete("/api/shopping/$id"); changed()
    }

    suspend fun clearDoneShopping() {
        api.post("/api/shopping/clear-done"); changed()
    }
}
