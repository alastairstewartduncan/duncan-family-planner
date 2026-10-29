package uk.co.duncan.familyplanner.widget

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.ActionParameters
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import uk.co.duncan.familyplanner.MainActivity
import uk.co.duncan.familyplanner.R
import uk.co.duncan.familyplanner.brick.BrickEngine
import uk.co.duncan.familyplanner.brick.BrickSetup
import uk.co.duncan.familyplanner.brick.LockReason
import uk.co.duncan.familyplanner.brick.human
import uk.co.duncan.familyplanner.data.Repository
import java.time.Duration
import java.time.Instant

private val Night = Color(0xFF101614)
private val Moss = Color(0xFF9DD1BC)
private val Green = Color(0xFF2E5E4E)
private val Coral = Color(0xFFE07A5F)

/** What the Brick widget shows. */
private sealed interface BrickWidgetState {
    data object SignedOut : BrickWidgetState
    data object NeedsSetup : BrickWidgetState
    data class Ready(val todayMinutes: Long, val note: String?) : BrickWidgetState
    data class Bricked(val title: String, val since: Instant?, val until: Instant?, val canTag: Boolean) : BrickWidgetState
}

/**
 * Home-screen Brick button. One tap bricks the phone; walking to a Brick tag
 * and scanning it is the only way back. Shows how long you've been phone-free.
 */
class BrickWidget : GlanceAppWidget() {
    override val sizeMode: SizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        Repository.init(context)
        BrickEngine.init(context)
        val state = load(context)
        provideContent { Content(state) }
    }

    private fun load(context: Context): BrickWidgetState {
        val me = Repository.personId ?: return BrickWidgetState.SignedOut
        if (!BrickSetup.serviceEnabled(context)) return BrickWidgetState.NeedsSetup
        val lock = BrickEngine.refresh(context)
        if (lock.locked) {
            val title = when (lock.reason) {
                LockReason.Focus -> "Family Focus"
                LockReason.Schedule -> lock.label
                else -> "Bricked"
            }
            return BrickWidgetState.Bricked(title, lock.since, lock.until, lock.canSelfUnlock)
        }
        val today = BrickEngine.snapshot().stats[me]?.today?.toLong() ?: 0L
        val note = when {
            lock.emergencyUntil != null -> "Emergency unlock until ${BrickEngine.formatTime(lock.emergencyUntil)}"
            lock.passUntil != null -> "Pass until ${BrickEngine.formatTime(lock.passUntil)}"
            else -> BrickEngine.nextScheduled(BrickEngine.snapshot(), me)?.let { (s, at) -> "${s.label} ${BrickEngine.formatTime(at.toInstant())}" }
        }
        return BrickWidgetState.Ready(today, note)
    }

    @Composable
    private fun Content(state: BrickWidgetState) {
        val openBrick = actionStartActivity(
            Intent().setClassName("uk.co.duncan.familyplanner", MainActivity::class.java.name)
                .putExtra(MainActivity.EXTRA_OPEN, "brick")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        )
        val bricked = state is BrickWidgetState.Bricked
        Column(
            modifier = GlanceModifier.fillMaxSize().cornerRadius(24.dp)
                .background(if (bricked) Night else Color(0xFFE3EFE9)).padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            when (state) {
                BrickWidgetState.SignedOut -> Message("Sign in to the planner to use Brick", openBrick)
                BrickWidgetState.NeedsSetup -> Message("Finish setting up Brick (app blocker is off)", openBrick)
                is BrickWidgetState.Ready -> {
                    // The big button.
                    Box(
                        modifier = GlanceModifier.fillMaxWidth().defaultHeight().cornerRadius(18.dp).background(Green)
                            .clickable(actionRunCallback<BrickNowAction>()),
                        contentAlignment = Alignment.Center,
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Image(ImageProvider(R.drawable.ic_brick), "Brick", GlanceModifier.size(26.dp), colorFilter = ColorFilter.tint(ColorProvider(Color.White)))
                            Spacer(GlanceModifier.width(10.dp))
                            Text("Brick", style = TextStyle(color = ColorProvider(Color.White), fontSize = 22.sp, fontWeight = FontWeight.Bold))
                        }
                    }
                    Spacer(GlanceModifier.height(8.dp))
                    Text(
                        "Today: ${minutes(state.todayMinutes)} phone-free",
                        style = TextStyle(color = ColorProvider(Green), fontSize = 12.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center),
                        modifier = GlanceModifier.clickable(openBrick),
                    )
                    if (state.note != null) {
                        Text(state.note, maxLines = 1, style = TextStyle(color = ColorProvider(Color(0xFF5B6661)), fontSize = 11.sp, textAlign = TextAlign.Center))
                    }
                }
                is BrickWidgetState.Bricked -> {
                    Column(GlanceModifier.fillMaxWidth().clickable(openBrick), horizontalAlignment = Alignment.CenterHorizontally) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Image(ImageProvider(R.drawable.ic_brick), null, GlanceModifier.size(22.dp), colorFilter = ColorFilter.tint(ColorProvider(Moss)))
                            Spacer(GlanceModifier.width(8.dp))
                            Text(state.title, maxLines = 1, style = TextStyle(color = ColorProvider(Color.White), fontSize = 20.sp, fontWeight = FontWeight.Bold))
                        }
                        Spacer(GlanceModifier.height(4.dp))
                        state.since?.let {
                            Text("Since ${BrickEngine.formatTime(it)}", style = TextStyle(color = ColorProvider(Moss), fontSize = 13.sp))
                        }
                        Spacer(GlanceModifier.height(4.dp))
                        Text(
                            when {
                                state.until != null -> "Unlocks ${BrickEngine.formatTime(state.until)}"
                                state.canTag -> "Scan a Brick to unlock"
                                else -> "Ask a parent for a pass"
                            },
                            style = TextStyle(color = ColorProvider(Color.White.copy(alpha = .75f)), fontSize = 12.sp, textAlign = TextAlign.Center),
                        )
                    }
                }
            }
        }
    }

    @Composable
    private fun Message(text: String, action: androidx.glance.action.Action) {
        Text(
            text, modifier = GlanceModifier.fillMaxWidth().clickable(action),
            style = TextStyle(color = ColorProvider(Coral), fontSize = 13.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center),
        )
    }

    private fun GlanceModifier.defaultHeight() = this.height(64.dp)

    private fun minutes(m: Long) = if (m < 60) "${m}m" else "${m / 60}h ${m % 60}m"
}

/** The widget's Brick button. */
class BrickNowAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        Repository.init(context)
        BrickEngine.init(context)
        BrickEngine.brickNow(context)
        BrickWidgetUpdater.refresh(context)
    }
}

class BrickWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = BrickWidget()
}

object BrickWidgetUpdater {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    suspend fun refresh(context: Context) {
        runCatching { BrickWidget().updateAll(context.applicationContext) }
    }

    fun refreshAsync(context: Context) {
        val app = context.applicationContext
        scope.launch { refresh(app) }
    }
}

@Suppress("unused")
private fun elapsed(since: Instant?) = since?.let { human(Duration.between(it, Instant.now())) }
