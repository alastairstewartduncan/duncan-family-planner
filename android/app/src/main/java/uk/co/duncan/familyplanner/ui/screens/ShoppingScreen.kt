package uk.co.duncan.familyplanner.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import uk.co.duncan.familyplanner.data.Repository
import uk.co.duncan.familyplanner.ui.LocalSnackbar
import uk.co.duncan.familyplanner.ui.PersonAvatar
import uk.co.duncan.familyplanner.ui.Session
import uk.co.duncan.familyplanner.ui.safeLaunch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShoppingScreen(session: Session.Ready) {
    val family = session.family
    val list by remember(family.id) { Repository.shoppingFlow() }.collectAsStateWithLifecycle(initialValue = emptyList())
    var text by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val snackbar = LocalSnackbar.current

    fun add() {
        val t = text.trim()
        if (t.isEmpty()) return
        text = ""
        scope.safeLaunch(snackbar) { Repository.addShopping(t) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Shopping list", style = MaterialTheme.typography.titleLarge) },
                actions = {
                    if (list.any { it.done }) TextButton(onClick = { scope.safeLaunch(snackbar) { Repository.clearDoneShopping() } }) {
                        Text("Clear ticked")
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = text, onValueChange = { text = it },
                    placeholder = { Text("Add an item, e.g. waffle fries") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { add() }),
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { add() }) { Icon(Icons.Default.Add, "Add") }
            }
            if (list.isEmpty()) {
                Text("The list is empty.", color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(16.dp))
            }
            val ordered = list.filter { !it.done } + list.filter { it.done }
            LazyColumn(contentPadding = PaddingValues(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(0.dp)) {
                items(ordered, key = { it.id }) { item ->
                    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = item.done, onCheckedChange = { c -> scope.safeLaunch(snackbar) { Repository.setShoppingDone(item.id, c) } })
                        Text(
                            item.text,
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyLarge,
                            textDecoration = if (item.done) TextDecoration.LineThrough else null,
                            color = if (item.done) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.onSurface,
                        )
                        family.person(item.addedBy)?.let { PersonAvatar(it, 22) }
                        IconButton(onClick = { scope.safeLaunch(snackbar) { Repository.deleteShopping(item.id) } }) {
                            Icon(Icons.Default.Close, "Delete")
                        }
                    }
                    HorizontalDivider(Modifier.padding(start = 56.dp))
                }
            }
        }
    }
}
