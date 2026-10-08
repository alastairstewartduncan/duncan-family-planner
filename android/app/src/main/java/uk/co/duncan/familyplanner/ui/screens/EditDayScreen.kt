package uk.co.duncan.familyplanner.ui.screens

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import uk.co.duncan.familyplanner.data.Day
import uk.co.duncan.familyplanner.data.Family
import uk.co.duncan.familyplanner.data.PersonDay
import uk.co.duncan.familyplanner.data.PlanItem
import uk.co.duncan.familyplanner.data.Repository
import uk.co.duncan.familyplanner.data.id
import uk.co.duncan.familyplanner.data.pretty
import uk.co.duncan.familyplanner.data.sortedForDisplay
import uk.co.duncan.familyplanner.ui.LocalSnackbar
import uk.co.duncan.familyplanner.ui.PeopleMultiPicker
import uk.co.duncan.familyplanner.ui.PersonAvatar
import uk.co.duncan.familyplanner.ui.PersonPicker
import uk.co.duncan.familyplanner.ui.STATUS_SUGGESTIONS
import uk.co.duncan.familyplanner.ui.SectionLabel
import uk.co.duncan.familyplanner.ui.Session
import uk.co.duncan.familyplanner.ui.SuggestionChips
import uk.co.duncan.familyplanner.ui.TRAVEL_SUGGESTIONS
import uk.co.duncan.familyplanner.ui.TimePickerDialog
import uk.co.duncan.familyplanner.ui.safeLaunch
import uk.co.duncan.familyplanner.widget.WidgetUpdater
import java.time.LocalDate

private val sentence = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditDayScreen(session: Session.Ready, date: LocalDate, onDone: () -> Unit) {
    val family = session.family
    val dayId = date.id()
    var draft by remember(dayId) { mutableStateOf<Day?>(null) }
    var saving by remember { mutableStateOf(false) }
    var editingItem by remember { mutableStateOf<PlanItem?>(null) }
    val scope = rememberCoroutineScope()
    val snackbar = LocalSnackbar.current
    val context = LocalContext.current

    // Load once into a local draft so typing isn't disturbed by live updates.
    LaunchedEffect(family.id, dayId) {
        draft = Repository.loadDay(dayId) ?: Day(dayId)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onDone) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                title = { Text(date.pretty()) },
                actions = {
                    Button(
                        enabled = draft != null && !saving,
                        onClick = {
                            val d = draft ?: return@Button
                            saving = true
                            scope.safeLaunch(snackbar, "Saved") {
                                try {
                                    Repository.saveDay(d)
                                    WidgetUpdater.refresh(context)
                                    onDone()
                                } finally {
                                    saving = false
                                }
                            }
                        },
                        modifier = Modifier.padding(end = 8.dp),
                    ) { Text(if (saving) "Saving…" else "Save") }
                },
            )
        },
    ) { padding ->
        val d = draft
        if (d == null) {
            Column(Modifier.fillMaxSize().padding(padding), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item { SectionLabel("Who's where") }
            items(family.people, key = { it.id }) { person ->
                val pd = d.people[person.id] ?: PersonDay()
                fun update(new: PersonDay) { draft = d.copy(people = d.people + (person.id to new)) }
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            PersonAvatar(person, 28)
                            Spacer(Modifier.width(10.dp))
                            Text(person.name, style = MaterialTheme.typography.titleMedium)
                        }
                        OutlinedTextField(
                            value = pd.status, onValueChange = { update(pd.copy(status = it)) },
                            label = { Text("Where / what") }, placeholder = { Text("WFH, Blackburn, school…") },
                            singleLine = true, keyboardOptions = sentence, modifier = Modifier.fillMaxWidth(),
                        )
                        SuggestionChips(STATUS_SUGGESTIONS, pd.status) { update(pd.copy(status = it)) }
                        OutlinedTextField(
                            value = pd.extras, onValueChange = { update(pd.copy(extras = it)) },
                            label = { Text("Extras (shown in bold)") }, placeholder = { Text("2nd lunch DT clinic") },
                            singleLine = true, keyboardOptions = sentence, modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = pd.travel, onValueChange = { update(pd.copy(travel = it)) },
                            label = { Text("Getting there / home") }, singleLine = true, keyboardOptions = sentence,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        SuggestionChips(TRAVEL_SUGGESTIONS, pd.travel) { update(pd.copy(travel = it)) }
                    }
                }
            }

            item {
                Column {
                    SectionLabel("Tea", Modifier.padding(top = 6.dp))
                    OutlinedTextField(
                        value = d.tea, onValueChange = { draft = d.copy(tea = it) },
                        placeholder = { Text("Chicken wings & waffle fries") }, singleLine = true,
                        keyboardOptions = sentence, modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                    )
                }
            }

            item { SectionLabel("Happening", Modifier.padding(top = 6.dp)) }
            items(d.items.sortedForDisplay(), key = { "i_" + it.id }) { item ->
                Card(
                    modifier = Modifier.fillMaxWidth().clickable { editingItem = item },
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                ) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(listOfNotNull(item.time, item.title).joinToString("  "), style = MaterialTheme.typography.titleMedium)
                            family.person(item.responsible)?.let { Text("${it.name} taking", style = MaterialTheme.typography.bodySmall) }
                        }
                        if (item.recurringId != null) Icon(Icons.Default.Repeat, "Repeats", tint = MaterialTheme.colorScheme.outline)
                        IconButton(onClick = {
                            draft = d.copy(
                                items = d.items.filterNot { it.id == item.id },
                                // Remember a removed recurring item so the server doesn't add it back.
                                skippedRecurring = item.recurringId?.let { d.skippedRecurring + it } ?: d.skippedRecurring,
                            )
                        }) { Icon(Icons.Default.Delete, "Remove") }
                    }
                }
            }
            item {
                OutlinedButton(onClick = { editingItem = PlanItem() }) {
                    Icon(Icons.Default.Add, null)
                    Spacer(Modifier.width(6.dp))
                    Text("Add something")
                }
            }

            item {
                Column {
                    SectionLabel("Notes", Modifier.padding(top = 6.dp))
                    OutlinedTextField(
                        value = d.notes, onValueChange = { draft = d.copy(notes = it) },
                        placeholder = { Text("Anything else everyone should know") },
                        keyboardOptions = sentence, minLines = 2, modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                    )
                }
            }
        }

        editingItem?.let { item ->
            ItemDialog(
                initial = item,
                family = family,
                onDismiss = { editingItem = null },
                onSave = { saved ->
                    val exists = d.items.any { it.id == saved.id }
                    draft = d.copy(items = if (exists) d.items.map { if (it.id == saved.id) saved else it } else d.items + saved)
                    editingItem = null
                },
            )
        }
    }
}

@Composable
fun ItemDialog(initial: PlanItem, family: Family, onDismiss: () -> Unit, onSave: (PlanItem) -> Unit) {
    var item by remember(initial.id) { mutableStateOf(initial) }
    var pickTime by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial.title.isBlank()) "Add to the day" else "Edit") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = item.title, onValueChange = { item = item.copy(title = it) },
                    label = { Text("What") }, placeholder = { Text("Ice hockey, Hannah, Beavers…") },
                    singleLine = true, keyboardOptions = sentence, modifier = Modifier.fillMaxWidth(),
                )
                OutlinedButton(onClick = { pickTime = true }) {
                    Icon(Icons.Default.Schedule, null)
                    Spacer(Modifier.width(6.dp))
                    Text(item.time?.let { "At $it" } ?: "Add a time (puts it in the calendar)")
                }
                if (item.time != null) {
                    OutlinedTextField(
                        value = item.durationMins.toString(),
                        onValueChange = { v -> v.filter(Char::isDigit).take(3).toIntOrNull()?.let { item = item.copy(durationMins = it) } },
                        label = { Text("Length (minutes)") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Text("Who's taking / responsible", style = MaterialTheme.typography.labelLarge)
                PersonPicker(family.people, item.ownerPersonId, { item = item.copy(ownerPersonId = it, claimedBy = null) })
                Text("Who's it for", style = MaterialTheme.typography.labelLarge)
                PeopleMultiPicker(family.people, item.attendees) { item = item.copy(attendees = it) }
                OutlinedTextField(
                    value = item.notes, onValueChange = { item = item.copy(notes = it) },
                    label = { Text("Notes") }, keyboardOptions = sentence, modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(enabled = item.title.isNotBlank(), onClick = { onSave(item.copy(title = item.title.trim())) }) { Text("OK") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
    if (pickTime) {
        TimePickerDialog(
            initial = item.time,
            onDismiss = { pickTime = false },
            onPick = { item = item.copy(time = it); pickTime = false },
            onClear = { item = item.copy(time = null); pickTime = false },
        )
    }
}
