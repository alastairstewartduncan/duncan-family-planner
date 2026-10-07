package uk.co.duncan.familyplanner

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarViewWeek
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.Today
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import uk.co.duncan.familyplanner.ui.LocalSnackbar
import uk.co.duncan.familyplanner.ui.Session
import uk.co.duncan.familyplanner.ui.SessionViewModel
import uk.co.duncan.familyplanner.ui.screens.DayScreen
import uk.co.duncan.familyplanner.ui.screens.DefaultsScreen
import uk.co.duncan.familyplanner.ui.screens.EditDayScreen
import uk.co.duncan.familyplanner.ui.screens.RecurringScreen
import uk.co.duncan.familyplanner.ui.screens.SettingsScreen
import uk.co.duncan.familyplanner.ui.screens.ShoppingScreen
import uk.co.duncan.familyplanner.ui.screens.SignInScreen
import uk.co.duncan.familyplanner.ui.screens.WeekScreen
import uk.co.duncan.familyplanner.ui.theme.FamilyPlannerTheme
import uk.co.duncan.familyplanner.widget.WidgetUpdater
import java.time.LocalDate

class MainActivity : ComponentActivity() {
    /** Date requested by a notification or widget tap, consumed by the UI. */
    private var openDate by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        openDate = intent?.getStringExtra(EXTRA_DATE)
        setContent {
            FamilyPlannerTheme {
                App(openDate) { openDate = null }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        openDate = intent.getStringExtra(EXTRA_DATE)
    }

    companion object {
        const val EXTRA_DATE = "date"
    }
}

private enum class Tab(val route: String, val label: String, val icon: ImageVector) {
    Today("day", "Today", Icons.Default.Today),
    Week("week", "Week", Icons.Default.CalendarViewWeek),
    Shopping("shopping", "Shopping", Icons.Default.ShoppingCart),
    More("more", "More", Icons.Default.MoreHoriz),
}

@Composable
private fun App(openDate: String?, onOpenDateHandled: () -> Unit) {
    val vm: SessionViewModel = viewModel()
    val session by vm.session.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val activity = androidx.compose.ui.platform.LocalContext.current as ComponentActivity

    when (val s = session) {
        Session.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        Session.SignedOut -> SignInScreen(busy, error, null, { vm.signIn(activity) }, {}, {})
        is Session.NeedsJoin -> SignInScreen(busy, error, s.email, {}, { vm.join() }, { vm.signOut() })
        is Session.Ready -> {
            NotificationPermission()
            LaunchedEffect(s.familyId) { WidgetUpdater.refresh(activity) }
            Home(s, openDate, onOpenDateHandled, onSignOut = {
                vm.signOut()
                WidgetUpdater.refreshAsync(activity)
            })
        }
    }
}

@Composable
private fun NotificationPermission() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
    val context = androidx.compose.ui.platform.LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(Unit) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}

@Composable
private fun Home(session: Session.Ready, openDate: String?, onOpenDateHandled: () -> Unit, onSignOut: () -> Unit) {
    val nav = rememberNavController()
    val snackbar = remember { SnackbarHostState() }
    val backStack by nav.currentBackStackEntryAsState()
    val route = backStack?.destination?.route.orEmpty()
    val topLevel = Tab.entries.any { route.startsWith(it.route) && !route.startsWith("edit") }

    LaunchedEffect(openDate) {
        if (openDate != null) {
            nav.navigate("day?date=$openDate") { launchSingleTop = true }
            onOpenDateHandled()
        }
    }

    CompositionLocalProvider(LocalSnackbar provides snackbar) {
        Scaffold(
            snackbarHost = { SnackbarHost(snackbar) },
            bottomBar = {
                if (topLevel) NavigationBar {
                    Tab.entries.forEach { tab ->
                        NavigationBarItem(
                            selected = route.startsWith(tab.route),
                            onClick = { nav.goTo(tab) },
                            icon = { Icon(tab.icon, null) },
                            label = { Text(tab.label) },
                        )
                    }
                }
            },
        ) { padding ->
            NavHost(nav, startDestination = "day?date={date}", modifier = Modifier.padding(bottom = padding.calculateBottomPadding())) {
                composable(
                    "day?date={date}",
                    arguments = listOf(navArgument("date") { type = NavType.StringType; nullable = true; defaultValue = null }),
                ) { entry ->
                    val arg = entry.arguments?.getString("date")
                    var date by remember(arg) { mutableStateOf(arg?.let { LocalDate.parse(it) } ?: LocalDate.now()) }
                    DayScreen(session, date, onDateChange = { date = it }, onEdit = { nav.navigate("edit/$it") })
                }
                composable("edit/{date}") { entry ->
                    val date = entry.arguments?.getString("date")?.let { LocalDate.parse(it) } ?: LocalDate.now()
                    EditDayScreen(session, date, onDone = { nav.popBackStack() })
                }
                composable("week") {
                    WeekScreen(session, onOpenDay = { nav.navigate("day?date=$it") })
                }
                composable("shopping") { ShoppingScreen(session) }
                composable("more") {
                    SettingsScreen(
                        session,
                        onOpenRecurring = { nav.navigate("recurring") },
                        onOpenDefaults = { nav.navigate("defaults") },
                        onSignOut = onSignOut,
                    )
                }
                composable("recurring") { RecurringScreen(session, onBack = { nav.popBackStack() }) }
                composable("defaults") { DefaultsScreen(session, onBack = { nav.popBackStack() }) }
            }
        }
    }
}

private fun NavHostController.goTo(tab: Tab) {
    val target = if (tab == Tab.Today) "day?date=${LocalDate.now()}" else tab.route
    navigate(target) {
        popUpTo(graph.findStartDestination().id) { saveState = tab != Tab.Today }
        launchSingleTop = true
        restoreState = tab != Tab.Today
    }
}
