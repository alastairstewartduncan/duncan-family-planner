package uk.co.duncan.familyplanner.brick

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Nfc
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant

/**
 * Covers any app that isn't allowed while the phone is bricked. Holding the
 * phone to a Brick while this is showing unlocks straight away.
 */
class BlockActivity : ComponentActivity() {
    private var tapResult by mutableStateOf<TapResult?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        BrickEngine.init(this)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = goHome()
        })
        setContent {
            BrickTheme {
                val state by BrickEngine.state.collectAsStateWithLifecycle()
                LaunchedEffect(state.locked) {
                    if (!state.locked && tapResult == null) finish()
                }
                BlockScreen(state, onLaunch = ::launch)
                tapResult?.let { r ->
                    TapOutcomeDialog(r, onDone = {
                        tapResult = null
                        if (!BrickEngine.state.value.locked) finish()
                    })
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        BrickTags.enableReader(this) { tag ->
            val scan = BrickTags.read(tag)
            BrickTapHandler.handle(this, scan) { tapResult = it }
        }
    }

    override fun onPause() {
        BrickTags.disableReader(this)
        super.onPause()
    }

    private fun goHome() {
        startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        finish()
    }

    private fun launch(pkg: String) {
        packageManager.getLaunchIntentForPackage(pkg)?.let {
            startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            finish()
        }
    }

    companion object {
        const val EXTRA_BLOCKED = "blocked"
    }
}

private val Night = Color(0xFF101614)
private val Moss = Color(0xFF9DD1BC)
private val Ember = Color(0xFFF2A48F)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BlockScreen(state: LockState, onLaunch: (String) -> Unit) {
    val context = LocalContext.current
    var showRecovery by remember { mutableStateOf(false) }
    val apps by produceState(initialValue = emptyList<Pair<String, ImageBitmap?>>()) {
        value = (BrickEngine.allowedApps - BrickSetup.neverAllowed(context.packageManager))
            .mapNotNull { pkg ->
                runCatching {
                    val pm = context.packageManager
                    val info = pm.getApplicationInfo(pkg, 0)
                    pkg to pm.getApplicationIcon(info).toBitmap(96, 96).asImageBitmap()
                }.getOrNull()
            }
    }
    val now by produceState(Instant.now()) {
        while (true) { delay(1_000); value = Instant.now() }
    }
    val pulse = rememberInfiniteTransition(label = "pulse")
    val scale by pulse.animateFloat(1f, 1.12f, infiniteRepeatable(tween(1400), RepeatMode.Reverse), label = "scale")
    val glow by pulse.animateFloat(0.25f, 0.7f, infiniteRepeatable(tween(1400), RepeatMode.Reverse), label = "glow")

    Column(
        Modifier.fillMaxSize().background(Night).safeDrawingPadding().verticalScroll(rememberScrollState()).padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(40.dp))
        Box(contentAlignment = Alignment.Center) {
            Box(Modifier.size(150.dp).scale(scale).alpha(glow).clip(CircleShape).background(Moss.copy(alpha = 0.25f)))
            Box(
                Modifier.size(104.dp).clip(RoundedCornerShape(22.dp)).background(Moss),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Default.Nfc, null, tint = Night, modifier = Modifier.size(52.dp)) }
        }
        Spacer(Modifier.height(32.dp))
        Text(
            when (state.reason) {
                LockReason.Focus -> "Family Focus"
                LockReason.Schedule -> state.label
                else -> "Bricked"
            },
            color = Color.White, fontSize = 34.sp, fontFamily = FontFamily.Serif, fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(10.dp))
        val elapsed = state.since?.let { Duration.between(it, now) }
        if (elapsed != null && !elapsed.isNegative) {
            Text("Phone-free for ${human(elapsed)}", color = Moss, fontSize = 16.sp)
        }
        state.until?.let { until ->
            val left = Duration.between(now, until)
            if (!left.isNegative) Text("Unlocks in ${human(left)} (${BrickEngine.formatTime(until)})", color = Color.White.copy(alpha = 0.7f), fontSize = 14.sp)
        }
        Spacer(Modifier.height(24.dp))
        Text(
            if (state.canSelfUnlock) "Hold your phone against a Brick to unlock." else BrickEngine.lockedMessage(state),
            color = Color.White.copy(alpha = 0.85f), fontSize = 17.sp, textAlign = TextAlign.Center,
        )

        if (apps.isNotEmpty()) {
            Spacer(Modifier.height(36.dp))
            Text("STILL AVAILABLE", color = Moss, fontSize = 11.sp, letterSpacing = 1.2.sp)
            Spacer(Modifier.height(12.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterHorizontally), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                apps.forEach { (pkg, icon) ->
                    Box(
                        Modifier.size(56.dp).clip(RoundedCornerShape(16.dp)).background(Color.White.copy(alpha = 0.08f))
                            .clickable { onLaunch(pkg) },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (icon != null) Image(icon, BrickSetup.label(context, pkg), Modifier.size(38.dp))
                    }
                }
            }
        }

        Spacer(Modifier.height(36.dp))
        OutlinedButton(onClick = {
            context.startActivity(Intent(Intent.ACTION_DIAL).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }) {
            Icon(Icons.Default.Call, null, tint = Color.White)
            Spacer(Modifier.width(8.dp))
            Text("Phone", color = Color.White)
        }
        Spacer(Modifier.height(20.dp))
        Text(
            "Calls, messages and the planner always work. In a real emergency, use the sealed emergency tag.",
            color = Color.White.copy(alpha = 0.5f), fontSize = 12.sp, textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().border(1.dp, Ember.copy(alpha = 0.3f), RoundedCornerShape(12.dp)).padding(12.dp),
        )
        Spacer(Modifier.height(12.dp))
        TextButton(onClick = { showRecovery = true }) {
            Text("Use recovery code", color = Color.White.copy(alpha = 0.6f), fontSize = 13.sp)
        }
    }
    if (showRecovery) RecoveryDialog(onDismiss = { showRecovery = false })
}

/** A parent types the family recovery code to unlock this phone. It is checked on the phone, so it works offline. */
@Composable
private fun RecoveryDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var code by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Recovery code") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("A parent can type the family recovery code to unlock this phone. It works without a signal, and the family is told when it's used.")
                OutlinedTextField(
                    value = code,
                    onValueChange = { v -> code = v.filter { it.isLetterOrDigit() && it.code < 128 }.take(32) },
                    label = { Text("Recovery code") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    isError = message != null,
                    modifier = Modifier.fillMaxWidth(),
                )
                message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(enabled = code.length >= 8 && !busy, onClick = {
                busy = true
                message = null
                scope.launch {
                    val result = BrickEngine.useRecoveryCode(context, code)
                    busy = false
                    when (result) {
                        is RecoveryResult.Unlocked -> onDismiss()
                        is RecoveryResult.NotLocked -> onDismiss()
                        is RecoveryResult.Wrong -> {
                            code = ""
                            message = "That isn't the recovery code. ${result.triesLeft} left before a wait."
                        }
                        is RecoveryResult.Waiting -> {
                            code = ""
                            message = "Too many wrong codes. Try again in ${human(Duration.ofSeconds(result.seconds))}."
                        }
                        is RecoveryResult.NotSet -> message = "No recovery code has been set yet. A parent can set one in the planner under Brick, Parents."
                    }
                }
            }) { Text(if (busy) "Checking..." else "Unlock") }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("Cancel") } },
    )
}

fun human(d: Duration): String {
    val mins = d.toMinutes()
    return when {
        mins < 1 -> "under a minute"
        mins < 60 -> "$mins min"
        mins % 60 == 0L -> "${mins / 60} h"
        else -> "${mins / 60} h ${mins % 60} min"
    }
}

@Composable
fun BrickTheme(content: @Composable () -> Unit) = uk.co.duncan.familyplanner.ui.theme.FamilyPlannerTheme(content = content)

/** Runs a tap through the engine off the main thread and reports back. */
object BrickTapHandler {
    fun handle(activity: ComponentActivity, scan: BrickTags.Scan, onResult: (TapResult) -> Unit) {
        activity.lifecycleScope.launch {
            val result = BrickEngine.onTap(activity.applicationContext, scan)
            BrickFeedback.buzz(activity, result)
            onResult(result)
        }
    }
}
