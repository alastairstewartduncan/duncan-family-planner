package uk.co.duncan.familyplanner.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import uk.co.duncan.familyplanner.data.PersonDay
import uk.co.duncan.familyplanner.data.Repository
import uk.co.duncan.familyplanner.data.WEEKDAY_SHORT
import uk.co.duncan.familyplanner.ui.LocalSnackbar
import uk.co.duncan.familyplanner.ui.PersonAvatar
import uk.co.duncan.familyplanner.ui.STATUS_SUGGESTIONS
import uk.co.duncan.familyplanner.ui.Session
import uk.co.duncan.familyplanner.ui.SuggestionChips
import uk.co.duncan.familyplanner.ui.TRAVEL_SUGGESTIONS
import uk.co.duncan.familyplanner.ui.safeLaunch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DefaultsScreen(session: Session.Ready, onBack: () -> Unit) {
    val family = session.family
    val all by remember(family.id) { Repository.defaultsFlow() }
        .collectAsStateWithLifecycle(initialValue = null as Map<Int, Map<String, PersonDay>>?)
    var weekday by rememberSaveable { mutableStateOf(1) }
    var draft by remember(weekday) { mutableStateOf<Map<String, PersonDay>?>(null) }
    val scope = rememberCoroutineScope()
    val snackbar = LocalSnackbar.current

    // Fill the draft once the saved defaults have loaded (null = still loading).
    LaunchedEffect(weekday, all) {
        val loaded = all
        if (draft == null && loaded != null) draft = loaded[weekday] ?: emptyMap()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                title = { Text("Usual week") },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            ScrollableTabRow(selectedTabIndex = weekday - 1, edgePadding = 8.dp) {
                WEEKDAY_SHORT.forEachIndexed { i, label ->
                    Tab(selected = weekday == i + 1, onClick = { weekday = i + 1 }, text = { Text(label) })
                }
            }
            Column(
                Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    "What normally happens on this day. New days start from this, so you only change what's different.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val d = draft ?: emptyMap()
                family.people.forEach { person ->
                    val pd = d[person.id] ?: PersonDay()
                    fun update(new: PersonDay) { draft = d + (person.id to new) }
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                PersonAvatar(person, 28)
                                Spacer(Modifier.width(10.dp))
                                Text(person.name, style = MaterialTheme.typography.titleMedium)
                            }
                            val opts = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences)
                            OutlinedTextField(pd.status, { update(pd.copy(status = it)) }, label = { Text("Where / what") }, singleLine = true, keyboardOptions = opts, modifier = Modifier.fillMaxWidth())
                            SuggestionChips(STATUS_SUGGESTIONS, pd.status) { update(pd.copy(status = it)) }
                            OutlinedTextField(pd.extras, { update(pd.copy(extras = it)) }, label = { Text("Extras") }, singleLine = true, keyboardOptions = opts, modifier = Modifier.fillMaxWidth())
                            OutlinedTextField(pd.travel, { update(pd.copy(travel = it)) }, label = { Text("Getting there / home") }, singleLine = true, keyboardOptions = opts, modifier = Modifier.fillMaxWidth())
                            SuggestionChips(TRAVEL_SUGGESTIONS, pd.travel) { update(pd.copy(travel = it)) }
                        }
                    }
                }
                Button(
                    onClick = {
                        val toSave = (draft ?: emptyMap()).filterValues { !it.isEmpty() }
                        scope.safeLaunch(snackbar, "Saved ${WEEKDAY_SHORT[weekday - 1]}") {
                            Repository.saveDefaults(weekday, toSave)
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Save ${WEEKDAY_SHORT[weekday - 1]}") }
            }
        }
    }
}
