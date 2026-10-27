package uk.co.duncan.familyplanner.brick

import android.app.Activity
import android.content.Intent
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.Ndef
import android.nfc.tech.NdefFormatable
import android.os.Build
import android.os.Parcelable
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * What's on a Brick tag: an NDEF record of our own MIME type holding
 * "v1|<tag id>|<secret>", plus an Android Application Record so tapping the
 * tag always opens this app. The server only ever sees sha256(uid:secret), so
 * a tag is checked against both its factory serial number and the secret.
 */
object BrickTags {
    const val MIME = "application/vnd.uk.co.duncan.brick"
    private const val PACKAGE = "uk.co.duncan.familyplanner"
    private val random = SecureRandom()

    data class Payload(val tagId: String, val secret: String)
    data class Scan(val uid: String, val payload: Payload?)
    data class Written(val tagId: String, val uid: String, val hash: String, val locked: Boolean)

    fun uidHex(tag: Tag): String = tag.id.joinToString("") { "%02X".format(it) }

    fun hash(uid: String, secret: String): String =
        MessageDigest.getInstance("SHA-256").digest("$uid:$secret".toByteArray()).joinToString("") { "%02x".format(it) }

    private fun randomToken(bytes: Int): String {
        val b = ByteArray(bytes).also(random::nextBytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b)
    }

    fun parse(message: NdefMessage?): Payload? {
        val record = message?.records?.firstOrNull {
            it.tnf == NdefRecord.TNF_MIME_MEDIA && String(it.type, Charsets.US_ASCII) == MIME
        } ?: return null
        val parts = String(record.payload, Charsets.UTF_8).split('|')
        if (parts.size != 3 || parts[0] != "v1" || parts[1].isBlank() || parts[2].isBlank()) return null
        return Payload(parts[1], parts[2])
    }

    /** Reads a tag handed to us by reader mode. */
    fun read(tag: Tag): Scan {
        val message = runCatching {
            Ndef.get(tag)?.use { ndef ->
                ndef.connect()
                ndef.cachedNdefMessage ?: ndef.ndefMessage
            }
        }.getOrNull()
        return Scan(uidHex(tag), parse(message))
    }

    /** Reads a tag from the intent Android sent when the phone touched it. */
    fun fromIntent(intent: Intent?): Scan? {
        intent ?: return null
        val tag = parcelable<Tag>(intent, NfcAdapter.EXTRA_TAG) ?: return null
        val messages = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableArrayExtra(NfcAdapter.EXTRA_NDEF_MESSAGES, NdefMessage::class.java)?.toList()
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableArrayExtra(NfcAdapter.EXTRA_NDEF_MESSAGES)?.filterIsInstance<NdefMessage>()
        }
        val payload = messages?.firstNotNullOfOrNull(::parse) ?: parse(runCatching {
            Ndef.get(tag)?.use { it.connect(); it.ndefMessage }
        }.getOrNull())
        return Scan(uidHex(tag), payload)
    }

    @Suppress("DEPRECATION")
    private inline fun <reified T : Parcelable> parcelable(intent: Intent, key: String): T? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) intent.getParcelableExtra(key, T::class.java)
        else intent.getParcelableExtra(key) as? T

    /**
     * Writes a fresh identity onto a tag. With [lock] the tag is made permanently
     * read-only, so nobody can rewrite or wipe it with another NFC app.
     */
    fun write(tag: Tag, lock: Boolean): Written {
        val tagId = randomToken(9)
        val secret = randomToken(24)
        val message = NdefMessage(
            arrayOf(
                NdefRecord.createMime(MIME, "v1|$tagId|$secret".toByteArray(Charsets.UTF_8)),
                NdefRecord.createApplicationRecord(PACKAGE),
            ),
        )
        val ndef = Ndef.get(tag)
        var locked = false
        if (ndef != null) {
            ndef.use {
                it.connect()
                if (!it.isWritable) throw IllegalStateException("This tag is locked. Use a blank tag.")
                if (it.maxSize < message.byteArrayLength) throw IllegalStateException("This tag is too small. Use an NTAG213/215/216 sticker or card.")
                it.writeNdefMessage(message)
                if (lock && it.canMakeReadOnly()) locked = it.makeReadOnly()
            }
        } else {
            val formatable = NdefFormatable.get(tag) ?: throw IllegalStateException("This tag can't hold Brick data. Use an NTAG213/215/216 tag.")
            formatable.use {
                it.connect()
                if (lock) {
                    it.formatReadOnly(message); locked = true
                } else {
                    it.format(message)
                }
            }
        }
        val uid = uidHex(tag)
        return Written(tagId, uid, hash(uid, secret), locked)
    }

    /** Hands NFC tags straight to [onTag] while [activity] is on screen (no system sound or app chooser). */
    fun enableReader(activity: Activity, onTag: (Tag) -> Unit): Boolean {
        val adapter = NfcAdapter.getDefaultAdapter(activity) ?: return false
        if (!adapter.isEnabled) return false
        val flags = NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_NFC_B or
            NfcAdapter.FLAG_READER_NFC_F or NfcAdapter.FLAG_READER_NFC_V
        adapter.enableReaderMode(activity, { tag -> onTag(tag) }, flags, null)
        return true
    }

    fun disableReader(activity: Activity) {
        runCatching { NfcAdapter.getDefaultAdapter(activity)?.disableReaderMode(activity) }
    }

    fun nfcState(activity: android.content.Context): NfcState {
        val adapter = NfcAdapter.getDefaultAdapter(activity) ?: return NfcState.Missing
        return if (adapter.isEnabled) NfcState.On else NfcState.Off
    }

    enum class NfcState { Missing, Off, On }
}
