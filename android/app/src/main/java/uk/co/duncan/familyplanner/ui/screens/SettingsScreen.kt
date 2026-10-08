package uk.co.duncan.familyplanner.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.ViewWeek
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import uk.co.duncan.familyplanner.notify.MorningAlarm
import uk.co.duncan.familyplanner.data.Person
import uk.co.duncan.familyplanner.data.Repository
import uk.co.duncan.familyplanner.data.WEEKDAY_SHORT
import uk.co.duncan.familyplanner.ui.LocalSnackbar
import uk.co.duncan.familyplanner.ui.PersonAvatar
import uk.co.duncan.familyplanner.ui.SectionLabel
import uk.co.duncan.familyplanner.ui.Session
import uk.co.duncan.familyplanner.ui.TimePickerDialog
import uk.co.duncan.familyplanner.ui.safeLaunch
import uk.co.duncan.familyplanner.ui.theme.parseColour

private val PALETTE = listOf("#D81B60", "#1E88E5", "#43A047", "#FB8C00", "#8E24AA", "#00897B", "#6D4C41", "#546E7A")

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    session: Session.Ready,
    onOpenRecurring: () -> Unit,
    onOpenDefaults: () -> Unit,
    onSignOut: () -> Unit,
) {
    val family = session.family
    val scope = rememberCoroutineScope()
    val snackbar = LocalSnackbar.current
    val context = LocalContext.current
    var pickTime by remember { mutableStateOf(false) }
    var calendarId by remember(family.calendarId) { mutableStateOf(family.calendarId) }
    var editingPerson by remember { mutableStateOf<Person?>(null) }

    fun update(fields: Map<String, Any?>) = scope.safeLaunch(snackbar) { Repository.updateFamily(fields) }

    Scaffold(topBar = { TopAppBar(title = { Text("More", style = MaterialTheme.typography.titleLarge) }) }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                val itemColors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
                ListItem(
                    headlineContent = { Text("Regular events") },
                    supportingContent = { Text("Ice hockey, Beavers… added automatically") },
                    leadingContent = { Icon(Icons.Default.Repeat, null) },
                    trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null) },
                    colors = itemColors,
                    modifier = Modifier.clickable(onClick = onOpenRecurring),
                )
                HorizontalDivider()
                ListItem(
                    headlineContent = { Text("Usual week") },
                    supportingContent = { Text("Each person's normal day, used for new days") },
                    leadingContent = { Icon(Icons.Default.ViewWeek, null) },
                    trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null) },
                    colors = itemColors,
                    modifier = Modifier.clickable(onClick = onOpenDefaults),
                )
            }

            SectionLabel("Morning reminder", Modifier.padding(top = 8.dp))
            Text(
                "Each phone shows the day's plan at this time. Everyone shares the same setting.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { pickTime = true }) { Text("Show at ${family.reminderTime}") }
                TextButton(onClick = { scope.safeLaunch(snackbar) { MorningAlarm.showToday(context) } }) { Text("Send me a test") }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                WEEKDAY_SHORT.forEachIndexed { i, label ->
                    val d = i + 1
                    val on = d in family.reminderDays
                    FilterChip(
                        selected = on,
                        onClick = {
                            val next = if (on) family.reminderDays - d else family.reminderDays + d
                            update(mapOf("reminderDays" to next.sorted()))
                        },
                        label = { Text(label) },
                    )
                }
            }

            SectionLabel("Google Calendar", Modifier.padding(top = 8.dp))
            Text(
                "Anything with a time is added to this shared calendar by the home server. Leave blank to turn syncing off.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = calendarId, onValueChange = { calendarId = it.trim() },
                label = { Text("Calendar ID") }, placeholder = { Text("…@group.calendar.google.com") },
                singleLine = true, leadingIcon = { Icon(Icons.Default.CalendarMonth, null) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                modifier = Modifier.fillMaxWidth(),
            )
            if (calendarId != family.calendarId) {
                OutlinedButton(onClick = { scope.safeLaunch(snackbar, "Calendar saved") { Repository.updateFamily(mapOf("calendarId" to calendarId)) } }) {
                    Text("Save calendar")
                }
            }

            SectionLabel("Family", Modifier.padding(top = 8.dp))
            family.people.forEach { p ->
                Row(
                    Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).clickable { editingPerson = p }.padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PersonAvatar(p, 36)
                    Spacer(Modifier.width(12.dp))
                    Text(p.name + if (p.id == session.personId) " (you)" else "", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                }
            }
            TextButton(onClick = { editingPerson = Person(id = "", name = "", colour = PALETTE[family.people.size % PALETTE.size]) }) {
                Icon(Icons.Default.Add, null)
                Spacer(Modifier.width(6.dp))
                Text("Add a family member")
            }

            SectionLabel("Account", Modifier.padding(top = 8.dp))
            Text("Signed in as ${session.me?.name ?: "?"}", style = MaterialTheme.typography.bodyMedium)
            Text("Server: ${session.serverUrl}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedButton(onClick = onSignOut) {
                Icon(Icons.AutoMirrored.Filled.Logout, null)
                Spacer(Modifier.width(6.dp))
                Text("Sign out")
            }
            Spacer(Modifier.padding(24.dp))
        }
    }

    if (pickTime) {
        TimePickerDialog(
            initial = family.reminderTime,
            onDismiss = { pickTime = false },
            onPick = { pickTime = false; update(mapOf("reminderTime" to it)) },
        )
    }

    editingPerson?.let { person ->
        PersonDialog(
            initial = person,
            canDelete = person.id.isNotBlank() && person.id != session.personId,
            onDismiss = { editingPerson = null },
            onSave = { saved ->
                editingPerson = null
                val withId = if (saved.id.isBlank()) saved.copy(id = uniqueId(saved.name, family.people)) else saved
                val people = if (family.people.any { it.id == withId.id }) family.people.map { if (it.id == withId.id) withId else it } else family.people + withId
                scope.safeLaunch(snackbar, "Saved") { Repository.savePeople(people) }
            },
            onDelete = {
                editingPerson = null
                scope.safeLaunch(snackbar, "Removed") { Repository.savePeople(family.people.filterNot { it.id == person.id }) }
            },
        )
    }
}

private fun uniqueId(name: String, people: List<Person>): String {
    val base = name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').ifBlank { "person" }
    var id = base
    var n = 2
    while (people.any { it.id == id }) id = "$base-${n++}"
    return id
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PersonDialog(initial: Person, canDelete: Boolean, onDismiss: () -> Unit, onSave: (Person) -> Unit, onDelete: () -> Unit) {
    var p by remember(initial) { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial.id.isBlank()) "Add family member" else "Edit ${initial.name}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(p.name, { p = p.copy(name = it) }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Text("Colour", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    PALETTE.forEach { hex ->
                        val selected = hex.equals(p.colour, ignoreCase = true)
                        Box(
                            Modifier.size(36.dp).clip(CircleShape).background(parseColour(hex))
                                .then(if (selected) Modifier.border(3.dp, MaterialTheme.colorScheme.onSurface, CircleShape) else Modifier)
                                .clickable { p = p.copy(colour = hex) },
                        )
                    }
                }
                if (canDelete) TextButton(onClick = onDelete) { Text("Remove from family", color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = { TextButton(enabled = p.name.isNotBlank(), onClick = { onSave(p.copy(name = p.name.trim())) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
