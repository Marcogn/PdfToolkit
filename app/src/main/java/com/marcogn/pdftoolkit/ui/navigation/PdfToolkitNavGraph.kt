package com.marcogn.pdftoolkit.ui.navigation

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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
import com.marcogn.pdftoolkit.ui.edit.EditScreen
import com.marcogn.pdftoolkit.ui.home.HomeScreen
import com.marcogn.pdftoolkit.ui.home.labelRes
import com.marcogn.pdftoolkit.ui.merge.MergeScreen
import com.marcogn.pdftoolkit.ui.recents.RecentsScreen
import com.marcogn.pdftoolkit.ui.recents.RecentsViewModel
import com.marcogn.pdftoolkit.ui.settings.SettingsScreen
import com.marcogn.pdftoolkit.ui.viewer.ViewerScreen
import com.marcogn.pdftoolkit.ui.viewer.rememberOpenPdfLauncher
import com.marcogn.pdftoolkit.ui.viewer.takePersistableAccess
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

/** Tools of Home that work on one open PDF and open straight on their pane or dialog (spec §4.1). */
private val pageTools = setOf(PdfTool.ADD_PAGES, PdfTool.INSERT_IMAGES, PdfTool.REMOVE_PAGES, PdfTool.REORDER_PAGES, PdfTool.FILL_AND_SIGN)

/** Page tools that, after the PDF, ask for more files (images, another PDF): a dialog explains the order first. */
private val toolsPickingTwice = setOf(PdfTool.ADD_PAGES, PdfTool.INSERT_IMAGES)

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
            context.takePersistableAccess(startUri)
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
                // A page tool tapped on Home picks the file first and then opens straight on the tool (spec §4.1).
                var pendingTool by rememberSaveable { mutableStateOf<String?>(null) }
                // Tools that pick a second file after the PDF say so first: otherwise the PDF picker
                // looks like the wrong one ("I wanted images").
                var explainTool by rememberSaveable { mutableStateOf<String?>(null) }
                val openPdf = rememberOpenPdfLauncher { uri ->
                    val tool = pendingTool
                    pendingTool = null
                    navController.navigate(
                        if (tool == null) Destination.Viewer(uri.toString()) else Destination.Edit(uri.toString(), tool),
                    )
                }
                // "Merge PDFs" picks the files first, then opens the merge list (spec §6.6).
                val mergePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
                    if (uris.isNotEmpty()) {
                        uris.forEach { context.takePersistableAccess(it) }
                        navController.navigate(Destination.Merge(uris.map { it.toString() }))
                    }
                }
                HomeScreen(
                    onMenuClick = openDrawer,
                    onOpenPdfClick = {
                        if (entry.lifecycleIsResumed()) {
                            pendingTool = null
                            openPdf()
                        }
                    },
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
                        } else if (tool == PdfTool.MERGE) {
                            if (entry.lifecycleIsResumed()) mergePicker.launch(arrayOf("application/pdf"))
                        } else if (tool in pageTools) {
                            if (entry.lifecycleIsResumed()) {
                                if (tool in toolsPickingTwice) {
                                    explainTool = tool.name
                                } else {
                                    pendingTool = tool.name
                                    openPdf()
                                }
                            }
                        } else if (entry.lifecycleIsResumed()) {
                            navController.navigate(Destination.Tool(tool.name))
                        }
                    },
                )
                explainTool?.let { name ->
                    val tool = PdfTool.valueOf(name)
                    AlertDialog(
                        onDismissRequest = { explainTool = null },
                        title = { Text(stringResource(tool.labelRes())) },
                        text = {
                            Text(
                                stringResource(
                                    if (tool == PdfTool.INSERT_IMAGES) R.string.home_pick_pdf_first_images else R.string.home_pick_pdf_first_pages,
                                ),
                            )
                        },
                        confirmButton = {
                            TextButton(
                                onClick = {
                                    explainTool = null
                                    pendingTool = name
                                    openPdf()
                                },
                            ) { Text(stringResource(R.string.home_pick_pdf_first_confirm)) }
                        },
                        dismissButton = { TextButton(onClick = { explainTool = null }) { Text(stringResource(R.string.save_cancel)) } },
                    )
                }
            }
            composable<Destination.Viewer> { entry ->
                ViewerScreen(
                    onBack = { if (entry.lifecycleIsResumed()) navController.popBackStack() },
                    onEdit = {
                        if (entry.lifecycleIsResumed()) navController.navigate(Destination.Edit(entry.toRoute<Destination.Viewer>().uri))
                    },
                )
            }
            composable<Destination.Edit> { entry ->
                // Both results replace everything above Home: after an overwrite the viewer below
                // still holds the old file open, and a copy is shown on its own.
                val showResult: (String) -> Unit = { uri ->
                    navController.navigate(Destination.Viewer(uri)) { popUpTo<Destination.Home>() }
                }
                EditScreen(
                    startTool = entry.toRoute<Destination.Edit>().tool?.let { PdfTool.valueOf(it) },
                    onBack = { if (entry.lifecycleIsResumed()) navController.popBackStack() },
                    onResultReady = showResult,
                    onOpenCopy = { uri -> if (entry.lifecycleIsResumed()) showResult(uri) },
                )
            }
            composable<Destination.Merge> { entry ->
                MergeScreen(
                    onBack = { if (entry.lifecycleIsResumed()) navController.popBackStack() },
                    onMerge = { uris, edit ->
                        // The first file is the main document of the edit; the others are added to it.
                        if (entry.lifecycleIsResumed() && uris.size >= 2) {
                            navController.navigate(Destination.Edit(uri = uris.first(), mergeWith = uris.drop(1), autoSave = !edit))
                        }
                    },
                )
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
