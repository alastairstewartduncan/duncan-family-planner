package uk.co.duncan.familyplanner.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import uk.co.duncan.familyplanner.data.Repository
import uk.co.duncan.familyplanner.data.short
import uk.co.duncan.familyplanner.ui.PersonAvatar
import uk.co.duncan.familyplanner.ui.Session
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WeekScreen(session: Session.Ready, onOpenDay: (LocalDate) -> Unit) {
    val family = session.family
    val today = LocalDate.now()
    var weekOffset by rememberSaveable { mutableStateOf(0L) }
    val monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).plusWeeks(weekOffset)
    val sunday = monday.plusDays(6)
    val days by remember(family.id, monday) { Repository.daysFlow(family.id, monday, sunday) }
        .collectAsStateWithLifecycle(initialValue = emptyMap())

    LaunchedEffect(family.id, monday) {
        // Only ask the server to fill in days from today onwards.
        val from = if (monday.isBefore(today)) today else monday
        if (!from.isAfter(sunday)) Repository.ensureDays(from, (sunday.toEpochDay() - from.toEpochDay() + 1).toInt())
    }

    val range = "${monday.format(DateTimeFormatter.ofPattern("d MMM", Locale.UK))} – ${sunday.format(DateTimeFormatter.ofPattern("d MMM", Locale.UK))}"
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(if (weekOffset == 0L) "This week" else if (weekOffset == 1L) "Next week" else "Week", style = MaterialTheme.typography.titleLarge)
                        Text(range, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    }
                },
                actions = {
                    IconButton(onClick = { weekOffset-- }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Previous week") }
                    IconButton(onClick = { weekOffset++ }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Next week") }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items((0L..6L).map { monday.plusDays(it) }, key = { it.toString() }) { date ->
                val day = days[date.toString()]
                val isToday = date == today
                Card(
                    modifier = Modifier.fillMaxWidth().clickable { onOpenDay(date) },
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                    border = if (isToday) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
                ) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            date.short() + if (isToday) " · Today" else "",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        if (day == null) {
                            Text("Not planned yet", color = MaterialTheme.colorScheme.outline)
                        } else {
                            family.people.forEach { p ->
                                val s = day.people[p.id]?.summary().orEmpty()
                                if (s.isNotEmpty()) Row(verticalAlignment = Alignment.CenterVertically) {
                                    PersonAvatar(p, 20)
                                    Spacer(Modifier.width(8.dp))
                                    Text(s, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                                }
                            }
                            if (day.tea.isNotBlank()) Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Restaurant, null, tint = MaterialTheme.colorScheme.secondary)
                                Spacer(Modifier.width(8.dp))
                                Text(day.tea, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.secondary)
                            }
                            day.items.forEach { item ->
                                val who = family.person(item.responsible)?.name
                                Text(
                                    listOfNotNull(item.time, item.title, who?.let { "($it)" }).joinToString(" "),
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
