package com.marcogn.pdftoolkit.ui.navigation

import android.net.Uri
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.Lifecycle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.marcogn.pdftoolkit.R
import com.marcogn.pdftoolkit.domain.model.PdfTool
import com.marcogn.pdftoolkit.ui.about.AboutScreen
import com.marcogn.pdftoolkit.ui.common.PlaceholderScreen
import com.marcogn.pdftoolkit.ui.home.HomeScreen
import com.marcogn.pdftoolkit.ui.home.labelRes
import com.marcogn.pdftoolkit.ui.recents.RecentsScreen
import com.marcogn.pdftoolkit.ui.recents.RecentsViewModel
import com.marcogn.pdftoolkit.ui.settings.SettingsScreen
import com.marcogn.pdftoolkit.ui.viewer.ViewerScreen
import com.marcogn.pdftoolkit.ui.viewer.rememberOpenPdfLauncher
import com.marcogn.pdftoolkit.ui.viewer.takePersistableReadPermission
import kotlinx.coroutines.launch

// A NavBackStackEntry reaches RESUMED only once its transition has finished: every
// navigate()/popBackStack() goes through this check on the entry that owns the callback, so a
// fast double tap can't land on a screen that is still mid-transition. Same fix as the
// reference projects.
private fun NavBackStackEntry.lifecycleIsResumed() = lifecycle.currentState == Lifecycle.State.RESUMED

// Spec §9: 200–250 ms transitions, never above 300. The reference projects use a 300 ms
// full-width slide; here a short slide plus fade, which feels lighter.
private const val NAV_ENTER_MS = 250
private const val NAV_EXIT_MS = 200
private const val SLIDE_FRACTION = 12

private val navEnterTransition: AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition = {
    slideInHorizontally(tween(NAV_ENTER_MS, easing = FastOutSlowInEasing)) { it / SLIDE_FRACTION } +
        fadeIn(tween(NAV_ENTER_MS))
}
private val navExitTransition: AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition = {
    fadeOut(tween(NAV_EXIT_MS))
}
private val navPopEnterTransition: AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition = {
    fadeIn(tween(NAV_ENTER_MS))
}
private val navPopExitTransition: AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition = {
    slideOutHorizontally(tween(NAV_EXIT_MS, easing = FastOutSlowInEasing)) { it / SLIDE_FRACTION } +
        fadeOut(tween(NAV_EXIT_MS))
}

private val drawerDestinations = listOf(
    Destination.Home,
    Destination.Recents,
    Destination.Signatures,
    Destination.Settings,
    Destination.About,
)

private fun NavDestination.asDrawerDestination(): Destination? = drawerDestinations.firstOrNull { candidate ->
    when (candidate) {
        Destination.Home -> hasRoute<Destination.Home>()
        Destination.Recents -> hasRoute<Destination.Recents>()
        Destination.Signatures -> hasRoute<Destination.Signatures>()
        Destination.Settings -> hasRoute<Destination.Settings>()
        Destination.About -> hasRoute<Destination.About>()
        else -> false
    }
}

/** Navigation graph wrapped in a drawer that is always reachable (hamburger on the left), spec §4. */
@Composable
fun PdfToolkitNavGraph(
    startUri: Uri? = null,
    navController: NavHostController = rememberNavController(),
) {
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDrawerDestination = backStackEntry?.destination?.asDrawerDestination()
    // In the viewer a horizontal drag pans the page: the drawer opens only from its button.
    val drawerGesturesEnabled = backStackEntry?.destination?.hasRoute<Destination.Viewer>() != true

    val openDrawer: () -> Unit = { scope.launch { drawerState.open() } }
    val navigateFromDrawer: (Destination) -> Unit = { destination ->
        if (navController.currentBackStackEntry?.lifecycleIsResumed() != false) {
            scope.launch { drawerState.close() }
            if (destination == Destination.Home) {
                // As in KartLog: popUpTo(Home){saveState} + restoreState towards Home itself
                // doesn't restore reliably, so Home is always rebuilt from scratch.
                navController.navigate(Destination.Home) {
                    popUpTo<Destination.Home> { inclusive = true }
                    launchSingleTop = true
                }
            } else {
                navController.navigate(destination) {
                    popUpTo(Destination.Home) { saveState = true }
                    launchSingleTop = true
                    restoreState = true
                }
            }
        }
    }

    val context = LocalContext.current
    // PDF handed over by another app (VIEW / SEND): opened on top of Home, so back goes to Home.
    LaunchedEffect(startUri) {
        if (startUri != null) {
            context.takePersistableReadPermission(startUri)
            navController.navigate(Destination.Viewer(startUri.toString()))
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = drawerGesturesEnabled,
        drawerContent = { AppDrawerSheet(current = currentDrawerDestination, onNavigate = navigateFromDrawer) },
    ) {
        NavHost(
            navController = navController,
            startDestination = Destination.Home,
            enterTransition = navEnterTransition,
            exitTransition = navExitTransition,
            popEnterTransition = navPopEnterTransition,
            popExitTransition = navPopExitTransition,
            // System back (button or gesture) uses these instead of the pop transitions above, and
            // Navigation's defaults scale the screen down to 70% towards the centre: back would look
            // different from the toolbar arrow. Same transitions for both, driven by the gesture.
            predictivePopEnterTransition = { navPopEnterTransition() },
            predictivePopExitTransition = { navPopExitTransition() },
        ) {
            composable<Destination.Home> { entry ->
                val recentsViewModel: RecentsViewModel = hiltViewModel()
                val recents by recentsViewModel.recents.collectAsStateWithLifecycle()
                // The picker result arrives before the entry is RESUMED again, so this navigate()
                // can't go through the guard; it isn't a tap, so no double-tap risk. The tap that
                // opens the picker is guarded.
                val openPdf = rememberOpenPdfLauncher { uri -> navController.navigate(Destination.Viewer(uri.toString())) }
                HomeScreen(
                    onMenuClick = openDrawer,
                    onOpenPdfClick = { if (entry.lifecycleIsResumed()) openPdf() },
                    recents = recents,
                    onRecentClick = { item ->
                        if (entry.lifecycleIsResumed()) navController.navigate(Destination.Viewer(item.document.uri))
                    },
                    onRecentRemove = { recentsViewModel.remove(it.document.uri) },
                    onToolClick = { tool ->
                        if (tool == PdfTool.MY_SIGNATURES) {
                            // Same screen as the drawer entry, so same navigation: this way
                            // two copies never pile up on the back stack.
                            navigateFromDrawer(Destination.Signatures)
                        } else if (entry.lifecycleIsResumed()) {
                            navController.navigate(Destination.Tool(tool.name))
                        }
                    },
                )
            }
            composable<Destination.Viewer> { entry ->
                ViewerScreen(onBack = { if (entry.lifecycleIsResumed()) navController.popBackStack() })
            }
            composable<Destination.Tool> { entry ->
                val tool = PdfTool.valueOf(entry.toRoute<Destination.Tool>().tool)
                PlaceholderScreen(
                    title = stringResource(tool.labelRes()),
                    onBack = { if (entry.lifecycleIsResumed()) navController.popBackStack() },
                )
            }
            composable<Destination.Recents> { entry ->
                val recentsViewModel: RecentsViewModel = hiltViewModel()
                val recents by recentsViewModel.recents.collectAsStateWithLifecycle()
                RecentsScreen(
                    recents = recents,
                    onMenuClick = openDrawer,
                    onRecentClick = { item ->
                        if (entry.lifecycleIsResumed()) navController.navigate(Destination.Viewer(item.document.uri))
                    },
                    onRecentRemove = { recentsViewModel.remove(it.document.uri) },
                )
            }
            composable<Destination.Signatures> {
                PlaceholderScreen(title = stringResource(R.string.drawer_signatures), onMenuClick = openDrawer)
            }
            composable<Destination.Settings> {
                SettingsScreen(onMenuClick = openDrawer)
            }
            composable<Destination.About> {
                AboutScreen(onMenuClick = openDrawer)
            }
        }
    }
}
