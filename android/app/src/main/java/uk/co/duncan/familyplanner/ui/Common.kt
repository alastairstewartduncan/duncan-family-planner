package uk.co.duncan.familyplanner.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import uk.co.duncan.familyplanner.data.Person
import uk.co.duncan.familyplanner.ui.theme.parseColour

val LocalSnackbar = staticCompositionLocalOf { SnackbarHostState() }

/** Launches a write and shows any failure in the snackbar instead of crashing. */
fun CoroutineScope.safeLaunch(snackbar: SnackbarHostState, success: String? = null, block: suspend () -> Unit) =
    launch {
        try {
            block()
            if (success != null) snackbar.showSnackbar(success)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            snackbar.showSnackbar("Couldn't save: ${e.message ?: "unknown error"}")
        }
    }

@Composable
fun PersonAvatar(person: Person?, size: Int = 32) {
    val colour = parseColour(person?.colour ?: "#607D8B")
    Box(
        modifier = Modifier.size(size.dp).clip(CircleShape).background(colour),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = person?.name?.take(1)?.uppercase() ?: "?",
            color = Color.White,
            fontWeight = FontWeight.Bold,
            fontSize = (size * 0.45f).sp,
        )
    }
}

/** Single-select chips for picking one person (or nobody). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PersonPicker(people: List<Person>, selected: String?, onSelect: (String?) -> Unit, allowNone: Boolean = true) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (allowNone) FilterChip(selected = selected == null, onClick = { onSelect(null) }, label = { Text("Nobody") })
        people.forEach { p ->
            FilterChip(
                selected = selected == p.id,
                onClick = { onSelect(p.id) },
                label = { Text(p.name) },
                leadingIcon = { PersonAvatar(p, 18) },
            )
        }
    }
}

/** Multi-select chips for picking several people. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PeopleMultiPicker(people: List<Person>, selected: List<String>, onChange: (List<String>) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        people.forEach { p ->
            val on = p.id in selected
            FilterChip(
                selected = on,
                onClick = { onChange(if (on) selected - p.id else selected + p.id) },
                label = { Text(p.name) },
            )
        }
    }
}

/** Quick-pick suggestion chips that fill a text field. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SuggestionChips(options: List<String>, current: String, onPick: (String) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEach { o ->
            FilterChip(selected = current.equals(o, ignoreCase = true), onClick = { onPick(o) }, label = { Text(o, fontSize = 12.sp) })
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimePickerDialog(initial: String?, onDismiss: () -> Unit, onPick: (String) -> Unit, onClear: (() -> Unit)? = null) {
    val (h, m) = initial?.split(":")?.mapNotNull { it.toIntOrNull() }?.takeIf { it.size == 2 }?.let { it[0] to it[1] } ?: (18 to 0)
    val state = rememberTimePickerState(initialHour = h, initialMinute = m, is24Hour = true)
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = { onPick("%02d:%02d".format(state.hour, state.minute)) }) { Text("Set") }
        },
        dismissButton = {
            Row {
                if (onClear != null) TextButton(onClick = onClear) { Text("No time") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
        text = { TimePicker(state = state) },
    )
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier,
    )
}

val STATUS_SUGGESTIONS = listOf("WFH", "Office", "School", "Day off", "Away")
val TRAVEL_SUGGESTIONS = listOf("Bus home", "Lift home", "Walking", "Driving")
