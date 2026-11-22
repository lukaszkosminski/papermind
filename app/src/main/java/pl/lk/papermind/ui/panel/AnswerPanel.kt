package pl.lk.papermind.ui.panel

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import pl.lk.papermind.R
import pl.lk.papermind.model.Answer
import pl.lk.papermind.model.AnswerState
import pl.lk.papermind.ui.QuestionUi
import pl.lk.papermind.ui.overlay.AnswerText
import pl.lk.papermind.ui.theme.OverlayColors
import kotlin.math.roundToInt

@Composable
fun AnswerPanel(
    items: List<QuestionUi>,
    selectedId: String?,
    showExplanations: Boolean,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth()) {
        val ready = items.count { it.state is AnswerState.Ready }
        Text(
            text = if (items.isEmpty()) stringResource(R.string.panel_empty)
            else stringResource(R.string.panel_title, ready, items.size),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
        )
        LazyColumn(
            modifier = Modifier.fillMaxWidth().heightIn(max = 520.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items(items, key = { it.question.id }) { item ->
                QuestionRow(item, item.question.id == selectedId, showExplanations) { onSelect(item.question.id) }
            }
        }
    }
}

@Composable
private fun QuestionRow(item: QuestionUi, selected: Boolean, showExplanations: Boolean, onClick: () -> Unit) {
    val q = item.question
    Surface(
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).clickable(onClick = onClick),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.Top) {
            Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = CircleShape, modifier = Modifier.size(28.dp)) {
                Box(contentAlignment = Alignment.Center) {
                    Text(q.number ?: "•", style = MaterialTheme.typography.labelLarge)
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    AnswerText.shorten(q.text),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.size(4.dp))
                when (val state = item.state) {
                    AnswerState.Pending -> Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.panel_pending), style = MaterialTheme.typography.bodyMedium)
                    }
                    is AnswerState.Failed -> Text(
                        stringResource(R.string.panel_failed, state.message),
                        style = MaterialTheme.typography.bodyMedium,
                        color = OverlayColors.Failed,
                    )
                    is AnswerState.Ready -> ReadyContent(item, state.answer, showExplanations)
                }
            }
        }
    }
}

@Composable
private fun ReadyContent(item: QuestionUi, answer: Answer, showExplanations: Boolean) {
    Text(answerAnnotated(item, answer), style = MaterialTheme.typography.bodyLarge)
    if (showExplanations && !answer.explanation.isNullOrBlank()) {
        Spacer(Modifier.size(2.dp))
        Text(answer.explanation, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Spacer(Modifier.size(2.dp))
    Text(
        stringResource(R.string.panel_meta, engineName(answer.engine), (answer.confidence * 100).roundToInt()),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.outline,
    )
}

/** Opcje poprawne — pogrubione (z etykietą); pozostałe odpowiedzi — zwykły tekst. */
@Composable
private fun answerAnnotated(item: QuestionUi, answer: Answer) = buildAnnotatedString {
    val q = item.question
    val options = AnswerText.correctOptions(q, answer)
    if (options.isNotEmpty()) {
        options.forEachIndexed { i, o ->
            if (i > 0) append("\n")
            withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = OverlayColors.Correct)) { append("${o.label}.") }
            append(" ")
            withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(o.text) }
        }
    } else {
        append(AnswerText.summary(q, answer).ifBlank { "—" })
    }
}
