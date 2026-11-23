package pl.lk.papermind.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import pl.lk.papermind.R
import pl.lk.papermind.diag.DiagLog

/** Podgląd logów diagnostycznych (kamera, OCR/stabilizator, silniki AI) z kopiowaniem do schowka. */
@Composable
internal fun DiagnosticsSection() {
    val context = LocalContext.current
    var shown by remember { mutableStateOf(false) }
    val lines by DiagLog.lines.collectAsState()
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { shown = !shown }) {
                Text(stringResource(if (shown) R.string.settings_logs_hide else R.string.settings_logs_show))
            }
            if (shown) {
                OutlinedButton(onClick = {
                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("PaperMind log", DiagLog.dump()))
                }) { Text(stringResource(R.string.settings_logs_copy)) }
                OutlinedButton(onClick = { DiagLog.clear() }) { Text(stringResource(R.string.settings_logs_clear)) }
            }
        }
        if (shown) {
            Text(
                if (lines.isEmpty()) stringResource(R.string.settings_logs_empty)
                else lines.takeLast(80).asReversed().joinToString("\n"),
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                lineHeight = 13.sp,
            )
        }
    }
}
