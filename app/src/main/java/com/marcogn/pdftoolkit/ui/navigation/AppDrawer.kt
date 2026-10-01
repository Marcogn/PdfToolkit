package com.marcogn.pdftoolkit.ui.navigation

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.Draw
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.marcogn.pdftoolkit.R

private data class DrawerEntry(
    val destination: Destination,
    @StringRes val label: Int,
    val icon: ImageVector,
)

private val mainEntries = listOf(
    DrawerEntry(Destination.Home, R.string.drawer_home, Icons.Outlined.Home),
    DrawerEntry(Destination.Recents, R.string.drawer_recents, Icons.Outlined.History),
    DrawerEntry(Destination.Signatures, R.string.drawer_signatures, Icons.Outlined.Draw),
)

private val secondaryEntries = listOf(
    DrawerEntry(Destination.Settings, R.string.drawer_settings, Icons.Outlined.Settings),
    DrawerEntry(Destination.About, R.string.drawer_about, Icons.Outlined.Info),
)

/** Drawer entries, spec §4. [current] highlights the entry of the screen on top. */
@Composable
fun AppDrawerSheet(current: Destination?, onNavigate: (Destination) -> Unit) {
    ModalDrawerSheet {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 12.dp)) {
            Spacer(Modifier.height(16.dp))
            Icon(
                Icons.AutoMirrored.Outlined.InsertDriveFile,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            )
            mainEntries.forEach { entry -> DrawerItem(entry, current, onNavigate) }
            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            secondaryEntries.forEach { entry -> DrawerItem(entry, current, onNavigate) }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun DrawerItem(entry: DrawerEntry, current: Destination?, onNavigate: (Destination) -> Unit) {
    NavigationDrawerItem(
        label = { Text(stringResource(entry.label)) },
        icon = { Icon(entry.icon, contentDescription = null) },
        selected = current == entry.destination,
        onClick = { onNavigate(entry.destination) },
    )
}
