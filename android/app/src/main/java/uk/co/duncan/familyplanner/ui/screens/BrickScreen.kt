package uk.co.duncan.familyplanner.ui.screens

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Emergency
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Nfc
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uk.co.duncan.familyplanner.brick.BrickEngine
import uk.co.duncan.familyplanner.brick.BrickSetup
import uk.co.duncan.familyplanner.brick.BrickTags
import uk.co.duncan.familyplanner.brick.LockReason
import uk.co.duncan.familyplanner.brick.LockState
import uk.co.duncan.familyplanner.brick.human
import uk.co.duncan.familyplanner.data.ApiException
import uk.co.duncan.familyplanner.data.BrickSchedule
import uk.co.duncan.familyplanner.data.BrickSnapshot
import uk.co.duncan.familyplanner.data.BrickTagInfo
import uk.co.duncan.familyplanner.data.Family
import uk.co.duncan.familyplanner.data.Person
import uk.co.duncan.familyplanner.data.Repository
import uk.co.duncan.familyplanner.data.TagKind
import uk.co.duncan.familyplanner.data.WEEKDAY_SHORT
import uk.co.duncan.familyplanner.ui.LocalSnackbar
import uk.co.duncan.familyplanner.ui.PeopleMultiPicker
import uk.co.duncan.familyplanner.ui.PersonAvatar
import uk.co.duncan.familyplanner.ui.SectionLabel
import uk.co.duncan.familyplanner.ui.Session
import uk.co.duncan.familyplanner.ui.TimePickerDialog
import uk.co.duncan.familyplanner.ui.safeLaunch
import uk.co.duncan.familyplanner.ui.theme.parseColour
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

/** Brick: phone-free time for the family. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun BrickScreen(session: Session.Ready) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = LocalSnackbar.current
    val snap by remember { Repository.brickFlow() }.collectAsStateWithLifecycle(initialValue = Repository.cachedBrick())
    val lock by BrickEngine.state.collectAsStateWithLifecycle()
    val data = snap ?: BrickSnapshot()
    val family = session.family
    val me = session.personId
    val guardian = data.isGuardian

    // Re-check permissions every time the screen comes back from Settings.
    var resumes by remember { mutableIntStateOf(0) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) resumes++ }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    LaunchedEffect(snap, resumes) { BrickEngine.refresh(context) }

    var pairing by remember { mutableStateOf<TagKind?>(null) }
    var choosingApps by remember { mutableStateOf(false) }
    var askingParentPin by remember { mutableStateOf(false) }
    var settingParentPin by remember { mutableStateOf(false) }
    // Parents change the essential apps freely; everyone else needs a parent to enter the parent PIN first.
    val requestApps = {
        if (BrickEngine.appsManaged) {
            // A parent chooses these from the family server PC, so there is nothing to change here.
        } else if (guardian) {
            choosingApps = true
        } else {
            askingParentPin = true
        }
    }
    var editingSchedule by remember { mutableStateOf<BrickSchedule?>(null) }
    var passFor by remember { mutableStateOf<Person?>(null) }
    var renaming by remember { mutableStateOf<BrickTagInfo?>(null) }
    var confirmDelete by remember { mutableStateOf<BrickTagInfo?>(null) }
    var startingFocus by remember { mutableStateOf(false) }
    var editingParents by remember { mutableStateOf(false) }

    Scaffold(topBar = { TopAppBar(title = { Text("Brick", style = MaterialTheme.typography.titleLarge) }) }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            StatusHero(lock, data, me, hasBricks = data.bricks.isNotEmpty(), onBrickNow = {
                if (!BrickSetup.serviceEnabled(context)) {
                    scope.launch { snackbar.showSnackbar("Switch on the app blocker first (see the checklist below)") }
                } else if (BrickEngine.brickNow(context)) {
                    context.startActivity(android.content.Intent(context, uk.co.duncan.familyplanner.brick.BlockActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            })

            SetupChecklist(resumes, data, onChooseApps = requestApps)

            // ---- My phone-free log ----
            SessionLog(data.mySessions, lock)

            // ---- The family right now ----
            SectionLabel("The family", Modifier.padding(top = 8.dp))
            family.people.forEach { p ->
                PersonRow(p, data, isMe = p.id == me, guardian = guardian, onPass = { passFor = p })
            }

            // ---- Family Focus ----
            SectionLabel("Family Focus", Modifier.padding(top = 8.dp))
            val focus = data.focus
            if (focus != null) {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Groups, null)
                            Spacer(Modifier.width(10.dp))
                            Text("Family Focus is on", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        }
                        Text(
                            (focus.until?.let { "Until ${BrickEngine.formatTime(it)}" } ?: "Until a parent ends it") +
                                " · " + focus.personIds.mapNotNull { family.person(it)?.name }.joinToString(", "),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        if (guardian) {
                            OutlinedButton(onClick = { scope.safeLaunch(snackbar, "Family Focus ended") { Repository.endFamilyFocus() } }) {
                                Text("End for everyone")
                            }
                        }
                    }
                }
            } else {
                Text(
                    "Everyone's phone bricks at once — for tea, an evening in or a day out. Only a parent can end it early.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (guardian) {
                    FilledTonalButton(onClick = { startingFocus = true }) {
                        Icon(Icons.Default.Groups, null)
                        Spacer(Modifier.width(8.dp))
                        Text("Start Family Focus")
                    }
                }
            }

            // ---- Schedules ----
            SectionLabel("Automatic phone-free times", Modifier.padding(top = 8.dp))
            if (data.schedules.isEmpty()) {
                Text(
                    "For example school nights from 21:30 to 07:00. Phones lock and unlock on their own, even with no signal.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            data.schedules.forEach { s ->
                ScheduleRow(s, family, guardian,
                    onEdit = { editingSchedule = s },
                    onToggle = { on ->
                        scope.safeLaunch(snackbar) { Repository.saveBrickSchedules(data.schedules.map { if (it.id == s.id) it.copy(active = on) else it }) }
                    })
            }
            if (guardian) {
                TextButton(onClick = { editingSchedule = BrickSchedule(personIds = family.people.filter { it.id !in data.settings.guardians }.map { it.id }) }) {
                    Icon(Icons.Default.Add, null)
                    Spacer(Modifier.width(6.dp))
                    Text("Add a phone-free time")
                }
            }

            // ---- My essential apps ----
            SectionLabel("Your essential apps", Modifier.padding(top = 8.dp))
            EssentialApps(locked = lock.locked, guardian = guardian, onChoose = requestApps)

            // ---- Bricks ----
            SectionLabel("Bricks", Modifier.padding(top = 8.dp))
            Text(
                "NFC tags that lock and unlock phones. Stick one somewhere you'd have to get up to reach — the kitchen, the hall.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            data.bricks.forEach { t ->
                TagRow(t, family, guardian, onRename = { renaming = t }, onDelete = { confirmDelete = t })
            }
            if (guardian) {
                TextButton(onClick = { pairing = TagKind.Brick }) {
                    Icon(Icons.Default.Nfc, null)
                    Spacer(Modifier.width(6.dp))
                    Text("Add a Brick")
                }
            }

            // ---- Emergency ----
            SectionLabel("Emergency tag", Modifier.padding(top = 8.dp))
            EmergencyCard(data, family, guardian,
                onSeal = { pairing = TagKind.Emergency },
                onRearm = { id -> scope.safeLaunch(snackbar, "Emergency tag re-armed") { Repository.rearmBrickTag(id) } })

            // ---- Parents ----
            if (guardian) {
                SectionLabel("Parents", Modifier.padding(top = 8.dp))
                val names = data.settings.guardians.mapNotNull { family.person(it)?.name }
                Text(
                    if (names.isEmpty()) "No parents chosen yet, so anyone can change the rules. Choose who's in charge first."
                    else "${names.joinToString(" and ")} can change the rules, give passes and end Family Focus. " +
                        "The emergency tag unlocks for ${data.settings.emergencyMinutes} min.",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (names.isEmpty()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(onClick = { editingParents = true }) { Text("Parents & emergency settings") }
                Text(
                    if (data.hasParentPin) "A parent PIN is set. Anyone who isn't a parent needs it to change their essential apps."
                    else "No parent PIN yet, so only parents can change essential apps. Set one so you can unlock it on a child's phone when needed.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(onClick = { settingParentPin = true }) { Text(if (data.hasParentPin) "Change parent PIN" else "Set parent PIN") }
            }

            // ---- Recent activity ----
            if (data.events.isNotEmpty()) {
                SectionLabel("Recent", Modifier.padding(top = 8.dp))
                data.events.take(12).forEach { e ->
                    val who = family.person(e.personId)?.name ?: e.personId
                    Text(
                        "${e.at?.let { BrickEngine.formatTime(it) } ?: ""}  ·  $who ${describe(e.type, e.detail)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (e.type in listOf("emergency", "tamper")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(32.dp))
        }
    }

    // ---- Dialogs ----
    pairing?.let { kind ->
        PairTagDialog(
            kind = kind,
            replacing = kind == TagKind.Emergency && data.emergencyTag != null,
            onDismiss = { pairing = null },
            onDone = { pairing = null; scope.launch { snackbar.showSnackbar(if (kind == TagKind.Emergency) "Emergency tag sealed" else "Brick added") } },
        )
    }
    if (choosingApps) AppPickerDialog(onDismiss = { choosingApps = false })
    if (askingParentPin) {
        ParentPinDialog(
            hasParentPin = data.hasParentPin,
            onDismiss = { askingParentPin = false },
            onVerified = { askingParentPin = false; choosingApps = true },
        )
    }
    if (settingParentPin) {
        SetParentPinDialog(
            onDismiss = { settingParentPin = false },
            onSave = { pin ->
                settingParentPin = false
                scope.safeLaunch(snackbar, "Parent PIN saved") { Repository.setBrickParentPin(pin) }
            },
        )
    }
    editingSchedule?.let { s ->
        ScheduleDialog(
            initial = s, people = family.people,
            onDismiss = { editingSchedule = null },
            onSave = { saved ->
                editingSchedule = null
                val list = if (saved.id.isNotBlank() && data.schedules.any { it.id == saved.id }) data.schedules.map { if (it.id == saved.id) saved else it }
                else data.schedules + saved
                scope.safeLaunch(snackbar, "Saved") { Repository.saveBrickSchedules(list) }
            },
            onDelete = if (s.id.isBlank()) null else ({
                editingSchedule = null
                scope.safeLaunch(snackbar, "Removed") { Repository.saveBrickSchedules(data.schedules.filterNot { it.id == s.id }) }
            }),
        )
    }
    passFor?.let { p ->
        val current = data.passes[p.id]
        ChoiceDialog(
            title = "Give ${p.name} a pass",
            body = current?.let { "${p.name} already has a pass until ${BrickEngine.formatTime(it.until)}." }
                ?: "Unlocks ${p.name}'s phone for a while, even during Family Focus or a phone-free time.",
            options = listOf("15 minutes" to 15, "30 minutes" to 30, "1 hour" to 60, "2 hours" to 120) +
                (if (current != null) listOf("Cancel the pass" to 0) else emptyList()),
            onDismiss = { passFor = null },
            onPick = { mins ->
                passFor = null
                scope.safeLaunch(snackbar, if (mins == 0) "Pass cancelled" else "${p.name} has a pass") { Repository.grantBrickPass(p.id, mins) }
            },
        )
    }
    if (startingFocus) {
        ChoiceDialog(
            title = "Start Family Focus",
            body = "Every phone bricks within about 30 seconds. Calls, messages and essentials still work.",
            options = listOf("30 minutes" to 30, "1 hour" to 60, "2 hours" to 120, "Until I end it" to -1),
            onDismiss = { startingFocus = false },
            onPick = { mins ->
                startingFocus = false
                scope.safeLaunch(snackbar, "Family Focus started") {
                    Repository.startFamilyFocus(if (mins < 0) null else mins, family.people.map { it.id })
                }
            },
        )
    }
    renaming?.let { t ->
        var name by remember(t.id) { mutableStateOf(t.name) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("Rename") },
            text = { OutlinedTextField(name, { name = it.take(40) }, label = { Text("Where is it?") }, singleLine = true) },
            confirmButton = {
                TextButton(enabled = name.isNotBlank(), onClick = {
                    renaming = null
                    scope.safeLaunch(snackbar, "Renamed") { Repository.renameBrickTag(t.id, name) }
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text("Cancel") } },
        )
    }
    confirmDelete?.let { t ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Remove ${t.name}?") },
            text = { Text("It will stop locking and unlocking phones. You can register the same tag again later.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = null
                    scope.safeLaunch(snackbar, "Removed") { Repository.deleteBrickTag(t.id) }
                }) { Text("Remove", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Cancel") } },
        )
    }
    if (editingParents) {
        ParentsDialog(data, family.people, onDismiss = { editingParents = false }, onSave = { guardians, minutes, rearm ->
            editingParents = false
            scope.safeLaunch(snackbar, "Saved") {
                Repository.saveBrickSettings(mapOf("guardians" to guardians, "emergencyMinutes" to minutes, "rearmAfterEmergency" to rearm))
            }
        })
    }
}

private fun describe(type: String, detail: String) = when (type) {
    "brick" -> "bricked their phone"
    "unbrick" -> "unbricked" + if (detail.isNotBlank()) " ($detail)" else ""
    "emergency" -> "used the EMERGENCY tag"
    "denied" -> "tried to unlock" + if (detail.isNotBlank()) " during $detail" else ""
    "tamper" -> "switched their app blocker OFF"
    "focus" -> "started Family Focus"
    "focus-ended" -> "ended Family Focus"
    "pass" -> "gave a pass ($detail)"
    "pass-cancelled" -> "cancelled a pass"
    "tag-added" -> "added the Brick \"$detail\""
    "emergency-sealed" -> "sealed a new emergency tag"
    "rearmed" -> "re-armed the emergency tag"
    "parent-pin-set" -> "set a new parent PIN"
    "pin-wrong" -> "entered a wrong parent PIN" + if (detail.isNotBlank()) " ($detail)" else ""
    "apps-unlocked" -> "unlocked their essential apps with the parent PIN"
    "apps-managed" -> "had their essential apps set by a parent" + if (detail.isNotBlank()) " ($detail)" else ""
    else -> type
}

// ---- Hero ------------------------------------------------------------------------------

@Composable
private fun StatusHero(lock: LockState, data: BrickSnapshot, me: String?, hasBricks: Boolean, onBrickNow: () -> Unit) {
    val locked = lock.locked
    val bg by animateColorAsState(if (locked) Color(0xFF101614) else MaterialTheme.colorScheme.primaryContainer, label = "bg")
    val fg = if (locked) Color.White else MaterialTheme.colorScheme.onPrimaryContainer
    val accent = if (locked) Color(0xFF9DD1BC) else MaterialTheme.colorScheme.primary
    val now by produceState(Instant.now()) { while (true) { kotlinx.coroutines.delay(15_000); value = Instant.now() } }
    val stats = me?.let { data.stats[it] }

    Card(colors = CardDefaults.cardColors(containerColor = bg), shape = RoundedCornerShape(24.dp)) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(48.dp).clip(RoundedCornerShape(14.dp)).background(accent), contentAlignment = Alignment.Center) {
                    Icon(if (locked) Icons.Default.Lock else Icons.Default.LockOpen, null, tint = if (locked) Color(0xFF101614) else Color.White)
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        when {
                            lock.emergencyUntil != null -> "Emergency unlock"
                            lock.passUntil != null -> "You have a pass"
                            !locked -> "Unlocked"
                            lock.reason == LockReason.Focus -> "Family Focus"
                            lock.reason == LockReason.Schedule -> lock.label
                            else -> "Bricked"
                        },
                        style = MaterialTheme.typography.headlineSmall, color = fg,
                    )
                    val sub = when {
                        lock.emergencyUntil != null -> "Until ${BrickEngine.formatTime(lock.emergencyUntil)}"
                        lock.passUntil != null -> "Until ${BrickEngine.formatTime(lock.passUntil)}"
                        locked && lock.since != null -> "Phone-free for ${human(Duration.between(lock.since, now))}" +
                            (lock.until?.let { " · unlocks ${BrickEngine.formatTime(it)}" } ?: "")
                        !hasBricks -> "Add a Brick below so you can unlock again"
                        else -> BrickEngine.nextScheduled(data, me)?.let { (s, at) -> "${s.label} starts ${BrickEngine.formatTime(at.toInstant())}" }
                            ?: "Press Brick — scan a Brick to unlock"
                    }
                    Text(sub, style = MaterialTheme.typography.bodyMedium, color = fg.copy(alpha = 0.8f))
                }
            }
            if (!locked) {
                Button(
                    onClick = onBrickNow,
                    enabled = hasBricks,
                    modifier = Modifier.fillMaxWidth().height(56.dp).padding(top = 4.dp),
                    shape = RoundedCornerShape(16.dp),
                ) {
                    Icon(Icons.Default.Lock, null)
                    Spacer(Modifier.width(10.dp))
                    Text("Brick", style = MaterialTheme.typography.titleLarge)
                }
            } else if (lock.reason == LockReason.Manual) {
                Text("Scan a Brick to unlock", style = MaterialTheme.typography.bodyMedium, color = accent)
            }
            if (stats != null) {
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Stat("Today", minutes(stats.today), fg)
                    Stat("This week", minutes(stats.week), fg)
                    Stat("Streak", if (stats.streakDays == 1) "1 day" else "${stats.streakDays} days", fg)
                }
                WeekBars(stats.byDay.map { it.second }, accent, fg)
            }
        }
    }
}

private fun minutes(m: Int) = if (m < 60) "${m}m" else "${m / 60}h ${m % 60}m"

@Composable
private fun Stat(label: String, value: String, colour: Color) {
    Column {
        Text(value, style = MaterialTheme.typography.titleLarge, color = colour)
        Text(label.uppercase(), style = MaterialTheme.typography.labelSmall, color = colour.copy(alpha = 0.7f))
    }
}

/** Seven little bars: phone-free minutes for each of the last seven days. */
@Composable
private fun WeekBars(values: List<Int>, accent: Color, label: Color) {
    if (values.isEmpty()) return
    val max = (values.maxOrNull() ?: 0).coerceAtLeast(60)
    Row(Modifier.fillMaxWidth().height(56.dp).padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Bottom) {
        val start = LocalDate.now().minusDays((values.size - 1).toLong())
        values.forEachIndexed { i, v ->
            Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.Bottom, horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    Modifier.fillMaxWidth().fillMaxHeight((v.toFloat() / max).coerceIn(0.04f, 0.8f))
                        .clip(RoundedCornerShape(4.dp)).background(if (v >= 60) accent else accent.copy(alpha = 0.35f)),
                )
                Text(WEEKDAY_SHORT[start.plusDays(i.toLong()).dayOfWeek.value - 1].take(1), style = MaterialTheme.typography.labelSmall, color = label.copy(alpha = 0.6f))
            }
        }
    }
}

// ---- Setup checklist ------------------------------------------------------------------

@Composable
private fun SetupChecklist(resumes: Int, data: BrickSnapshot, onChooseApps: () -> Unit) {
    val context = LocalContext.current
    val nfc = remember(resumes) { BrickTags.nfcState(context) }
    val service = remember(resumes) { BrickSetup.serviceEnabled(context) }
    val admin = remember(resumes) { BrickSetup.adminActive(context) }
    val notifications = remember(resumes) {
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    }
    val apps = remember(resumes) { BrickEngine.allowedChosen }
    if (nfc == BrickTags.NfcState.On && service && admin && notifications && apps) return

    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Set up this phone", style = MaterialTheme.typography.titleMedium)
            if (nfc == BrickTags.NfcState.Missing) {
                Text("This phone has no NFC, so it can't use Bricks. A parent can still give passes and start Family Focus from here.",
                    style = MaterialTheme.typography.bodySmall)
            } else {
                Step("Turn on NFC", nfc == BrickTags.NfcState.On) { context.startActivity(BrickSetup.nfcSettings()) }
            }
            Step("Switch on the app blocker", service, help = if (!service && Build.VERSION.SDK_INT >= 33)
                "If it's greyed out: App info → ⋮ → Allow restricted settings, then try again." else null,
                secondary = if (!service && Build.VERSION.SDK_INT >= 33) ("App info" to { context.startActivity(BrickSetup.appInfo(context)) }) else null,
            ) { context.startActivity(BrickSetup.accessibilitySettings()) }
            Step("Protect against uninstalling", admin) { context.startActivity(BrickSetup.addAdmin(context)) }
            Step("Choose your essential apps", apps, onClick = onChooseApps)
            if (!notifications) Step("Allow notifications (for emergency alerts)", false) {
                context.startActivity(BrickSetup.appInfo(context))
            }
            if (data.bricks.isEmpty()) {
                Text("Then ask a parent to add a Brick.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun Step(
    title: String,
    done: Boolean,
    help: String? = null,
    secondary: Pair<String, () -> Unit>? = null,
    onClick: () -> Unit,
) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (done) Icons.Default.CheckCircle else Icons.Default.ErrorOutline, null,
                tint = if (done) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary,
            )
            Spacer(Modifier.width(10.dp))
            Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            if (!done) TextButton(onClick = onClick) { Text("Set up") }
        }
        if (help != null) Text(help, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 34.dp))
        if (secondary != null) TextButton(onClick = secondary.second, modifier = Modifier.padding(start = 26.dp)) { Text(secondary.first) }
    }
}

// ---- People --------------------------------------------------------------------------

@Composable
private fun PersonRow(p: Person, data: BrickSnapshot, isMe: Boolean, guardian: Boolean, onPass: () -> Unit) {
    val status = data.statuses[p.id]
    val pass = data.passes[p.id]
    val stale = status?.updatedAt?.let { Duration.between(it, Instant.now()).toMinutes() > 10 } ?: true
    val line = when {
        status == null -> "Not set up yet"
        !status.serviceOn -> "⚠ App blocker is off"
        pass != null -> "Pass until ${BrickEngine.formatTime(pass.until)}"
        status.locked -> when (status.reason) {
            "focus" -> "Family Focus"
            "schedule" -> "Phone-free time"
            else -> "Bricked"
        } + (status.since?.let { " · ${human(Duration.between(it, Instant.now()))}" } ?: "")
        else -> "Unlocked"
    } + if (status != null && stale) " · last seen ${status.updatedAt?.let { BrickEngine.formatTime(it) } ?: "?"}" else ""
    val week = data.stats[p.id]?.week ?: 0

    Row(
        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium)
            .then(if (guardian && !isMe) Modifier.clickable(onClick = onPass) else Modifier).padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box {
            PersonAvatar(p, 40)
            if (status?.locked == true) {
                Box(
                    Modifier.align(Alignment.BottomEnd).size(16.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surface),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Default.Lock, null, Modifier.size(12.dp), tint = parseColour(p.colour)) }
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(p.name + if (isMe) " (you)" else "", style = MaterialTheme.typography.bodyLarge)
            Text(
                line, style = MaterialTheme.typography.bodySmall,
                color = if (status != null && !status.serviceOn) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(minutes(week), style = MaterialTheme.typography.titleSmall)
            Text("this week", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (guardian && !isMe) IconButton(onClick = onPass) { Icon(Icons.Default.Timer, "Give a pass") }
    }
}

// ---- Schedules -----------------------------------------------------------------------

@Composable
private fun ScheduleRow(s: BrickSchedule, family: Family, guardian: Boolean, onEdit: () -> Unit, onToggle: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium)
            .then(if (guardian) Modifier.clickable(onClick = onEdit) else Modifier).padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Default.Schedule, null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(s.label, style = MaterialTheme.typography.bodyLarge)
            Text(
                "${s.start}–${s.end} · ${dayList(s.days)} · ${s.personIds.mapNotNull { family.person(it)?.name }.joinToString(", ")}",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = s.active, onCheckedChange = onToggle, enabled = guardian)
    }
}

private fun dayList(days: List<Int>): String = when (days.sorted()) {
    (1..7).toList() -> "Every day"
    listOf(1, 2, 3, 4, 5) -> "Mon–Fri"
    listOf(1, 2, 3, 4, 7) -> "School nights"
    listOf(6, 7) -> "Weekends"
    else -> days.sorted().joinToString(" ") { WEEKDAY_SHORT[it - 1] }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ScheduleDialog(
    initial: BrickSchedule,
    people: List<Person>,
    onDismiss: () -> Unit,
    onSave: (BrickSchedule) -> Unit,
    onDelete: (() -> Unit)?,
) {
    var s by remember(initial) { mutableStateOf(initial) }
    var picking by remember { mutableStateOf<String?>(null) } // "start" | "end"
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial.id.isBlank()) "New phone-free time" else "Edit ${initial.label}") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(s.label, { s = s.copy(label = it.take(40)) }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Text("Who", style = MaterialTheme.typography.labelLarge)
                PeopleMultiPicker(people, s.personIds) { s = s.copy(personIds = it) }
                Text("Starts on", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    WEEKDAY_SHORT.forEachIndexed { i, label ->
                        val d = i + 1
                        FilterChip(selected = d in s.days, onClick = {
                            s = s.copy(days = if (d in s.days) s.days - d else (s.days + d).sorted())
                        }, label = { Text(label) })
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = { picking = "start" }) { Text("From ${s.start}") }
                    OutlinedButton(onClick = { picking = "end" }) { Text("To ${s.end}") }
                }
                if (s.end < s.start) Text("Runs past midnight into the next morning.", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (onDelete != null) TextButton(onClick = onDelete) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(
                enabled = s.personIds.isNotEmpty() && s.days.isNotEmpty() && s.start != s.end && s.label.isNotBlank(),
                onClick = { onSave(s) },
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
    picking?.let { which ->
        TimePickerDialog(
            initial = if (which == "start") s.start else s.end,
            onDismiss = { picking = null },
            onPick = { t -> s = if (which == "start") s.copy(start = t) else s.copy(end = t); picking = null },
        )
    }
}

// ---- Essential apps ------------------------------------------------------------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EssentialApps(locked: Boolean, guardian: Boolean, onChoose: () -> Unit) {
    val context = LocalContext.current
    val chosen = remember(locked) { BrickEngine.allowedApps }
    val managed = BrickEngine.appsManaged
    Text(
        "Phone, messages and this planner always work. " +
            if (managed) "A parent chooses the other apps from the family server PC."
            else if (guardian) "Pick anything else you'd need while bricked — maps, the bus app, your camera."
            else "Changing the other apps needs a parent to enter the parent PIN.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (chosen.isNotEmpty()) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            chosen.map { BrickSetup.label(context, it) }.sorted().forEach { AssistChip(onClick = {}, label = { Text(it) }, enabled = false) }
        }
    }
    OutlinedButton(onClick = onChoose, enabled = !locked && !managed) {
        Icon(Icons.Default.Apps, null)
        Spacer(Modifier.width(6.dp))
        Text(if (managed) "Set by a parent" else if (locked) "Unlock to change" else if (guardian) "Choose apps" else "Parent: choose apps")
    }
}

/** A child's phone asks a parent to type the parent PIN, which the server checks, before the app picker opens. */
@Composable
private fun ParentPinDialog(hasParentPin: Boolean, onDismiss: () -> Unit, onVerified: () -> Unit) {
    val scope = rememberCoroutineScope()
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    fun submit() {
        if (busy || pin.length < 6) return
        busy = true
        error = null
        scope.launch {
            try {
                Repository.checkBrickParentPin(pin)
                onVerified()
            } catch (e: Exception) {
                error = (e as? ApiException)?.message ?: "Couldn't reach the family server. A parent PIN needs a connection."
                pin = ""
            } finally {
                busy = false
            }
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Parent PIN") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (!hasParentPin) {
                    Text("No parent PIN has been set yet. Ask a parent to set one on their phone (Brick → Parents), then try again.")
                } else {
                    Text("A parent needs to enter the parent PIN to change the essential apps.")
                    OutlinedTextField(
                        value = pin, onValueChange = { v -> pin = v.filter(Char::isDigit).take(8) },
                        label = { Text("Parent PIN") }, singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        isError = error != null,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                }
            }
        },
        confirmButton = {
            if (hasParentPin) TextButton(enabled = pin.length >= 6 && !busy, onClick = { submit() }) { Text("Unlock") }
            else TextButton(onClick = onDismiss) { Text("OK") }
        },
        dismissButton = { if (hasParentPin) TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** A parent chooses or changes the parent PIN. It has to be different from the family PIN the children know. */
@Composable
private fun SetParentPinDialog(onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var pin by remember { mutableStateOf("") }
    var again by remember { mutableStateOf("") }
    val ok = pin.length >= 6 && pin == again
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Parent PIN") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("6 to 8 digits, and different from the family PIN. Keep it to yourselves: it lets someone change essential apps on a child's phone.")
                OutlinedTextField(
                    value = pin, onValueChange = { v -> pin = v.filter(Char::isDigit).take(8) },
                    label = { Text("New parent PIN") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = again, onValueChange = { v -> again = v.filter(Char::isDigit).take(8) },
                    label = { Text("Type it again") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    isError = again.isNotEmpty() && pin != again,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = { TextButton(enabled = ok, onClick = { onSave(pin) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun AppPickerDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    var selected by remember { mutableStateOf(if (BrickEngine.allowedChosen) BrickEngine.allowedApps else BrickSetup.suggestedEssentials(context)) }
    val apps by produceState<List<Triple<String, String, ImageBitmap?>>?>(null) {
        value = withContext(Dispatchers.IO) {
            BrickSetup.launchableApps(context).map { a ->
                Triple(a.packageName, a.label, runCatching { a.icon?.toBitmap(72, 72)?.asImageBitmap() }.getOrNull())
            }
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Essential apps") },
        text = {
            val list = apps
            if (list == null) {
                Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            } else {
                LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    items(list, key = { it.first }) { (pkg, label, icon) ->
                        Row(
                            Modifier.fillMaxWidth().clickable { selected = if (pkg in selected) selected - pkg else selected + pkg }.padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(checked = pkg in selected, onCheckedChange = null)
                            Spacer(Modifier.width(8.dp))
                            if (icon != null) Image(icon, null, Modifier.size(28.dp)) else Spacer(Modifier.size(28.dp))
                            Spacer(Modifier.width(10.dp))
                            Text(label, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                BrickEngine.allowedApps = selected
                onDismiss()
            }) { Text("Save (${selected.size})") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

// ---- Tags ------------------------------------------------------------------------------

@Composable
private fun TagRow(t: BrickTagInfo, family: Family, guardian: Boolean, onRename: () -> Unit, onDelete: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.primary), contentAlignment = Alignment.Center) {
            Icon(Icons.Default.Nfc, null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(t.name, style = MaterialTheme.typography.bodyLarge)
            Text(
                t.lastUsedAt?.let { "Last used ${BrickEngine.formatTime(it)}" + (family.person(t.lastUsedBy)?.let { p -> " by ${p.name}" } ?: "") } ?: "Not used yet",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (guardian) {
            IconButton(onClick = onRename) { Icon(Icons.Default.Edit, "Rename") }
            IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, "Remove") }
        }
    }
}

@Composable
private fun EmergencyCard(data: BrickSnapshot, family: Family, guardian: Boolean, onSeal: () -> Unit, onRearm: (String) -> Unit) {
    val tag = data.emergencyTag
    val red = MaterialTheme.colorScheme.error
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Emergency, null, tint = red)
                Spacer(Modifier.width(10.dp))
                Text(
                    when {
                        tag == null -> "No emergency tag yet"
                        tag.spent -> "Used — needs re-arming"
                        else -> "Sealed and ready"
                    },
                    style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                    color = if (tag?.spent == true) red else MaterialTheme.colorScheme.onSurface,
                )
            }
            Text(
                "There is only ever one. It unlocks any phone straight away for ${data.settings.emergencyMinutes} minutes, " +
                    "even during Family Focus, and tells everyone it was used. Keep it somewhere sealed — an envelope in a drawer.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val usedAt = tag?.lastUsedAt
            if (tag != null && usedAt != null) {
                Text(
                    "Last used ${BrickEngine.formatTime(usedAt)} by ${family.person(tag.lastUsedBy)?.name ?: "someone"}",
                    style = MaterialTheme.typography.bodySmall, color = if (tag.spent) red else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (guardian) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (tag != null && tag.spent) {
                        Button(onClick = { onRearm(tag.id) }) {
                            Icon(Icons.Default.Refresh, null)
                            Spacer(Modifier.width(6.dp))
                            Text("Re-arm")
                        }
                    }
                    OutlinedButton(onClick = onSeal) { Text(if (tag == null) "Seal an emergency tag" else "Replace it") }
                }
            }
        }
    }
}

/**
 * Writes a fresh identity onto a blank tag and registers it. Uses reader mode,
 * so while this is open the phone talks only to the tag being set up.
 */
@Composable
private fun PairTagDialog(kind: TagKind, replacing: Boolean, onDismiss: () -> Unit, onDone: () -> Unit) {
    val context = LocalContext.current
    val activity = context as? android.app.Activity
    val scope = rememberCoroutineScope()
    val emergency = kind == TagKind.Emergency
    var name by remember { mutableStateOf(if (emergency) "Emergency" else "Kitchen") }
    var lockTag by remember { mutableStateOf(true) }
    var confirmedReplace by remember { mutableStateOf(!replacing) }
    var waiting by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    if (waiting && activity != null) {
        DisposableEffect(Unit) {
            val ok = BrickTags.enableReader(activity) { tag ->
                if (busy) return@enableReader
                busy = true
                // Already off the main thread: reader mode calls back on its own thread.
                val result = runCatching { BrickTags.write(tag, lockTag) }
                scope.launch {
                    result.onSuccess { w ->
                        try {
                            Repository.addBrickTag(w.tagId, kind, name.trim(), w.hash, replace = replacing)
                            Repository.loadBrick()
                            onDone()
                        } catch (e: ApiException) {
                            error = e.message; busy = false
                        } catch (e: Exception) {
                            error = "Couldn't reach home to register it: ${e.message}"; busy = false
                        }
                    }.onFailure { e ->
                        error = e.message ?: "Couldn't write to that tag. Hold it still and try again."; busy = false
                    }
                }
            }
            if (!ok) error = "Turn on NFC first."
            onDispose { BrickTags.disableReader(activity) }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(if (emergency) Icons.Default.Emergency else Icons.Default.Nfc, null) },
        title = { Text(if (emergency) (if (replacing) "Replace the emergency tag" else "Seal an emergency tag") else "Add a Brick") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                when {
                    !confirmedReplace -> Text(
                        "There's already an emergency tag. Setting up a new one retires the old one for good — it will stop working on every phone.",
                    )
                    !waiting -> {
                        if (!emergency) {
                            OutlinedTextField(name, { name = it.take(40) }, label = { Text("Where will it live?") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Lock the tag", style = MaterialTheme.typography.bodyLarge)
                                Text("Permanent. Stops anyone rewriting or copying it with another app. Recommended.",
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Switch(lockTag, { lockTag = it })
                        }
                        Text("Use a blank NTAG213, 215 or 216 sticker, card or key fob.", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    else -> Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                        if (busy) CircularProgressIndicator() else Icon(Icons.Default.Nfc, null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.height(12.dp))
                        Text(if (busy) "Writing… keep it still" else "Hold the tag flat against the back of your phone")
                    }
                }
                AnimatedVisibility(error != null) { Text(error.orEmpty(), color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            when {
                !confirmedReplace -> TextButton(onClick = { confirmedReplace = true }) { Text("Replace", color = MaterialTheme.colorScheme.error) }
                !waiting -> TextButton(enabled = name.isNotBlank(), onClick = { error = null; waiting = true }) { Text("Next") }
                else -> {}
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

// ---- Small dialogs ---------------------------------------------------------------------

@Composable
private fun ChoiceDialog(title: String, body: String, options: List<Pair<String, Int>>, onDismiss: () -> Unit, onPick: (Int) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(body, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(8.dp))
                options.forEach { (label, value) ->
                    HorizontalDivider()
                    Text(
                        label,
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (value == 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.fillMaxWidth().clickable { onPick(value) }.padding(vertical = 12.dp),
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun ParentsDialog(data: BrickSnapshot, people: List<Person>, onDismiss: () -> Unit, onSave: (List<String>, Int, Boolean) -> Unit) {
    var guardians by remember { mutableStateOf(data.settings.guardians.ifEmpty { people.map { it.id } }) }
    var minutes by remember { mutableIntStateOf(data.settings.emergencyMinutes) }
    var rearm by remember { mutableStateOf(data.settings.rearmAfterEmergency) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Parents & emergency") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Who's in charge?", style = MaterialTheme.typography.labelLarge)
                PeopleMultiPicker(people, guardians) { guardians = it }
                Text("Emergency tag unlocks for", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(15, 30, 60, 120).forEach { m ->
                        FilterChip(selected = minutes == m, onClick = { minutes = m }, label = { Text(if (m < 60) "$m min" else "${m / 60} h") })
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("One use, then re-arm", style = MaterialTheme.typography.bodyLarge)
                        Text("After it's used, a parent re-arms it before it works again.", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(rearm, { rearm = it })
                }
            }
        },
        confirmButton = { TextButton(enabled = guardians.isNotEmpty(), onClick = { onSave(guardians, minutes, rearm) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Suppress("unused")
private fun openApp(context: android.content.Context, pkg: String) =
    context.packageManager.getLaunchIntentForPackage(pkg)?.let { context.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }

// ---- Usage log ---------------------------------------------------------------------------

@Composable
private fun SessionLog(sessions: List<uk.co.duncan.familyplanner.data.BrickSession>, lock: LockState) {
    val zone = java.time.ZoneId.systemDefault()
    val today = LocalDate.now(zone)
    val current = if (lock.locked && lock.since != null) lock.since else null
    if (sessions.isEmpty() && current == null) return
    SectionLabel("Your phone-free time", Modifier.padding(top = 8.dp))
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
            fun dayName(d: LocalDate) = when (d) {
                today -> "Today"
                today.minusDays(1) -> "Yesterday"
                else -> d.format(java.time.format.DateTimeFormatter.ofPattern("EEE d MMM", java.util.Locale.UK))
            }
            fun hm(i: Instant) = i.atZone(zone).let { "%02d:%02d".format(it.hour, it.minute) }
            if (current != null) {
                LogRow("Now", "Since ${hm(current)}", "${human(Duration.between(current, Instant.now()))} so far", live = true)
            }
            var lastDay: LocalDate? = null
            sessions.take(20).forEach { s ->
                val d = s.end.atZone(zone).toLocalDate()
                LogRow(if (d != lastDay) dayName(d) else "", "${hm(s.start)}–${hm(s.end)}", human(Duration.ofMinutes(s.minutes.toLong())))
                lastDay = d
            }
        }
    }
}

@Composable
private fun LogRow(day: String, time: String, length: String, live: Boolean = false) {
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(day, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.width(78.dp))
        Text(time, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(
            length, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold,
            color = if (live) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurface,
        )
    }
}
