package pl.lk.papermind.ui.overlay

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.sp
import pl.lk.papermind.model.AnswerState
import pl.lk.papermind.model.Box
import pl.lk.papermind.model.CrosswordGrid
import pl.lk.papermind.model.GridCell
import pl.lk.papermind.model.QuestionType
import pl.lk.papermind.ui.QuestionUi
import pl.lk.papermind.ui.theme.OverlayColors

/**
 * Nakładka AR rysowana nad PreviewView (ten sam rozmiar; współrzędne Box = piksele widoku).
 * [bottomInsetPx] — wysokość zasłoniętą przez zwinięty panel (dymki tam nie wchodzą).
 */
@Composable
fun AnswerOverlay(
    items: List<QuestionUi>,
    lineBoxes: List<Box>,
    showLineOutlines: Boolean,
    selectedId: String?,
    bottomInsetPx: Float,
    topInsetPx: Float = 0f,
    grid: CrosswordGrid? = null,
    crosswordLetters: Map<GridCell, Char> = emptyMap(),
    modifier: Modifier = Modifier,
) {
    val measurer = rememberTextMeasurer()
    val layoutCache = remember { HashMap<String, TextLayoutResult>() }
    val pulse by rememberInfiniteTransition(label = "pulse").animateFloat(
        initialValue = 0.25f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(700, easing = LinearEasing), RepeatMode.Reverse),
        label = "pulseAlpha",
    )

    Canvas(modifier = modifier) {
        val density = this.density
        if (showLineOutlines) {
            for (b in lineBoxes) {
                drawRoundRect(OverlayColors.LineOutline, b.topLeft(), b.size(), CornerRadius(4 * density), style = Stroke(1 * density))
            }
        }
        if (grid != null) {
            drawCrosswordGrid(
                grid = grid,
                letters = crosswordLetters,
                items = items,
                selectedId = selectedId,
                measurer = measurer,
                layoutCache = layoutCache,
                pulse = pulse,
            )
        }
        for (item in items) {
            drawItem(item, item.question.id == selectedId, measurer, layoutCache, pulse, topInsetPx, bottomInsetPx, hasGrid = grid != null)
        }
    }
}

private fun Box.topLeft() = Offset(left, top)
private fun Box.size() = Size(width, height)

private fun Box.inflate(d: Float) = Box(left - d, top - d, right + d, bottom + d)

private fun DrawScope.drawCrosswordGrid(
    grid: CrosswordGrid,
    letters: Map<GridCell, Char>,
    items: List<QuestionUi>,
    selectedId: String?,
    measurer: TextMeasurer,
    layoutCache: MutableMap<String, TextLayoutResult>,
    pulse: Float,
) {
    val dp = density

    // Zaznaczony slot powiązany z wybranym hasłem
    val selectedQuestion = items.firstOrNull { it.question.id == selectedId }?.question
    val selectedSlot = if (selectedQuestion?.clue != null) {
        grid.slots.firstOrNull {
            it.number == selectedQuestion.clue.number && it.direction == selectedQuestion.clue.direction
        }
    } else null

    // 1. Podświetlenie zaznaczonego slotu
    if (selectedSlot != null) {
        for (cell in selectedSlot.cells) {
            val r = CornerRadius(4 * dp)
            drawRoundRect(OverlayColors.Selected.copy(alpha = 0.35f), cell.box.topLeft(), cell.box.size(), r)
            drawRoundRect(OverlayColors.Selected, cell.box.topLeft(), cell.box.size(), r, style = Stroke(3 * dp))
        }
    }

    // 2. Przerywane obrysy slotów w stanie Pending
    val clueStates = items.mapNotNull { item ->
        item.question.clue?.let { (it.number to it.direction) to item.state }
    }.toMap()

    val dash = PathEffect.dashPathEffect(floatArrayOf(8 * dp, 6 * dp), 0f)
    val pendingStroke = Stroke(width = 2.5f * dp, pathEffect = dash)

    for (slot in grid.slots) {
        val state = clueStates[slot.number to slot.direction]
        if (state is AnswerState.Pending && slot != selectedSlot) {
            for (cell in slot.cells) {
                drawRoundRect(
                    OverlayColors.Pending.copy(alpha = pulse * 0.85f),
                    cell.box.topLeft(),
                    cell.box.size(),
                    CornerRadius(4 * dp),
                    style = pendingStroke,
                )
            }
        }
    }

    // 3. Litery w komórkach (wyśrodkowane, rozmiar ~60% komórki, kontrastowy kolor z cieniem, pomijając blocked)
    for (cell in grid.cells) {
        if (cell.blocked) continue
        val letter = letters[cell] ?: continue
        val fontSizeSp = (cell.box.height * 0.6f).toSp()
        val cacheKey = "letter:$letter:${(cell.box.height * 0.6f).toInt()}"
        val layout = layoutCache.getOrPut(cacheKey) {
            measurer.measure(
                text = letter.toString(),
                style = TextStyle(
                    color = Color(0xFF061A40),
                    fontSize = fontSizeSp,
                    fontWeight = FontWeight.Black,
                    shadow = androidx.compose.ui.graphics.Shadow(
                        color = Color.White,
                        blurRadius = 6 * dp,
                    ),
                ),
            )
        }
        val x = cell.box.left + (cell.box.width - layout.size.width) / 2f
        val y = cell.box.top + (cell.box.height - layout.size.height) / 2f
        drawText(layout, topLeft = Offset(x, y))
    }
}

private fun DrawScope.drawItem(
    item: QuestionUi,
    selected: Boolean,
    measurer: TextMeasurer,
    layoutCache: MutableMap<String, TextLayoutResult>,
    pulse: Float,
    topInsetPx: Float,
    bottomInsetPx: Float,
    hasGrid: Boolean,
) {
    val dp = density
    val tracked = item.tracked
    val qTracked = tracked?.question
    if (selected && qTracked != null) {
        val b = qTracked.box.inflate(6 * dp)
        drawRoundRect(OverlayColors.Selected.copy(alpha = qTracked.alpha), b.topLeft(), b.size(), CornerRadius(10 * dp), style = Stroke(3 * dp))
    }
    // Hasła krzyżówki
    if (item.question.type == QuestionType.CROSSWORD_CLUE) {
        when (val state = item.state) {
            AnswerState.Pending -> if (!hasGrid && qTracked != null) {
                val b = qTracked.box.inflate(4 * dp)
                drawRoundRect(
                    OverlayColors.Pending.copy(alpha = pulse * qTracked.alpha),
                    b.topLeft(), b.size(), CornerRadius(8 * dp), style = Stroke(2.5f * dp),
                )
            }
            is AnswerState.Failed -> if (qTracked != null) {
                drawFailedIcon(qTracked.box, qTracked.alpha, measurer, layoutCache, topInsetPx, bottomInsetPx)
            }
            is AnswerState.Ready -> if (!hasGrid && qTracked != null) {
                val text = AnswerText.summary(item.question, state.answer)
                if (text.isNotBlank()) drawBubble(text, qTracked.box, qTracked.alpha, measurer, layoutCache, topInsetPx, bottomInsetPx)
            }
        }
        return
    }
    when (val state = item.state) {
        AnswerState.Pending -> if (qTracked != null) {
            val b = qTracked.box.inflate(4 * dp)
            drawRoundRect(
                OverlayColors.Pending.copy(alpha = pulse * qTracked.alpha),
                b.topLeft(), b.size(), CornerRadius(8 * dp), style = Stroke(2.5f * dp),
            )
        }
        is AnswerState.Failed -> if (qTracked != null) {
            drawFailedIcon(qTracked.box, qTracked.alpha, measurer, layoutCache, topInsetPx, bottomInsetPx)
        }
        is AnswerState.Ready -> {
            val answer = state.answer
            if (AnswerText.isChoiceAnswer(item.question, answer)) {
                var anyMarked = false
                for (opt in AnswerText.correctOptions(item.question, answer)) {
                    val t = tracked?.options?.get(opt.label) ?: continue
                    drawCorrectOption(t.box, t.alpha)
                    anyMarked = true
                }
                // opcje poza kadrem, ale pytanie widoczne — pokaż skrót w dymku
                if (!anyMarked && qTracked != null) {
                    drawBubble(AnswerText.summary(item.question, answer), qTracked.box, qTracked.alpha, measurer, layoutCache, topInsetPx, bottomInsetPx)
                }
            } else if (qTracked != null) {
                val text = AnswerText.summary(item.question, answer)
                if (text.isNotBlank()) drawBubble(text, qTracked.box, qTracked.alpha, measurer, layoutCache, topInsetPx, bottomInsetPx)
            }
        }
    }
}

private fun DrawScope.drawCorrectOption(box: Box, alpha: Float) {
    val dp = density
    val b = box.inflate(5 * dp)
    val radius = CornerRadius(10 * dp)
    drawRoundRect(OverlayColors.Correct.copy(alpha = 0.18f * alpha), b.topLeft(), b.size(), radius)
    drawRoundRect(OverlayColors.Correct.copy(alpha = alpha), b.topLeft(), b.size(), radius, style = Stroke(3 * dp))
    // ✓ w zielonym kółku na prawej krawędzi opcji
    val r = 11 * dp
    val c = Offset(b.right - r - 2 * dp, b.top + b.height / 2)
    drawCircle(OverlayColors.Correct.copy(alpha = alpha), r, c)
    val check = Path().apply {
        moveTo(c.x - r * 0.45f, c.y + r * 0.02f)
        lineTo(c.x - r * 0.1f, c.y + r * 0.4f)
        lineTo(c.x + r * 0.5f, c.y - r * 0.35f)
    }
    drawPath(check, Color(0xFF06260F).copy(alpha = alpha), style = Stroke(2.4f * dp, cap = androidx.compose.ui.graphics.StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round))
}

private fun DrawScope.drawFailedIcon(
    box: Box,
    alpha: Float,
    measurer: TextMeasurer,
    layoutCache: MutableMap<String, TextLayoutResult>,
    topInsetPx: Float,
    bottomInsetPx: Float,
) {
    val r = 11 * density
    val topLimit = topInsetPx + r + 4 * density
    val bottomLimit = size.height - bottomInsetPx - r - 4 * density
    val clampedY = (box.top - r * 0.2f).coerceIn(topLimit, maxOf(topLimit, bottomLimit))
    val c = Offset(box.right.coerceIn(r + 4 * density, size.width - r - 4 * density), clampedY)
    drawCircle(OverlayColors.Failed.copy(alpha = alpha), r, c)
    val layout = layoutCache.getOrPut("icon:!") {
        measurer.measure(
            text = "!",
            style = TextStyle(color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold),
        )
    }
    drawText(layout, topLeft = Offset(c.x - layout.size.width / 2f, c.y - layout.size.height / 2f), alpha = alpha)
}

private fun DrawScope.drawBubble(
    text: String,
    anchor: Box,
    alpha: Float,
    measurer: TextMeasurer,
    layoutCache: MutableMap<String, TextLayoutResult>,
    safeTopPx: Float,
    bottomInsetPx: Float,
) {
    val dp = density
    val pad = 10 * dp
    val maxTextWidth = (minOf(size.width * 0.8f, 360 * dp) - 2 * pad).toInt().coerceAtLeast(1)
    val cacheKey = "$maxTextWidth:$text"
    val layout: TextLayoutResult = layoutCache.getOrPut(cacheKey) {
        measurer.measure(
            text = text,
            style = TextStyle(
                color = OverlayColors.BubbleText,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                lineHeight = 21.sp,
            ),
            overflow = TextOverflow.Ellipsis,
            maxLines = 5,
            constraints = Constraints(maxWidth = maxTextWidth),
        )
    }
    val w = layout.size.width + 2 * pad
    val h = layout.size.height + 2 * pad
    val pos = BubblePlacement.place(
        anchor = anchor,
        w = w,
        h = h,
        viewW = size.width,
        viewH = size.height,
        margin = 8 * dp,
        gap = 6 * dp,
        safeTop = safeTopPx,
        safeBottom = size.height - bottomInsetPx,
    )
    val radius = CornerRadius(12 * dp)
    drawRoundRect(
        OverlayColors.BubbleBackground.copy(alpha = OverlayColors.BubbleBackground.alpha * alpha),
        Offset(pos.x, pos.y),
        Size(w, h),
        radius,
    )
    drawRoundRect(
        OverlayColors.Correct.copy(alpha = 0.8f * alpha),
        Offset(pos.x, pos.y),
        Size(w, h),
        radius,
        style = Stroke(1.5f * dp),
    )
    drawText(layout, topLeft = Offset(pos.x + pad, pos.y + pad), alpha = alpha)
}
