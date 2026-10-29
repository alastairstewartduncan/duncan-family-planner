package uk.co.duncan.familyplanner.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import uk.co.duncan.familyplanner.R
import uk.co.duncan.familyplanner.data.Person
import uk.co.duncan.familyplanner.data.UntrustedServerException
import uk.co.duncan.familyplanner.ui.PersonAvatar

/**
 * Two steps: enter the home server address (the home's static IP, or the PC's
 * name at home), check its security code the first time, then pick who you are
 * and enter the family PIN.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SignInScreen(
    busy: Boolean,
    error: String?,
    people: List<Person>?,
    initialServer: String,
    onConnect: (String) -> Unit,
    onSignIn: (server: String, personId: String, pin: String) -> Unit,
    onChangeServer: () -> Unit,
    trustPrompt: UntrustedServerException? = null,
    onTrust: () -> Unit = {},
    onDismissTrust: () -> Unit = {},
) {
    var server by rememberSaveable { mutableStateOf(initialServer) }
    var personId by rememberSaveable { mutableStateOf<String?>(null) }
    var pin by rememberSaveable { mutableStateOf("") }

    Surface(color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding().padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.primary) {
                Image(painterResource(R.drawable.ic_launcher_foreground), null, Modifier.size(112.dp))
            }
            Spacer(Modifier.height(20.dp))
            Text("Duncan Family Planner", style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
            Spacer(Modifier.height(8.dp))
            Text(
                "Who's where, what's for tea and who's taking who — for the whole family.",
                style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(28.dp))

            if (people == null) {
                OutlinedTextField(
                    value = server, onValueChange = { server = it.trim() },
                    label = { Text("Home server address") },
                    placeholder = { Text("e.g. 81.2.69.160") },
                    supportingText = { Text("The home's internet address — Alastair has it") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { if (server.isNotBlank()) onConnect(server) }),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                if (busy) CircularProgressIndicator()
                else Button(onClick = { onConnect(server) }, enabled = server.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text("Connect") }
            } else {
                Text("Who are you?", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(10.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)) {
                    people.forEach { p ->
                        FilterChip(
                            selected = personId == p.id, onClick = { personId = p.id },
                            label = { Text(p.name) }, leadingIcon = { PersonAvatar(p, 20) },
                        )
                    }
                }
                Spacer(Modifier.height(14.dp))
                OutlinedTextField(
                    value = pin, onValueChange = { v -> pin = v.filter(Char::isDigit).take(8) },
                    label = { Text("Family PIN") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { personId?.let { if (pin.length >= 4) onSignIn(server, it, pin) } }),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                if (busy) CircularProgressIndicator()
                else Button(
                    onClick = { personId?.let { onSignIn(server, it, pin) } },
                    enabled = personId != null && pin.length >= 4,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Sign in") }
                TextButton(onClick = { pin = ""; personId = null; onChangeServer() }) { Text("Change server") }
            }
            if (error != null) {
                Spacer(Modifier.height(16.dp))
                Text(error, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
            }
        }
    }

    trustPrompt?.let { t ->
        AlertDialog(
            onDismissRequest = onDismissTrust,
            icon = { Icon(if (t.changed) Icons.Default.Warning else Icons.Default.Lock, null) },
            title = { Text(if (t.changed) "Security code has changed" else "Check the security code") },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        if (t.changed) "This server isn't the one this phone trusted before. Only carry on if Alastair has just reinstalled the server and the code below matches the one on the PC."
                        else "Make sure this matches the security code shown on the family PC. It keeps anyone pretending to be the server out.",
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(16.dp))
                    Text(t.securityCode, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
                }
            },
            confirmButton = { TextButton(onClick = onTrust) { Text("It matches") } },
            dismissButton = { TextButton(onClick = onDismissTrust) { Text("Cancel") } },
        )
    }
}
