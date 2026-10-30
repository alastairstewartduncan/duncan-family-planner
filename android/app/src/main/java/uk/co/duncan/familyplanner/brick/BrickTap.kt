package uk.co.duncan.familyplanner.brick

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Emergency
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.SupervisorAccount
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import uk.co.duncan.familyplanner.data.Repository

/** A short buzz pattern so you know the tap worked without looking. */
object BrickFeedback {
    fun buzz(context: Context, result: TapResult) {
        val v = context.getSystemService(Vibrator::class.java) ?: return
        if (!v.hasVibrator()) return
        val pattern = when (result) {
            is TapResult.Bricked -> longArrayOf(0, 60, 80, 160) // two beats: locked
            is TapResult.Unbricked, is TapResult.EmergencyUnlocked -> longArrayOf(0, 40) // one light tap: free
            is TapResult.ParentChoice -> longArrayOf(0, 40, 60, 40)
            else -> longArrayOf(0, 250) // one long: no
        }
        runCatching { v.vibrate(VibrationEffect.createWaveform(pattern, -1)) }
    }
}

/** Says what a tap did. Parents tapping during Family Focus or a schedule choose what happens. */
@Composable
fun TapOutcomeDialog(result: TapResult, onDone: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var error by androidx.compose.runtime.remember { mutableStateOf<String?>(null) }

    // Plain confirmations close themselves.
    if (result is TapResult.Bricked || result is TapResult.Unbricked) {
        LaunchedEffect(result) { delay(1_800); onDone() }
    }

    val (icon, title, body) = when (result) {
        is TapResult.Bricked -> Triple(Icons.Default.Lock, "Bricked", "Only your essential apps will open until you tap a Brick again.")
        is TapResult.Unbricked -> Triple(
            Icons.Default.LockOpen, "Welcome back",
            if (result.minutes >= 1) "You were phone-free for ${human(java.time.Duration.ofMinutes(result.minutes))}." else "Unlocked.",
        )
        is TapResult.EmergencyUnlocked -> Triple(
            Icons.Default.Emergency, "Emergency unlock",
            "Everything is open for ${result.minutes} minutes. The rest of the family has been told. Call 999 if you need help.",
        )
        is TapResult.ParentChoice -> Triple(
            Icons.Default.SupervisorAccount, result.state.label,
            "You're a parent, so you can unlock just your phone or end it for everyone.",
        )
        is TapResult.Denied -> Triple(Icons.Default.Block, "Not yet", result.message)
        is TapResult.Unknown -> Triple(Icons.AutoMirrored.Filled.HelpOutline, "Unknown tag", result.message)
    }

    AlertDialog(
        onDismissRequest = onDone,
        icon = { Icon(icon, null) },
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(body)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            if (result is TapResult.ParentChoice) {
                Column(horizontalAlignment = androidx.compose.ui.Alignment.End) {
                    TextButton(onClick = { BrickEngine.unlockJustMe(context, result.state); onDone() }) { Text("Unlock just me") }
                    if (result.state.reason == LockReason.Focus) {
                        TextButton(onClick = {
                            scope.launch {
                                try {
                                    Repository.endFamilyFocus()
                                    Repository.loadBrick()
                                    BrickEngine.refresh(context)
                                    onDone()
                                } catch (e: Exception) {
                                    error = "Couldn't reach home: ${e.message}. Unlocking just you instead."
                                    BrickEngine.unlockJustMe(context, result.state)
                                }
                            }
                        }) { Text("End Family Focus for everyone") }
                    }
                }
            } else {
                TextButton(onClick = onDone) { Text("OK") }
            }
        },
        dismissButton = {
            if (result is TapResult.ParentChoice) TextButton(onClick = onDone) { Text("Cancel") }
        },
    )
}

/**
 * Opened by Android when the phone touches a Brick tag (from any screen).
 * Shows the result over whatever was open, then gets out of the way.
 */
class TagTapActivity : ComponentActivity() {
    private var result by mutableStateOf<TapResult?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        BrickEngine.init(this)
        handle(intent)
        setContent {
            BrickTheme {
                result?.let { r ->
                    TapOutcomeDialog(r, onDone = {
                        finish()
                        // If the phone is now bricked, put the Brick screen up straight away.
                        if (BrickEngine.state.value.locked) {
                            startActivity(Intent(this, BlockActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        }
                    })
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent?) {
        val scan = BrickTags.fromIntent(intent)
        if (scan == null) {
            finish(); return
        }
        BrickTapHandler.handle(this, scan) { result = it }
    }
}
