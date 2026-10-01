package com.marcogn.pdftoolkit.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.clickable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.TextButton
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalResources
import com.marcogn.pdftoolkit.domain.model.ReadingMode
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.marcogn.pdftoolkit.R
import com.marcogn.pdftoolkit.domain.model.ThemeMode
import com.marcogn.pdftoolkit.ui.theme.ThemeViewModel
import com.marcogn.pdftoolkit.ui.theme.isDynamicColorSupported

/** Settings (spec §10): theme, dynamic colour, language, default reading mode, recents and thumbnail cache. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onMenuClick: () -> Unit,
    themeViewModel: ThemeViewModel = hiltViewModel(),
    settingsViewModel: SettingsViewModel = hiltViewModel(),
) {
    val readingMode by settingsViewModel.readingMode.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val resources = LocalResources.current
    var confirmClearRecents by rememberSaveable { mutableStateOf(false) }
    val showMessage: (Int) -> Unit = { messageRes ->
        scope.launch { snackbarHostState.showSnackbar(resources.getString(messageRes)) }
    }

    val themeMode by themeViewModel.themeMode.collectAsStateWithLifecycle()
    val dynamicColor by themeViewModel.dynamicColor.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onMenuClick) {
                        Icon(Icons.Filled.Menu, contentDescription = stringResource(R.string.cd_menu))
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            SectionHeader(stringResource(R.string.settings_appearance_section))
            Text(stringResource(R.string.settings_theme_label), style = MaterialTheme.typography.bodyLarge)
            Column(Modifier.selectableGroup()) {
                ThemeMode.entries.forEach { mode ->
                    RadioOptionRow(
                        label = stringResource(mode.labelRes()),
                        selected = themeMode == mode,
                        onClick = { themeViewModel.onThemeModeSelected(mode) },
                    )
                }
            }
            DynamicColorRow(
                checked = dynamicColor && isDynamicColorSupported,
                enabled = isDynamicColorSupported,
                onCheckedChange = themeViewModel::onDynamicColorChanged,
            )

            HorizontalDivider(Modifier.padding(vertical = 8.dp))

            SectionHeader(stringResource(R.string.settings_reading_section))
            Text(stringResource(R.string.settings_reading_mode_label), style = MaterialTheme.typography.bodyLarge)
            Column(Modifier.selectableGroup()) {
                ReadingMode.entries.forEach { mode ->
                    RadioOptionRow(
                        label = stringResource(mode.labelRes()),
                        selected = readingMode == mode,
                        onClick = { settingsViewModel.onReadingModeSelected(mode) },
                    )
                }
            }

            HorizontalDivider(Modifier.padding(vertical = 8.dp))

            SectionHeader(stringResource(R.string.settings_data_section))
            ActionRow(
                title = stringResource(R.string.settings_clear_recents),
                summary = stringResource(R.string.settings_clear_recents_summary),
                onClick = { confirmClearRecents = true },
            )
            ActionRow(
                title = stringResource(R.string.settings_clear_thumbnails),
                summary = stringResource(R.string.settings_clear_thumbnails_summary),
                onClick = { settingsViewModel.clearThumbnails { showMessage(R.string.settings_thumbnails_cleared) } },
            )

            HorizontalDivider(Modifier.padding(vertical = 8.dp))

            SectionHeader(stringResource(R.string.settings_language_label))
            // Read from AppCompatDelegate, not from a ViewModel: the system is the source of truth
            // (autoStoreLocales), and the activity is recreated on change anyway.
            var selectedLanguage by remember { mutableStateOf(currentAppLanguage()) }
            Column(Modifier.selectableGroup()) {
                AppLanguage.entries.forEach { language ->
                    RadioOptionRow(
                        label = stringResource(language.labelRes()),
                        selected = selectedLanguage == language,
                        onClick = {
                            selectedLanguage = language
                            applyAppLanguage(language)
                        },
                    )
                }
            }
        }
    }

    if (confirmClearRecents) {
        ConfirmClearRecentsDialog(
            onConfirm = {
                confirmClearRecents = false
                settingsViewModel.clearRecents { showMessage(R.string.settings_recents_cleared) }
            },
            onDismiss = { confirmClearRecents = false },
        )
    }
}

@Composable
private fun ConfirmClearRecentsDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_clear_recents_confirm_title)) },
        text = { Text(stringResource(R.string.settings_clear_recents_confirm_body)) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(R.string.settings_clear_recents_confirm)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}

@Composable
private fun ActionRow(title: String, summary: String, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(vertical = 8.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp).semantics { heading() },
    )
}

@Composable
private fun RadioOptionRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .selectable(selected = selected, onClick = onClick, role = Role.RadioButton),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 12.dp))
    }
}

@Composable
private fun DynamicColorRow(checked: Boolean, enabled: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.settings_dynamic_color), style = MaterialTheme.typography.bodyLarge)
            Text(
                stringResource(
                    if (enabled) R.string.settings_dynamic_color_summary else R.string.settings_dynamic_color_unsupported,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

private fun ReadingMode.labelRes(): Int = when (this) {
    ReadingMode.CONTINUOUS -> R.string.reading_mode_continuous
    ReadingMode.SINGLE_PAGE -> R.string.reading_mode_single
}

private fun ThemeMode.labelRes(): Int = when (this) {
    ThemeMode.SISTEMA -> R.string.theme_system
    ThemeMode.CHIARO -> R.string.theme_light
    ThemeMode.SCURO -> R.string.theme_dark
}

private fun AppLanguage.labelRes(): Int = when (this) {
    AppLanguage.SISTEMA -> R.string.language_system
    AppLanguage.ITALIANO -> R.string.language_italian
    AppLanguage.ENGLISH -> R.string.language_english
}
