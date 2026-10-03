package com.marcogn.pdftoolkit.ui.search

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.marcogn.pdftoolkit.R
import com.marcogn.pdftoolkit.pdf.text.IndexStatus
import com.marcogn.pdftoolkit.pdf.text.SearchState

/**
 * The top bar while searching (spec §5.1): a field, the counter "3 of 17" and previous / next.
 * [query] is what the reader typed; [state] is the answer to the last query that settled.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchTopBar(
    query: String,
    onQueryChange: (String) -> Unit,
    state: SearchState,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onClose: () -> Unit,
) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    TopAppBar(
        title = {
            TextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                placeholder = { Text(stringResource(R.string.search_hint)) },
                trailingIcon = {
                    searchCounter(query, state)?.let { Text(it, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(end = 8.dp)) }
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onNext() }),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                ),
                modifier = Modifier.focusRequester(focus),
            )
        },
        navigationIcon = {
            IconButton(onClick = onClose) {
                Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.cd_search_close))
            }
        },
        actions = {
            val hasResults = state.matches.isNotEmpty()
            IconButton(onClick = onPrevious, enabled = hasResults) {
                Icon(Icons.Filled.KeyboardArrowUp, contentDescription = stringResource(R.string.cd_search_previous))
            }
            IconButton(onClick = onNext, enabled = hasResults) {
                Icon(Icons.Filled.KeyboardArrowDown, contentDescription = stringResource(R.string.cd_search_next))
            }
        },
    )
}

/**
 * "3 of 17" for the current result, "No results" once the answer is final, nothing while it may
 * still change (blank query, or the index is still being built).
 */
@Composable
internal fun searchCounter(query: String, state: SearchState): String? = when {
    query.isBlank() || state.query == null -> null
    state.matches.isNotEmpty() -> stringResource(R.string.search_counter, state.current + 1, state.matches.size)
    state.status == IndexStatus.DONE -> stringResource(R.string.search_no_results)
    else -> null
}

/**
 * The explanations that replace results (spec §5.1): the document has no text (a scan), or it
 * couldn't be read. Nothing otherwise.
 */
@Composable
fun SearchNotice(state: SearchState, modifier: Modifier = Modifier) {
    val message = when {
        state.noSearchableText -> R.string.search_no_text
        state.status == IndexStatus.FAILED -> R.string.search_failed
        else -> return
    }
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, modifier = modifier) {
        Text(
            stringResource(message),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        )
    }
}
