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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import pl.lk.papermind.R
import pl.lk.papermind.model.Answer
import pl.lk.papermind.model.AnswerState
import pl.lk.papermind.model.Direction
import pl.lk.papermind.model.QuestionType
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

        val crosswordClues = items.filter { it.question.type == QuestionType.CROSSWORD_CLUE }
        val regularQuestions = items.filter { it.question.type != QuestionType.CROSSWORD_CLUE }

        LazyColumn(
            modifier = Modifier.fillMaxWidth().heightIn(max = 520.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (regularQuestions.isNotEmpty()) {
                items(regularQuestions, key = { it.question.id }) { item ->
                    QuestionRow(item, item.question.id == selectedId, showExplanations) { onSelect(item.question.id) }
                }
            }

            if (crosswordClues.isNotEmpty()) {
                item(key = "cw_header") {
                    CrosswordSectionHeader()
                }

                val across = crosswordClues.filter { it.question.clue?.direction == Direction.ACROSS }
                    .sortedBy { it.question.clue?.number ?: 0 }
                if (across.isNotEmpty()) {
                    item(key = "cw_across_header") {
                        CrosswordDirectionHeader(stringResource(R.string.crossword_across))
                    }
                    items(across, key = { it.question.id }) { item ->
                        CrosswordClueRow(item, item.question.id == selectedId) { onSelect(item.question.id) }
                    }
                }

                val down = crosswordClues.filter { it.question.clue?.direction == Direction.DOWN }
                    .sortedBy { it.question.clue?.number ?: 0 }
                if (down.isNotEmpty()) {
                    item(key = "cw_down_header") {
                        CrosswordDirectionHeader(stringResource(R.string.crossword_down))
                    }
                    items(down, key = { it.question.id }) { item ->
                        CrosswordClueRow(item, item.question.id == selectedId) { onSelect(item.question.id) }
                    }
                }

                val other = crosswordClues.filter { it.question.clue?.direction == null }
                if (other.isNotEmpty()) {
                    items(other, key = { it.question.id }) { item ->
                        CrosswordClueRow(item, item.question.id == selectedId) { onSelect(item.question.id) }
                    }
                }
            }
        }
    }
}

@Composable
private fun CrosswordSectionHeader() {
    Text(
        text = stringResource(R.string.panel_crossword_section),
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
    )
}

@Composable
private fun CrosswordDirectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp),
    )
}

@Composable
private fun CrosswordClueRow(item: QuestionUi, selected: Boolean, onClick: () -> Unit) {
    val q = item.question
    val clue = q.clue
    Surface(
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).clickable(onClick = onClick),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = CircleShape, modifier = Modifier.size(28.dp)) {
                Box(contentAlignment = Alignment.Center) {
                    Text(clue?.number?.toString() ?: q.number ?: "•", style = MaterialTheme.typography.labelLarge)
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
                    is AnswerState.Ready -> {
                        val word = state.answer.text.trim().uppercase()
                        val len = clue?.length ?: word.length
                        val spaced = word.toCharArray().joinToString("  ")
                        Text(
                            text = spaced.ifBlank { "—" },
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.size(2.dp))
                        Text(
                            text = "${AnswerText.letterCountSuffix(len)} · " + stringResource(
                                R.string.panel_meta,
                                engineName(state.answer.engine),
                                (state.answer.confidence * 100).roundToInt(),
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }
                }
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
