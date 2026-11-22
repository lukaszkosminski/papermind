package pl.lk.papermind

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.material3.Surface
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import pl.lk.papermind.ui.MainViewModel
import pl.lk.papermind.ui.camera.CameraScreen
import pl.lk.papermind.ui.settings.SettingsScreen
import pl.lk.papermind.ui.theme.PaperMindTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as PaperMindApp).container
        setContent {
            PaperMindTheme {
                val viewModel: MainViewModel = viewModel(factory = MainViewModel.factory(container))
                var showSettings by rememberSaveable { mutableStateOf(false) }
                val state by viewModel.uiState.collectAsStateWithLifecycle()
                Box(Modifier.fillMaxSize()) {
                    // kamera zostaje w kompozycji pod ustawieniami (szybki powrót, zachowana pauza)
                    CameraScreen(viewModel, paused = showSettings, onOpenSettings = { showSettings = true })
                    if (showSettings) {
                        BackHandler { showSettings = false }
                        Surface(Modifier.fillMaxSize()) {
                            SettingsScreen(
                                settings = state.settings,
                                connection = state.connection,
                                onUpdate = viewModel::updateSettings,
                                onTestConnection = viewModel::testConnection,
                                onBack = { showSettings = false },
                            )
                        }
                    }
                }
            }
        }
    }
}
