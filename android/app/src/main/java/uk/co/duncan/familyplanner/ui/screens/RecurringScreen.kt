package uk.co.duncan.familyplanner.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import uk.co.duncan.familyplanner.data.Family
import uk.co.duncan.familyplanner.data.RecurringItem
import uk.co.duncan.familyplanner.data.Repository
import uk.co.duncan.familyplanner.data.WEEKDAY_SHORT
import uk.co.duncan.familyplanner.ui.LocalSnackbar
import uk.co.duncan.familyplanner.ui.PeopleMultiPicker
import uk.co.duncan.familyplanner.ui.PersonAvatar
import uk.co.duncan.familyplanner.ui.PersonPicker
import uk.co.duncan.familyplanner.ui.Session
import uk.co.duncan.familyplanner.ui.TimePickerDialog
import uk.co.duncan.familyplanner.ui.safeLaunch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecurringScreen(session: Session.Ready, onBack: () -> Unit) {
    val family = session.family
    val items by remember(family.id) { Repository.recurringFlow() }.collectAsStateWithLifecycle(initialValue = emptyList())
    var editing by remember { mutableStateOf<RecurringItem?>(null) }
    val scope = rememberCoroutineScope()
    val snackbar = LocalSnackbar.current

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                title = { Text("Regular events") },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { editing = RecurringItem(daysOfWeek = emptyList()) },
                icon = { Icon(Icons.Default.Add, null) },
                text = { Text("Add") },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Text(
                    "These are added to every matching day automatically, e.g. ice hockey on Tuesdays. Changes apply to the next two weeks straight away.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (items.isEmpty()) item { Text("No regular events yet.", color = MaterialTheme.colorScheme.outline) }
            items(items, key = { it.id }) { r ->
                Card(
                    modifier = Modifier.fillMaxWidth().clickable { editing = r },
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                ) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(r.title, style = MaterialTheme.typography.titleMedium)
                            val days = r.daysOfWeek.sorted().joinToString(", ") { WEEKDAY_SHORT[it - 1] }
                            Text(listOfNotNull(days.ifEmpty { "No days" }, r.time).joinToString(" · "), style = MaterialTheme.typography.bodyMedium)
                            if (!r.active) Text("Paused", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                        }
                        family.person(r.ownerPersonId)?.let { PersonAvatar(it, 28) }
                    }
                }
            }
        }
        editing?.let { r ->
            val delete: () -> Unit = {
                editing = null
                scope.safeLaunch(snackbar, "Deleted") { Repository.deleteRecurring(r.id) }
            }
            RecurringDialog(
                initial = r,
                family = family,
                onDismiss = { editing = null },
                onSave = { saved ->
                    editing = null
                    scope.safeLaunch(snackbar, "Saved") { Repository.saveRecurring(saved) }
                },
                onDelete = if (r.id.isBlank()) null else delete,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RecurringDialog(
    initial: RecurringItem,
    family: Family,
    onDismiss: () -> Unit,
    onSave: (RecurringItem) -> Unit,
    onDelete: (() -> Unit)?,
) {
    var r by remember(initial.id) { mutableStateOf(initial) }
    var pickTime by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial.id.isBlank()) "New regular event" else "Edit regular event") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = r.title, onValueChange = { r = r.copy(title = it) }, label = { Text("What") },
                    singleLine = true, keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("Every", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    WEEKDAY_SHORT.forEachIndexed { i, label ->
                        val d = i + 1
                        val on = d in r.daysOfWeek
                        FilterChip(selected = on, onClick = { r = r.copy(daysOfWeek = if (on) r.daysOfWeek - d else r.daysOfWeek + d) }, label = { Text(label) })
                    }
                }
                OutlinedButton(onClick = { pickTime = true }) {
                    Icon(Icons.Default.Schedule, null)
                    Spacer(Modifier.width(6.dp))
                    Text(r.time?.let { "At $it" } ?: "No set time")
                }
                if (r.time != null) {
                    OutlinedTextField(
                        value = r.durationMins.toString(),
                        onValueChange = { v -> v.filter(Char::isDigit).take(3).toIntOrNull()?.let { r = r.copy(durationMins = it) } },
                        label = { Text("Length (minutes)") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth(),
                    )
                }
                Text("Usually taken by", style = MaterialTheme.typography.labelLarge)
                PersonPicker(family.people, r.ownerPersonId, { r = r.copy(ownerPersonId = it) })
                Text("Who's it for", style = MaterialTheme.typography.labelLarge)
                PeopleMultiPicker(family.people, r.attendees) { r = r.copy(attendees = it) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Active", modifier = Modifier.weight(1f))
                    Switch(checked = r.active, onCheckedChange = { r = r.copy(active = it) })
                }
                if (onDelete != null) TextButton(onClick = onDelete) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(enabled = r.title.isNotBlank() && r.daysOfWeek.isNotEmpty(), onClick = { onSave(r.copy(title = r.title.trim())) }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
    if (pickTime) {
        TimePickerDialog(
            initial = r.time,
            onDismiss = { pickTime = false },
            onPick = { r = r.copy(time = it); pickTime = false },
            onClear = { r = r.copy(time = null); pickTime = false },
        )
    }
}
