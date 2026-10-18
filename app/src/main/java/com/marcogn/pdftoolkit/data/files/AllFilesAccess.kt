package com.marcogn.pdftoolkit.data.files

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * "All files access" (ADR 0007): lets recents keep a PDF that another app handed over with a temporary
 * grant, by its path. Android 11+ grants it on a system settings page (`MANAGE_EXTERNAL_STORAGE`);
 * Android 8–10 through the old storage permissions ([LEGACY_PERMISSIONS]).
 */
interface AllFilesAccess {
    fun isGranted(): Boolean

    companion object {
        /** Asked at runtime below Android 11, where `MANAGE_EXTERNAL_STORAGE` doesn't exist. */
        val LEGACY_PERMISSIONS = arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE)

        /** Whether the grant is a settings page (Android 11+) rather than a runtime permission dialog. */
        val usesSettingsPage: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
    }
}

@Singleton
class AndroidAllFilesAccess @Inject constructor(@ApplicationContext private val context: Context) : AllFilesAccess {
    override fun isGranted(): Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Environment.isExternalStorageManager()
    } else {
        AllFilesAccess.LEGACY_PERMISSIONS.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }
    }
}

/**
 * The system pages where the user turns all files access on or off, most specific first: the app's own
 * switch on Android 11+ (then the general list, for devices without it), the app details below.
 */
fun Context.allFilesAccessSettingsIntents(): List<Intent> {
    val packageUri = "package:$packageName".toUri()
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        listOf(
            Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, packageUri),
            Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION),
        )
    } else {
        listOf(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri))
    }
}
