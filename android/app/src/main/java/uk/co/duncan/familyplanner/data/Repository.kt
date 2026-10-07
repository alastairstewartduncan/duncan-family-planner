package uk.co.duncan.familyplanner.data

import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.SetOptions
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import java.time.LocalDate

/** Single access point for Firebase. Kept as an object to avoid a DI framework. */
object Repository {
    private const val TAG = "Repository"
    private val auth get() = FirebaseAuth.getInstance()
    private val db get() = FirebaseFirestore.getInstance()
    private val functions get() = FirebaseFunctions.getInstance("europe-west2")

    private fun family(id: String) = db.collection("families").document(id)
    private fun dayRef(familyId: String, date: String) = family(familyId).collection("days").document(date)

    // ---- Auth & membership -------------------------------------------------

    fun authState(): Flow<FirebaseUser?> = callbackFlow {
        val listener = FirebaseAuth.AuthStateListener { trySend(it.currentUser) }
        auth.addAuthStateListener(listener)
        awaitClose { auth.removeAuthStateListener(listener) }
    }

    fun signOut() = auth.signOut()

    /** Emits the user's membership, or null when the users/{uid} doc doesn't exist yet. */
    fun membership(uid: String): Flow<Membership?> = callbackFlow {
        val reg = db.collection("users").document(uid).addSnapshotListener { snap, err ->
            if (err != null) { Log.w(TAG, "membership", err); trySend(null); return@addSnapshotListener }
            val fid = snap?.getString("familyId")
            trySend(fid?.let { Membership(it, snap.getString("personId")) })
        }
        awaitClose { reg.remove() }
    }

    suspend fun currentMembership(): Membership? {
        val uid = auth.currentUser?.uid ?: return null
        val snap = db.collection("users").document(uid).get().await()
        return snap.getString("familyId")?.let { Membership(it, snap.getString("personId")) }
    }

    suspend fun joinFamily() {
        functions.getHttpsCallable("joinFamily").call().await()
    }

    suspend fun registerPushToken() {
        val uid = auth.currentUser?.uid ?: return
        runCatching {
            val token = FirebaseMessaging.getInstance().token.await()
            db.collection("users").document(uid)
                .update(mapOf("fcmTokens" to FieldValue.arrayUnion(token), "updatedAt" to FieldValue.serverTimestamp()))
                .await()
        }.onFailure { Log.w(TAG, "registerPushToken", it) }
    }

    suspend fun removePushToken() {
        val uid = auth.currentUser?.uid ?: return
        runCatching {
            val token = FirebaseMessaging.getInstance().token.await()
            db.collection("users").document(uid).update("fcmTokens", FieldValue.arrayRemove(token)).await()
        }
    }

    // ---- Family -------------------------------------------------------------

    fun familyFlow(familyId: String): Flow<Family?> = callbackFlow {
        val reg = family(familyId).addSnapshotListener { snap, err ->
            if (err != null) { Log.w(TAG, "family", err); return@addSnapshotListener }
            trySend(snap?.data?.let { Family.from(familyId, it) })
        }
        awaitClose { reg.remove() }
    }

    suspend fun loadFamily(familyId: String): Family? =
        family(familyId).get().await().data?.let { Family.from(familyId, it) }

    suspend fun updateFamily(familyId: String, fields: Map<String, Any?>) {
        family(familyId).update(fields).await()
    }

    suspend fun savePeople(familyId: String, people: List<Person>) {
        updateFamily(
            familyId,
            mapOf(
                "people" to people.map { it.toMap() },
                "memberEmails" to people.map { it.email.lowercase().trim() }.filter { it.isNotEmpty() }.distinct(),
            ),
        )
    }

    // ---- Days ---------------------------------------------------------------

    fun dayFlow(familyId: String, date: String): Flow<Day?> = callbackFlow {
        val reg = dayRef(familyId, date).addSnapshotListener { snap, err ->
            if (err != null) { Log.w(TAG, "day", err); return@addSnapshotListener }
            trySend(snap?.data?.let { Day.from(it) })
        }
        awaitClose { reg.remove() }
    }

    suspend fun loadDay(familyId: String, date: String): Day? =
        dayRef(familyId, date).get().await().data?.let { Day.from(it) }

    fun daysFlow(familyId: String, from: LocalDate, to: LocalDate): Flow<Map<String, Day>> = callbackFlow {
        val reg = family(familyId).collection("days")
            .whereGreaterThanOrEqualTo("date", from.id())
            .whereLessThanOrEqualTo("date", to.id())
            .addSnapshotListener { snap, err ->
                if (err != null) { Log.w(TAG, "days", err); return@addSnapshotListener }
                trySend(snap?.documents?.mapNotNull { d -> d.data?.let { Day.from(it) } }?.associateBy { it.date } ?: emptyMap())
            }
        awaitClose { reg.remove() }
    }

    /** Asks the server to create day docs (applies weekday defaults and recurring items). */
    suspend fun ensureDays(from: LocalDate, count: Int = 1) {
        runCatching {
            functions.getHttpsCallable("ensureDayRange").call(mapOf("from" to from.id(), "count" to count)).await()
        }.onFailure { Log.w(TAG, "ensureDays", it) }
    }

    suspend fun saveDay(familyId: String, day: Day, editorPersonId: String?) {
        val data = day.copy(updatedBy = editorPersonId ?: "unknown", userEdited = true).toMap() +
            ("updatedAt" to FieldValue.serverTimestamp())
        dayRef(familyId, day.date).set(data).await()
    }

    /** Atomically changes one item (claim, done…) without overwriting others' edits. */
    suspend fun updateItem(familyId: String, date: String, itemId: String, editorPersonId: String?, change: (PlanItem) -> PlanItem) {
        val ref = dayRef(familyId, date)
        db.runTransaction { tx ->
            val day = tx.get(ref).data?.let { Day.from(it) } ?: return@runTransaction null
            val items = day.items.map { if (it.id == itemId) change(it) else it }
            tx.set(ref, day.copy(items = items, updatedBy = editorPersonId ?: "unknown", userEdited = true).toMap() +
                ("updatedAt" to FieldValue.serverTimestamp()))
            null
        }.await()
    }

    /** Copies everyone's status/extras/travel from one day to another. */
    suspend fun copyPeople(familyId: String, fromDate: String, toDate: String, editorPersonId: String?) {
        val source = loadDay(familyId, fromDate) ?: return
        val ref = dayRef(familyId, toDate)
        db.runTransaction { tx ->
            val target = tx.get(ref).data?.let { Day.from(it) } ?: Day(toDate)
            tx.set(ref, target.copy(people = source.people, updatedBy = editorPersonId ?: "unknown", userEdited = true).toMap() +
                ("updatedAt" to FieldValue.serverTimestamp()))
            null
        }.await()
    }

    // ---- Recurring & weekday defaults ---------------------------------------

    fun recurringFlow(familyId: String): Flow<List<RecurringItem>> = callbackFlow {
        val reg = family(familyId).collection("recurring").addSnapshotListener { snap, err ->
            if (err != null) { Log.w(TAG, "recurring", err); return@addSnapshotListener }
            trySend(snap?.documents?.map { RecurringItem.from(it.id, it.data ?: emptyMap()) }
                ?.sortedWith(compareBy({ it.daysOfWeek.minOrNull() ?: 8 }, { it.time ?: "" })) ?: emptyList())
        }
        awaitClose { reg.remove() }
    }

    suspend fun saveRecurring(familyId: String, item: RecurringItem) {
        val col = family(familyId).collection("recurring")
        val ref: DocumentReference = if (item.id.isBlank()) col.document() else col.document(item.id)
        ref.set(item.toMap()).await()
    }

    suspend fun deleteRecurring(familyId: String, id: String) {
        family(familyId).collection("recurring").document(id).delete().await()
    }

    fun defaultsFlow(familyId: String): Flow<Map<Int, Map<String, PersonDay>>> = callbackFlow {
        val reg = family(familyId).collection("defaults").addSnapshotListener { snap, err ->
            if (err != null) { Log.w(TAG, "defaults", err); return@addSnapshotListener }
            trySend(snap?.documents?.mapNotNull { d ->
                val weekday = d.id.toIntOrNull() ?: return@mapNotNull null
                val people = (d.get("people") as? Map<*, *>)?.entries
                    ?.associate { (k, v) -> k.toString() to PersonDay.from(v as? Map<*, *>) } ?: emptyMap()
                weekday to people
            }?.toMap() ?: emptyMap())
        }
        awaitClose { reg.remove() }
    }

    suspend fun saveDefaults(familyId: String, weekday: Int, people: Map<String, PersonDay>) {
        family(familyId).collection("defaults").document(weekday.toString())
            .set(mapOf("people" to people.mapValues { it.value.toMap() })).await()
    }

    // ---- Shopping list --------------------------------------------------------

    fun shoppingFlow(familyId: String): Flow<List<ShoppingItem>> = callbackFlow {
        val reg = family(familyId).collection("shopping").orderBy("createdAt", Query.Direction.ASCENDING)
            .addSnapshotListener { snap, err ->
                if (err != null) { Log.w(TAG, "shopping", err); return@addSnapshotListener }
                trySend(snap?.documents?.map { ShoppingItem.from(it.id, it.data ?: emptyMap()) } ?: emptyList())
            }
        awaitClose { reg.remove() }
    }

    suspend fun addShopping(familyId: String, text: String, personId: String?) {
        family(familyId).collection("shopping").add(
            mapOf("text" to text.trim(), "done" to false, "addedBy" to personId, "createdAt" to FieldValue.serverTimestamp()),
        ).await()
    }

    suspend fun setShoppingDone(familyId: String, id: String, done: Boolean) {
        family(familyId).collection("shopping").document(id).set(mapOf("done" to done), SetOptions.merge()).await()
    }

    suspend fun deleteShopping(familyId: String, id: String) {
        family(familyId).collection("shopping").document(id).delete().await()
    }

    suspend fun clearDoneShopping(familyId: String) {
        val done = family(familyId).collection("shopping").whereEqualTo("done", true).get().await()
        val batch = db.batch()
        done.documents.forEach { batch.delete(it.reference) }
        batch.commit().await()
    }
}
