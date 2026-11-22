package pl.lk.papermind.ui.panel

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import pl.lk.papermind.R
import pl.lk.papermind.model.EngineId

@Composable
fun engineName(id: EngineId): String = stringResource(
    when (id) {
        EngineId.GEMINI_NANO -> R.string.engine_nano
        EngineId.OPENCODE -> R.string.engine_opencode
        EngineId.CLOUD -> R.string.engine_cloud
    },
)
