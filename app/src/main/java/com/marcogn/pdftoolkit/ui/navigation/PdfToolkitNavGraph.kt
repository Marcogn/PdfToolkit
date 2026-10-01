package com.marcogn.pdftoolkit.ui.navigation

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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.res.stringResource
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
import com.marcogn.pdftoolkit.ui.settings.SettingsScreen
import kotlinx.coroutines.launch

// Una NavBackStackEntry arriva a RESUMED solo a transizione finita: ogni navigate()/popBackStack()
// passa da questo controllo sull'entry che possiede la callback, così un doppio tap rapido non
// atterra su una schermata ancora a metà transizione. Stesso fix dei progetti di riferimento.
private fun NavBackStackEntry.lifecycleIsResumed() = lifecycle.currentState == Lifecycle.State.RESUMED

// SPEC §9: transizioni di 200–250 ms, mai oltre 300. I riferimenti usano 300 ms con uno slide a
// tutta larghezza; qui slide breve più dissolvenza, più leggero da percepire.
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

/** Drawer sempre raggiungibile (hamburger a sinistra) attorno al grafo di navigazione, SPEC §4. */
@Composable
fun PdfToolkitNavGraph(navController: NavHostController = rememberNavController()) {
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDrawerDestination = backStackEntry?.destination?.asDrawerDestination()

    val openDrawer: () -> Unit = { scope.launch { drawerState.open() } }
    val navigateFromDrawer: (Destination) -> Unit = { destination ->
        if (navController.currentBackStackEntry?.lifecycleIsResumed() != false) {
            scope.launch { drawerState.close() }
            if (destination == Destination.Home) {
                // Come KartLog: popUpTo(Home){saveState} + restoreState verso Home stessa non
                // ripristina in modo affidabile; si torna sempre a una Home pulita.
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

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = { AppDrawerSheet(current = currentDrawerDestination, onNavigate = navigateFromDrawer) },
    ) {
        NavHost(
            navController = navController,
            startDestination = Destination.Home,
            enterTransition = navEnterTransition,
            exitTransition = navExitTransition,
            popEnterTransition = navPopEnterTransition,
            popExitTransition = navPopExitTransition,
        ) {
            composable<Destination.Home> { entry ->
                HomeScreen(
                    onMenuClick = openDrawer,
                    onOpenPdfClick = { if (entry.lifecycleIsResumed()) navController.navigate(Destination.Viewer) },
                    onToolClick = { tool ->
                        if (tool == PdfTool.MY_SIGNATURES) {
                            // Stessa schermata della voce del drawer: stessa navigazione, così
                            // non si accumulano due copie nello stack.
                            navigateFromDrawer(Destination.Signatures)
                        } else if (entry.lifecycleIsResumed()) {
                            navController.navigate(Destination.Tool(tool.name))
                        }
                    },
                )
            }
            composable<Destination.Viewer> { entry ->
                PlaceholderScreen(
                    title = stringResource(R.string.viewer_title),
                    onBack = { if (entry.lifecycleIsResumed()) navController.popBackStack() },
                )
            }
            composable<Destination.Tool> { entry ->
                val tool = PdfTool.valueOf(entry.toRoute<Destination.Tool>().tool)
                PlaceholderScreen(
                    title = stringResource(tool.labelRes()),
                    onBack = { if (entry.lifecycleIsResumed()) navController.popBackStack() },
                )
            }
            composable<Destination.Recents> {
                PlaceholderScreen(title = stringResource(R.string.drawer_recents), onMenuClick = openDrawer)
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
