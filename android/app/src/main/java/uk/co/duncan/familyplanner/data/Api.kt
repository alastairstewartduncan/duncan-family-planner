package uk.co.duncan.familyplanner.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Where the server is and who is signed in. Stored on the phone. */
data class Credentials(val serverUrl: String, val token: String, val personId: String)

class ApiException(val status: Int, message: String) : IOException(message)

/**
 * Minimal JSON-over-HTTP client for the home server, using only what ships
 * with Android (HttpURLConnection + org.json). Successful GET responses are
 * cached so the app and widget still show the last plan when offline.
 */
class Api(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("session", Context.MODE_PRIVATE)
    private val cache: SharedPreferences = context.getSharedPreferences("cache", Context.MODE_PRIVATE)

    private val _credentials = MutableStateFlow(readCredentials())
    val credentials: StateFlow<Credentials?> = _credentials

    private fun readCredentials(): Credentials? {
        val url = prefs.getString("serverUrl", null) ?: return null
        val token = prefs.getString("token", null) ?: return null
        val person = prefs.getString("personId", null) ?: return null
        return Credentials(url, token, person)
    }

    val lastServerUrl: String get() = prefs.getString("serverUrl", null) ?: ""

    fun saveCredentials(c: Credentials) {
        prefs.edit().putString("serverUrl", c.serverUrl).putString("token", c.token).putString("personId", c.personId).apply()
        _credentials.value = c
    }

    /** Signs out but remembers the server address for next time. */
    fun clearCredentials() {
        prefs.edit().remove("token").remove("personId").apply()
        cache.edit().clear().apply()
        _credentials.value = null
    }

    fun cached(path: String): Any? = cache.getString(path, null)?.let { parse(it) }

    suspend fun get(path: String): Any? = request("GET", path, null, cacheIt = true)
    suspend fun post(path: String, body: Any? = null): Any? = request("POST", path, body)
    suspend fun put(path: String, body: Any?): Any? = request("PUT", path, body)
    suspend fun patch(path: String, body: Any?): Any? = request("PATCH", path, body)
    suspend fun delete(path: String): Any? = request("DELETE", path, null)

    /** Calls a server that isn't saved yet (sign-in screen). */
    suspend fun anonymous(serverUrl: String, method: String, path: String, body: Any? = null): Any? =
        request(method, path, body, base = normaliseUrl(serverUrl), token = null)

    private suspend fun request(
        method: String,
        path: String,
        body: Any?,
        cacheIt: Boolean = false,
        base: String? = _credentials.value?.serverUrl,
        token: String? = _credentials.value?.token,
    ): Any? = withContext(Dispatchers.IO) {
        if (base == null) throw ApiException(401, "Not signed in")
        val conn = (URL(base + path).openConnection() as HttpURLConnection).apply {
            requestMethod = if (method == "PATCH") "POST" else method
            if (method == "PATCH") setRequestProperty("X-HTTP-Method-Override", "PATCH")
            connectTimeout = 8_000
            readTimeout = 15_000
            setRequestProperty("Accept", "application/json")
            if (token != null) setRequestProperty("Authorization", "Bearer $token")
        }
        try {
            if (body != null || method in listOf("POST", "PUT", "PATCH")) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                conn.outputStream.use { it.write(toJson(body ?: emptyMap<String, Any>()).toByteArray()) }
            }
            val status = conn.responseCode
            val text = (if (status in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (status !in 200..299) {
                val message = runCatching { JSONObject(text).optString("error") }.getOrNull()?.takeIf { it.isNotBlank() }
                    ?: "Server error ($status)"
                if (status == 401 && token != null) clearCredentials()
                throw ApiException(status, message)
            }
            if (cacheIt) cache.edit().putString(path, text).apply()
            parse(text)
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        fun normaliseUrl(input: String): String {
            var u = input.trim().trimEnd('/')
            if (!u.startsWith("http://") && !u.startsWith("https://")) u = "http://$u"
            // Default port if none given.
            val hostPart = u.substringAfter("://")
            if (!hostPart.contains(':')) u = "$u:8787"
            return u
        }

        fun parse(text: String): Any? {
            if (text.isBlank()) return null
            return unwrap(JSONTokener(text).nextValue())
        }

        private fun unwrap(v: Any?): Any? = when (v) {
            JSONObject.NULL, null -> null
            is JSONObject -> v.keys().asSequence().associateWith { unwrap(v.get(it)) }
            is JSONArray -> (0 until v.length()).map { unwrap(v.get(it)) }
            else -> v
        }

        fun toJson(v: Any?): String = wrap(v).let { if (it is JSONObject || it is JSONArray) it.toString() else JSONObject.quote(it.toString()) }

        private fun wrap(v: Any?): Any = when (v) {
            null -> JSONObject.NULL
            is Map<*, *> -> JSONObject().also { o -> v.forEach { (k, value) -> o.put(k.toString(), wrap(value)) } }
            is Iterable<*> -> JSONArray().also { a -> v.forEach { a.put(wrap(it)) } }
            else -> v
        }
    }
}
