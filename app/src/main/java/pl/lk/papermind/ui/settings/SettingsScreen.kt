package pl.lk.papermind.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import pl.lk.papermind.R
import pl.lk.papermind.model.EngineId
import pl.lk.papermind.settings.AppSettings
import pl.lk.papermind.settings.CloudProvider
import pl.lk.papermind.ui.ConnectionTest
import pl.lk.papermind.ui.panel.engineName
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settings: AppSettings,
    connection: ConnectionTest,
    onUpdate: ((AppSettings) -> AppSettings) -> Unit,
    onTestConnection: () -> Unit,
    onBack: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.cd_back))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Section(R.string.settings_engine) {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    EngineId.entries.forEachIndexed { i, id ->
                        SegmentedButton(
                            selected = settings.engine == id,
                            onClick = { onUpdate { it.copy(engine = id) } },
                            shape = SegmentedButtonDefaults.itemShape(i, EngineId.entries.size),
                        ) { Text(engineName(id)) }
                    }
                }
                SwitchRow(R.string.settings_fallback, settings.fallbackEnabled) { v -> onUpdate { it.copy(fallbackEnabled = v) } }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = onTestConnection, enabled = connection != ConnectionTest.Running) {
                        Text(stringResource(R.string.settings_test_connection))
                    }
                    when (connection) {
                        ConnectionTest.Idle -> Unit
                        ConnectionTest.Running -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        is ConnectionTest.Result -> Text(
                            if (connection.ok) stringResource(R.string.settings_test_ok)
                            else stringResource(R.string.settings_test_fail) + (connection.detail?.let { ": $it" } ?: ""),
                            color = if (connection.ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }

            Section(R.string.settings_opencode) {
                TextSetting(R.string.settings_opencode_url, settings.openCodeUrl, keyboardType = KeyboardType.Uri) { v -> onUpdate { it.copy(openCodeUrl = v.trim()) } }
                TextSetting(R.string.settings_opencode_user, settings.openCodeUser) { v -> onUpdate { it.copy(openCodeUser = v.trim()) } }
                TextSetting(R.string.settings_opencode_password, settings.openCodePassword, secret = true) { v -> onUpdate { it.copy(openCodePassword = v.trim()) } }
                TextSetting(R.string.settings_opencode_model, settings.openCodeModel, hint = R.string.settings_model_hint) { v -> onUpdate { it.copy(openCodeModel = v.trim()) } }
            }

            Section(R.string.settings_cloud) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CloudProvider.entries.forEach { p ->
                        FilterChip(
                            selected = settings.cloudProvider == p,
                            onClick = { onUpdate { it.copy(cloudProvider = p) } },
                            label = { Text(providerName(p)) },
                        )
                    }
                }
                TextSetting(R.string.settings_cloud_key, settings.cloudApiKey, secret = true) { v -> onUpdate { it.copy(cloudApiKey = v.trim()) } }
                TextSetting(R.string.settings_cloud_model, settings.cloudModel, hint = R.string.settings_model_default_hint) { v -> onUpdate { it.copy(cloudModel = v.trim()) } }
            }

            Section(R.string.settings_behavior) {
                Text(stringResource(R.string.settings_language), style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LANGUAGES.forEach { (code, label) ->
                        FilterChip(
                            selected = settings.language == code,
                            onClick = { onUpdate { it.copy(language = code) } },
                            label = { Text(label) },
                        )
                    }
                }
                SwitchRow(R.string.settings_explanations, settings.showExplanations) { v -> onUpdate { it.copy(showExplanations = v) } }
                StabilitySlider(settings.stabilityMs) { v -> onUpdate { it.copy(stabilityMs = v) } }
            }
        }
    }
}

private val LANGUAGES = listOf("pl" to "Polski", "en" to "English")

@Composable
private fun providerName(p: CloudProvider) = when (p) {
    CloudProvider.ANTHROPIC -> "Anthropic"
    CloudProvider.GEMINI -> "Gemini"
    CloudProvider.OPENAI -> "OpenAI"
}

@Composable
private fun Section(title: Int, content: @Composable () -> Unit) {
    Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(title), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        content()
    }
}

@Composable
private fun SwitchRow(label: Int, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Text(stringResource(label), Modifier.weight(1f))
        Switch(checked, onChange)
    }
}

/** Pole tekstowe z lokalnym stanem (brak skoków kursora przy asynchronicznym zapisie do DataStore). */
@Composable
private fun TextSetting(
    label: Int,
    value: String,
    secret: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
    hint: Int? = null,
    onChange: (String) -> Unit,
) {
    var text by remember { mutableStateOf(value) }
    var visible by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = text,
        onValueChange = { text = it; onChange(it) },
        label = { Text(stringResource(label)) },
        placeholder = hint?.let { { Text(stringResource(it)) } },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
        visualTransformation = if (secret && !visible) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = if (secret) KeyboardType.Password else keyboardType),
        trailingIcon = if (secret) {
            {
                IconButton(onClick = { visible = !visible }) {
                    Icon(if (visible) Icons.Default.VisibilityOff else Icons.Default.Visibility, stringResource(R.string.cd_show_secret))
                }
            }
        } else null,
    )
}

@Composable
private fun StabilitySlider(valueMs: Long, onChange: (Long) -> Unit) {
    var slider by remember(valueMs) { mutableStateOf(valueMs.toFloat()) }
    Column {
        Text(stringResource(R.string.settings_stability, valueMs.toInt()), style = MaterialTheme.typography.labelLarge)
        Slider(
            value = slider,
            onValueChange = { slider = it },
            onValueChangeFinished = { onChange(snapStability(slider)) },
            valueRange = 200f..2000f,
            steps = 17, // krok 100 ms
        )
    }
}

internal fun snapStability(raw: Float): Long = ((raw / 100f).roundToInt() * 100L).coerceIn(200L, 2000L)
