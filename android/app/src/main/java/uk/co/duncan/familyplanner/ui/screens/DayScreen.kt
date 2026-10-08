package uk.co.duncan.familyplanner.ui.screens

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
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.Today
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import uk.co.duncan.familyplanner.data.Day
import uk.co.duncan.familyplanner.data.Family
import uk.co.duncan.familyplanner.data.PlanItem
import uk.co.duncan.familyplanner.data.Repository
import uk.co.duncan.familyplanner.data.id
import uk.co.duncan.familyplanner.data.pretty
import uk.co.duncan.familyplanner.ui.LocalSnackbar
import uk.co.duncan.familyplanner.ui.PersonAvatar
import uk.co.duncan.familyplanner.ui.SectionLabel
import uk.co.duncan.familyplanner.ui.Session
import uk.co.duncan.familyplanner.ui.safeLaunch
import uk.co.duncan.familyplanner.widget.WidgetUpdater
import androidx.compose.ui.platform.LocalContext
import java.time.LocalDate

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DayScreen(
    session: Session.Ready,
    date: LocalDate,
    onDateChange: (LocalDate) -> Unit,
    onEdit: (LocalDate) -> Unit,
) {
    val family = session.family
    val dayId = date.id()
    val day by remember(family.id, dayId) { Repository.dayFlow(dayId) }
        .collectAsStateWithLifecycle(initialValue = null)
    var slow by remember(dayId) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val snackbar = LocalSnackbar.current
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    var menu by remember { mutableStateOf(false) }

    // If nothing has arrived after a few seconds the server is probably unreachable.
    LaunchedEffect(dayId) {
        kotlinx.coroutines.delay(8_000)
        slow = true
    }

    val today = LocalDate.now()
    val label = when (date) {
        today -> "Today"
        today.plusDays(1) -> "Tomorrow"
        today.minusDays(1) -> "Yesterday"
        else -> null
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(date.pretty(), style = MaterialTheme.typography.titleLarge)
                        if (label != null) Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    }
                },
                actions = {
                    IconButton(onClick = { onDateChange(date.minusDays(1)) }) {
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Previous day")
                    }
                    if (date != today) IconButton(onClick = { onDateChange(today) }) { Icon(Icons.Default.Today, "Today") }
                    IconButton(onClick = { onDateChange(date.plusDays(1)) }) {
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Next day")
                    }
                    IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "More") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(
                            text = { Text("Copy as text") },
                            leadingIcon = { Icon(Icons.Default.ContentCopy, null) },
                            onClick = {
                                menu = false
                                day?.let { clipboard.setText(AnnotatedString(it.asText(family))) }
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Copy people from previous day") },
                            leadingIcon = { Icon(Icons.Default.Repeat, null) },
                            onClick = {
                                menu = false
                                scope.safeLaunch(snackbar, "Copied from ${date.minusDays(1).dayOfWeek.name.lowercase().replaceFirstChar { it.uppercase() }}") {
                                    Repository.copyPeople(date.minusDays(1).id(), dayId)
                                    WidgetUpdater.refresh(context)
                                }
                            },
                        )
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { onEdit(date) },
                icon = { Icon(Icons.Default.Edit, null) },
                text = { Text("Edit day") },
            )
        },
    ) { padding ->
        val d = day
        when {
            d == null && !slow -> Column(Modifier.fillMaxSize().padding(padding), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                CircularProgressIndicator()
            }
            d == null -> Column(Modifier.fillMaxSize().padding(padding).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Text("Can't reach the home server.", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Check Tailscale is connected on this phone and the PC is on. It'll load as soon as it can.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            else -> DayContent(d, session, Modifier.padding(padding)) { item, change ->
                scope.safeLaunch(snackbar) {
                    Repository.updateItem(dayId, item, change)
                    WidgetUpdater.refresh(context)
                }
            }
        }
    }
}

@Composable
private fun DayContent(
    day: Day,
    session: Session.Ready,
    modifier: Modifier,
    onItemChange: (PlanItem, (PlanItem) -> PlanItem) -> Unit,
) {
    val family = session.family
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { SectionLabel("Who's where") }
        items(family.people, key = { "p_" + it.id }) { person ->
            val pd = day.people[person.id]
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    PersonAvatar(person, 40)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(person.name, style = MaterialTheme.typography.titleMedium)
                        if (pd == null || pd.isEmpty()) {
                            Text("Not set", color = MaterialTheme.colorScheme.outline)
                        } else {
                            if (pd.status.isNotBlank()) Text(pd.status, style = MaterialTheme.typography.bodyLarge)
                            if (pd.extras.isNotBlank()) Text(pd.extras, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.secondary)
                            if (pd.travel.isNotBlank()) Text(pd.travel, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }

        if (day.tea.isNotBlank()) {
            item { SectionLabel("Tea", Modifier.padding(top = 8.dp)) }
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                    Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Restaurant, null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
                        Spacer(Modifier.width(12.dp))
                        Text(day.tea, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSecondaryContainer)
                    }
                }
            }
        }

        item { SectionLabel("Happening", Modifier.padding(top = 8.dp)) }
        if (day.items.isEmpty()) {
            item { Text("Nothing else on.", color = MaterialTheme.colorScheme.outline) }
        }
        items(day.items, key = { it.id }) { item -> ItemCard(item, family, session.personId, onItemChange) }

        if (day.notes.isNotBlank()) {
            item { SectionLabel("Notes", Modifier.padding(top = 8.dp)) }
            item { Text(day.notes, style = MaterialTheme.typography.bodyLarge) }
        }
    }
}

@Composable
private fun ItemCard(item: PlanItem, family: Family, myId: String?, onItemChange: (PlanItem, (PlanItem) -> PlanItem) -> Unit) {
    val responsible = family.person(item.responsible)
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 12.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = item.done, onCheckedChange = { checked -> onItemChange(item) { it.copy(done = checked) } })
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (item.time != null) {
                        Text(item.time, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(
                        item.title,
                        style = MaterialTheme.typography.titleMedium,
                        textDecoration = if (item.done) TextDecoration.LineThrough else null,
                    )
                    if (item.recurringId != null) {
                        Spacer(Modifier.width(6.dp))
                        Icon(Icons.Default.Repeat, "Repeats", tint = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(top = 2.dp))
                    }
                }
                val who = item.attendees.mapNotNull { family.person(it)?.name }
                if (who.isNotEmpty()) Text("For " + who.joinToString(", "), style = MaterialTheme.typography.bodySmall)
                if (item.notes.isNotBlank()) Text(item.notes, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (responsible != null) {
                    Text(
                        (if (item.claimedBy != null) "${responsible.name} is doing this" else "${responsible.name} taking"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary,
                    )
                }
            }
            when {
                responsible == null && myId != null ->
                    OutlinedButton(onClick = { onItemChange(item) { it.copy(claimedBy = myId) } }) { Text("I'll do it") }
                item.claimedBy != null && item.claimedBy == myId ->
                    TextButton(onClick = { onItemChange(item) { it.copy(claimedBy = null) } }) { Text("Undo") }
                else -> PersonAvatar(responsible, 28)
            }
        }
    }
}
