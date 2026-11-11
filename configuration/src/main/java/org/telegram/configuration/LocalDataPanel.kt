package org.telegram.configuration

import androidx.compose.foundation.layout.Column
import androidx.compose.material.AlertDialog
import androidx.compose.material.Button
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier

data class LocalDataState(
    val cacheBytes: Long = 0,
    val historyBytes: Long = 0,
    val loading: Boolean = true,
    val clearing: Boolean = false,
    val error: String? = null
)

/** State is hoisted: the host supplies localized text, formatted sizes and storage callbacks. */
@Composable
fun LocalDataPanel(
    state: LocalDataState,
    title: String,
    cacheSummary: String,
    historySummary: String,
    clearLabel: String,
    confirmMessage: String,
    cancelLabel: String,
    refreshLabel: String,
    busyLabel: String,
    onClear: () -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier
) {
    var confirming by remember { mutableStateOf(false) }
    Column(modifier) {
        Text(title)
        if (state.loading || state.clearing) Text(busyLabel)
        else {
            Text(cacheSummary)
            Text(historySummary)
        }
        state.error?.let { Text(it) }
        Button(enabled = !state.loading && !state.clearing, onClick = { confirming = true }) {
            Text(clearLabel)
        }
        TextButton(enabled = !state.clearing && !state.loading, onClick = onRefresh) {
            Text(refreshLabel)
        }
    }
    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text(title) },
            text = { Text(confirmMessage) },
            confirmButton = {
                TextButton(enabled = !state.clearing, onClick = {
                    confirming = false
                    onClear()
                }) { Text(clearLabel) }
            },
            dismissButton = { TextButton(onClick = { confirming = false }) { Text(cancelLabel) } }
        )
    }
}
