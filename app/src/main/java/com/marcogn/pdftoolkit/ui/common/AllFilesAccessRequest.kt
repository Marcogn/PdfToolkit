package com.marcogn.pdftoolkit.ui.common

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.ActivityCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.marcogn.pdftoolkit.data.files.AllFilesAccess
import com.marcogn.pdftoolkit.data.files.allFilesAccessSettingsIntents

/**
 * Asks for all files access (ADR 0007) and calls [onDone] when the user is back, whatever the answer.
 * The returned function takes `openSettings`: go to the system page even below Android 11 (to turn
 * the access off). Android 11+ always uses the system page; its end is the app coming back to the
 * foreground, not the page's result, which some devices deliver at once. Below Android 11 the
 * permission dialog decides, and a permanent refusal leads to the app's details page instead.
 */
@Composable
fun rememberAllFilesAccessRequest(onDone: () -> Unit): (openSettings: Boolean) -> Unit {
    val context = LocalContext.current
    val done by rememberUpdatedState(onDone)
    // In the system page, and whether the app has been stopped since: both survive recreation.
    var inSettings by rememberSaveable { mutableStateOf(false) }
    var leftApp by rememberSaveable { mutableStateOf(false) }
    val settingsPage = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { }
    val openSettingsPage: () -> Unit = {
        if (settingsPage.launchFirst(context.allFilesAccessSettingsIntents())) {
            inSettings = true
            leftApp = false
        } else {
            done()
        }
    }
    val legacyPermissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        val activity = context.findActivity()
        val refusedForGood = result.values.any { !it } && activity != null &&
            AllFilesAccess.LEGACY_PERMISSIONS.none { ActivityCompat.shouldShowRequestPermissionRationale(activity, it) }
        if (refusedForGood) openSettingsPage() else done()
    }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { if (inSettings) leftApp = true }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        if (inSettings && leftApp) {
            inSettings = false
            done()
        }
    }
    return { openSettings ->
        if (AllFilesAccess.usesSettingsPage || openSettings) openSettingsPage() else legacyPermissions.launch(AllFilesAccess.LEGACY_PERMISSIONS)
    }
}

/** Launches the first of [intents] the device can handle; false if none. */
private fun ManagedActivityResultLauncher<Intent, ActivityResult>.launchFirst(intents: List<Intent>): Boolean {
    for (intent in intents) {
        try {
            launch(intent)
            return true
        } catch (e: ActivityNotFoundException) {
            // Not on this device: try the next one.
        }
    }
    return false
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
