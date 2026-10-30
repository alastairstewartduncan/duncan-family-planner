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
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException
import javax.net.ssl.X509TrustManager

/** Where the server is and who is signed in. Stored on the phone. */
data class Credentials(val serverUrl: String, val token: String, val personId: String)

class ApiException(val status: Int, message: String) : IOException(message)

/**
 * The home server makes its own HTTPS certificate, so there's no certificate
 * authority to vouch for it. Instead each phone "pins" it: the first time, the
 * person checks the security code on the phone matches the one shown on the PC,
 * and from then on the phone only talks to a server with that exact certificate.
 */
class UntrustedServerException(val serverUrl: String, val fingerprint: String, val changed: Boolean) :
    IOException(if (changed) "The home server's security code has changed." else "Check the home server's security code.") {
    /** First 16 hex digits in groups of four — the same code the PC shows. */
    val securityCode: String get() = fingerprint.take(16).uppercase().chunked(4).joinToString(" ")
}

private fun sha256Hex(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

/** Accepts only the pinned certificate, and remembers what it saw so a new one can be offered to the person. */
private class PinnedTrust(val expected: String?) : X509TrustManager {
    @Volatile var seen: String? = null
    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        val leaf = chain?.firstOrNull() ?: throw CertificateException("No certificate")
        val fp = sha256Hex(leaf.encoded)
        seen = fp
        if (expected == null || fp != expected) throw CertificateException("Certificate isn't the pinned one")
    }
    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = throw CertificateException("Not used")
    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}

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

    // ---- Pinned certificates (kept when signing out) ----------------------------------

    private fun pinKey(serverUrl: String) = runCatching { URL(serverUrl).let { "pin:${it.host}:${it.port}" } }.getOrDefault("pin:$serverUrl")
    fun pinFor(serverUrl: String): String? = prefs.getString(pinKey(serverUrl), null)
    fun trust(serverUrl: String, fingerprint: String) { prefs.edit().putString(pinKey(serverUrl), fingerprint).apply() }

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
        val conn = (URL(base + path).openConnection() as HttpURLConnection)
        var trust: PinnedTrust? = null
        if (conn is HttpsURLConnection) {
            val pin = pinFor(base)
            val t = PinnedTrust(pin).also { trust = it }
            conn.sslSocketFactory = SSLContext.getInstance("TLS").apply { init(null, arrayOf(t), null) }.socketFactory
            // The server is reached by IP address, so the name on the certificate can't match.
            // Identity is checked by the pinned certificate itself instead.
            conn.hostnameVerifier = HostnameVerifier { _, session ->
                pin != null && runCatching { sha256Hex(session.peerCertificates[0].encoded) == pin }.getOrDefault(false)
            }
        }
        conn.apply {
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
        } catch (e: SSLException) {
            val seen = trust?.seen ?: throw e
            val pinned = pinFor(base)
            if (seen == pinned) throw e // the right server; something else went wrong
            throw UntrustedServerException(base, seen, changed = pinned != null)
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        fun normaliseUrl(input: String): String {
            var u = input.trim().trimEnd('/')
            if (!u.startsWith("http://") && !u.startsWith("https://")) u = "https://$u"
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
