package pl.lk.papermind.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import pl.lk.papermind.R
import pl.lk.papermind.ai.OpenCodeGoApi

/** Pobiera listę modeli OpenCode Go (po wpisaniu klucza) i pozwala wybrać model jednym dotknięciem. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun OpenCodeGoModelPicker(apiKey: String, selected: String, onSelect: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    var models by remember { mutableStateOf<List<OpenCodeGoApi.Model>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val current = selected.ifBlank { OpenCodeGoApi.DEFAULT_MODEL }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.settings_go_hint), style = MaterialTheme.typography.bodySmall)
        Text(stringResource(R.string.settings_go_selected, current), style = MaterialTheme.typography.labelLarge)
        Button(
            enabled = !loading && apiKey.isNotBlank(),
            onClick = {
                loading = true; error = null
                scope.launch {
                    try {
                        models = OpenCodeGoApi.models(apiKey)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        error = e.message ?: e.javaClass.simpleName
                    }
                    loading = false
                }
            },
        ) { Text(stringResource(R.string.settings_go_models)) }
        if (loading) CircularProgressIndicator(Modifier, strokeWidth = 2.dp)
        error?.let { Text(stringResource(R.string.settings_go_error, it), color = MaterialTheme.colorScheme.error) }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            models.forEach { m ->
                FilterChip(
                    selected = m.id == current,
                    onClick = { onSelect(m.id) },
                    label = { Text(if (m.vision) "${m.name} 🖼" else m.name) },
                )
            }
        }
    }
}
