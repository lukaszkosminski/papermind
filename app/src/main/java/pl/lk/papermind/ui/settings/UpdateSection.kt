package pl.lk.papermind.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import pl.lk.papermind.R
import pl.lk.papermind.update.Updater
import java.io.File

private sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val release: Updater.Release) : UpdateState
    data class Downloading(val release: Updater.Release, val progress: Float) : UpdateState
    data class Ready(val release: Updater.Release, val file: File, val needsPermission: Boolean) : UpdateState
    data class Error(val message: String) : UpdateState
}

/** Sprawdza GitHub Releases, pobiera nowy APK i uruchamia systemowy instalator. */
@Composable
internal fun UpdateSection() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val current = remember { Updater.currentVersion(context) }
    var state by remember { mutableStateOf<UpdateState>(UpdateState.Idle) }

    fun check() {
        state = UpdateState.Checking
        scope.launch {
            state = try {
                val r = Updater.latest()
                if (Updater.isNewer(r.version, current)) UpdateState.Available(r) else UpdateState.UpToDate
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                UpdateState.Error(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    fun download(r: Updater.Release) {
        state = UpdateState.Downloading(r, 0f)
        scope.launch {
            state = try {
                val f = Updater.download(context, r) { p -> state = UpdateState.Downloading(r, p) }
                val started = Updater.install(context, f)
                UpdateState.Ready(r, f, needsPermission = !started)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                UpdateState.Error(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.settings_updates_version, current))
        when (val s = state) {
            UpdateState.Idle, UpdateState.UpToDate, is UpdateState.Error, UpdateState.Checking -> {
                Button(onClick = ::check, enabled = s != UpdateState.Checking) { Text(stringResource(R.string.settings_updates_check)) }
                when (s) {
                    UpdateState.UpToDate -> Text(stringResource(R.string.settings_updates_latest), color = MaterialTheme.colorScheme.primary)
                    is UpdateState.Error -> Text(stringResource(R.string.settings_updates_error, s.message), color = MaterialTheme.colorScheme.error)
                    UpdateState.Checking -> LinearProgressIndicator(Modifier.fillMaxWidth())
                    else -> Unit
                }
            }
            is UpdateState.Available -> {
                Text(stringResource(R.string.settings_updates_available, s.release.version), color = MaterialTheme.colorScheme.primary)
                if (s.release.notes.isNotBlank()) Text(s.release.notes.trim(), style = MaterialTheme.typography.bodySmall)
                Button(onClick = { download(s.release) }) { Text(stringResource(R.string.settings_updates_download)) }
            }
            is UpdateState.Downloading -> {
                Text(stringResource(R.string.settings_updates_available, s.release.version))
                if (s.progress in 0f..1f) LinearProgressIndicator(progress = { s.progress }, modifier = Modifier.fillMaxWidth())
                else LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            is UpdateState.Ready -> {
                if (s.needsPermission) Text(stringResource(R.string.settings_updates_permission))
                Button(onClick = { state = UpdateState.Ready(s.release, s.file, !Updater.install(context, s.file)) }) {
                    Text(stringResource(R.string.settings_updates_install))
                }
            }
        }
    }
}
