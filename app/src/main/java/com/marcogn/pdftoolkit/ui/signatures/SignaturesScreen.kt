package com.marcogn.pdftoolkit.ui.signatures

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.border
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.marcogn.pdftoolkit.R
import com.marcogn.pdftoolkit.data.signatures.Signature
import com.marcogn.pdftoolkit.domain.signature.SignatureType

private val ThumbnailWidth = 140.dp
private val ThumbnailHeight = 64.dp

/** "My signatures" (spec §6.5, §8): the archive, with draw / import, rename, favourite and delete. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SignaturesScreen(onMenuClick: () -> Unit, viewModel: SignaturesViewModel = hiltViewModel()) {
    val signatures by viewModel.signatures.collectAsStateWithLifecycle()
    val creation = rememberSignatureCreationState()
    var renaming by rememberSaveable { mutableStateOf<Long?>(null) }
    var deleting by rememberSaveable { mutableStateOf<Long?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.drawer_signatures)) },
                navigationIcon = {
                    IconButton(onClick = onMenuClick) { Icon(Icons.Filled.Menu, contentDescription = stringResource(R.string.cd_menu)) }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { creation.start() },
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text(stringResource(R.string.signature_new)) },
            )
        },
    ) { padding ->
        val list = signatures
        when {
            list == null -> Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            list.isEmpty() -> Box(Modifier.padding(padding).fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                Text(
                    stringResource(R.string.signatures_empty),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
            else -> LazyColumn(
                Modifier.padding(padding).fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 88.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(list, key = { it.id }) { signature ->
                    SignatureRow(
                        signature = signature,
                        viewModel = viewModel,
                        onRename = { renaming = signature.id },
                        onDelete = { deleting = signature.id },
                    )
                }
            }
        }
    }

    SignatureCreationHost(creation, viewModel, onCreated = {})

    signatures?.firstOrNull { it.id == renaming }?.let { signature ->
        RenameDialog(signature.name, onConfirm = { viewModel.rename(signature, it); renaming = null }, onDismiss = { renaming = null })
    }
    signatures?.firstOrNull { it.id == deleting }?.let { signature ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.signature_delete_title, signature.name)) },
            text = { Text(stringResource(R.string.signature_delete_body)) },
            confirmButton = { TextButton(onClick = { viewModel.delete(signature); deleting = null }) { Text(stringResource(R.string.fill_delete)) } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.save_cancel)) } },
        )
    }
}

@Composable
private fun SignatureRow(signature: Signature, viewModel: SignaturesViewModel, onRename: () -> Unit, onDelete: () -> Unit) {
    var menu by rememberSaveable(signature.id) { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SignatureThumbnail(
                viewModel.file(signature),
                Modifier.size(ThumbnailWidth, ThumbnailHeight).border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp)),
                contentDescription = signature.name,
            )
            Column(Modifier.weight(1f)) {
                Text(signature.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    stringResource(if (signature.type == SignatureType.DRAWN) R.string.signature_type_drawn else R.string.signature_type_imported),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = { viewModel.setDefault(signature, !signature.isDefault) }) {
                Icon(
                    if (signature.isDefault) Icons.Filled.Star else Icons.Outlined.StarBorder,
                    contentDescription = stringResource(if (signature.isDefault) R.string.signature_unset_favorite else R.string.signature_set_favorite),
                    tint = if (signature.isDefault) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.cd_more_actions)) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.signature_rename)) }, onClick = { menu = false; onRename() })
                    DropdownMenuItem(text = { Text(stringResource(R.string.fill_delete)) }, onClick = { menu = false; onDelete() })
                }
            }
        }
    }
}

@Composable
private fun RenameDialog(current: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var name by rememberSaveable { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.signature_rename)) },
        text = { OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true, modifier = Modifier.fillMaxWidth()) },
        confirmButton = { TextButton(onClick = { onConfirm(name) }, enabled = name.isNotBlank()) { Text(stringResource(R.string.fill_text_confirm)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.save_cancel)) } },
    )
}
