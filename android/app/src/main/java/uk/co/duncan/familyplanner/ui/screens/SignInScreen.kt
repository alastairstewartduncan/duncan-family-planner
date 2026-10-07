package uk.co.duncan.familyplanner.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import uk.co.duncan.familyplanner.R

@Composable
fun SignInScreen(
    busy: Boolean,
    error: String?,
    joinEmail: String?, // non-null when signed in but not yet a family member
    onSignIn: () -> Unit,
    onRetryJoin: () -> Unit,
    onSignOut: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier.fillMaxSize().padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.primary) {
                Image(painterResource(R.drawable.ic_launcher_foreground), null, Modifier.size(120.dp))
            }
            Spacer(Modifier.height(24.dp))
            Text("Duncan Family Planner", style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
            Spacer(Modifier.height(8.dp))
            Text(
                "Who's where, what's for tea and who's taking who — for the whole family.",
                style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(32.dp))
            when {
                busy -> CircularProgressIndicator()
                joinEmail != null -> {
                    Text(
                        "Signed in as $joinEmail. Joining the family…",
                        textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = onRetryJoin, modifier = Modifier.fillMaxWidth()) { Text("Try again") }
                    OutlinedButton(onClick = onSignOut, modifier = Modifier.fillMaxWidth()) { Text("Use a different account") }
                }
                else -> Button(onClick = onSignIn, modifier = Modifier.fillMaxWidth()) { Text("Sign in with Google") }
            }
            if (error != null) {
                Spacer(Modifier.height(16.dp))
                Text(error, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
            }
        }
    }
}
