package uk.co.duncan.familyplanner.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
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
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import uk.co.duncan.familyplanner.MainActivity
import uk.co.duncan.familyplanner.data.Repository
import uk.co.duncan.familyplanner.data.id
import uk.co.duncan.familyplanner.data.short
import uk.co.duncan.familyplanner.data.sortedForDisplay
import uk.co.duncan.familyplanner.ui.theme.parseColour
import java.time.LocalDate

/** What the widget shows; built from Firestore (or its offline cache). */
data class WidgetLine(val dot: Color?, val label: String, val text: String, val bold: Boolean = false)
data class WidgetState(val title: String, val lines: List<WidgetLine>, val message: String? = null)

class TodayWidget : GlanceAppWidget() {
    override val sizeMode: SizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val state = withTimeoutOrNull(10_000) { load() }
            ?: WidgetState(LocalDate.now().short(), emptyList(), "Couldn't load — tap to open")
        provideContent { GlanceTheme { Content(state) } }
    }

    private suspend fun load(): WidgetState {
        val today = LocalDate.now()
        val title = today.short()
        if (FirebaseAuth.getInstance().currentUser == null) return WidgetState(title, emptyList(), "Tap to sign in")
        return runCatching {
            val m = Repository.currentMembership() ?: return WidgetState(title, emptyList(), "Tap to join the family")
            val family = Repository.loadFamily(m.familyId) ?: return WidgetState(title, emptyList(), "Tap to open")
            val day = Repository.loadDay(m.familyId, today.id()) ?: return WidgetState(title, emptyList(), "Nothing planned yet")
            val lines = mutableListOf<WidgetLine>()
            family.people.forEach { p ->
                val s = day.people[p.id]?.summary().orEmpty()
                if (s.isNotEmpty()) lines += WidgetLine(parseColour(p.colour), p.name, s)
            }
            if (day.tea.isNotBlank()) lines += WidgetLine(null, "Tea", day.tea, bold = true)
            day.items.sortedForDisplay().forEach { item ->
                val who = family.person(item.responsible)?.name?.let { " ($it)" }.orEmpty()
                lines += WidgetLine(null, item.time ?: "•", item.title + who)
            }
            if (day.notes.isNotBlank()) lines += WidgetLine(null, "Note", day.notes)
            WidgetState(title, lines, if (lines.isEmpty()) "Nothing planned yet" else null)
        }.getOrElse { WidgetState(title, emptyList(), "Couldn't load — tap to open") }
    }

    @Composable
    private fun Content(state: WidgetState) {
        val onSurface = GlanceTheme.colors.onSurface
        val accent = GlanceTheme.colors.primary
        Column(
            modifier = GlanceModifier
                .fillMaxSize()
                .cornerRadius(20.dp)
                .background(GlanceTheme.colors.widgetBackground)
                .padding(14.dp)
                .clickable(actionStartActivity<MainActivity>()),
        ) {
            Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Family plan", style = TextStyle(color = accent, fontSize = 12.sp, fontWeight = FontWeight.Medium))
                Spacer(GlanceModifier.width(8.dp))
                Text(state.title, style = TextStyle(color = onSurface, fontSize = 15.sp, fontWeight = FontWeight.Bold))
            }
            Spacer(GlanceModifier.height(8.dp))
            if (state.message != null && state.lines.isEmpty()) {
                Text(state.message, style = TextStyle(color = onSurface, fontSize = 14.sp))
            } else {
                LazyColumn {
                    items(state.lines) { line ->
                        Row(GlanceModifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (line.dot != null) {
                                Box(GlanceModifier.size(10.dp).cornerRadius(5.dp).background(line.dot)) {}
                                Spacer(GlanceModifier.width(8.dp))
                            }
                            Text(
                                line.label,
                                style = TextStyle(color = if (line.dot != null) ColorProvider(line.dot) else accent, fontSize = 13.sp, fontWeight = FontWeight.Bold),
                            )
                            Spacer(GlanceModifier.width(6.dp))
                            Text(
                                line.text,
                                maxLines = 2,
                                style = TextStyle(color = onSurface, fontSize = 13.sp, fontWeight = if (line.bold) FontWeight.Medium else FontWeight.Normal),
                            )
                        }
                    }
                }
            }
        }
    }
}

class TodayWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = TodayWidget()
}

object WidgetUpdater {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    suspend fun refresh(context: Context) {
        runCatching { TodayWidget().updateAll(context.applicationContext) }
    }

    fun refreshAsync(context: Context) {
        val app = context.applicationContext
        scope.launch { refresh(app) }
    }
}
