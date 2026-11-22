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
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
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
    modifier: Modifier = Modifier,
) {
    val measurer = rememberTextMeasurer()
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
        for (item in items) {
            drawItem(item, item.question.id == selectedId, measurer, pulse, bottomInsetPx)
        }
    }
}

private fun Box.topLeft() = Offset(left, top)
private fun Box.size() = Size(width, height)

private fun Box.inflate(d: Float) = Box(left - d, top - d, right + d, bottom + d)

private fun DrawScope.drawItem(
    item: QuestionUi,
    selected: Boolean,
    measurer: TextMeasurer,
    pulse: Float,
    bottomInsetPx: Float,
) {
    val dp = density
    val tracked = item.tracked
    val qTracked = tracked?.question
    if (selected && qTracked != null) {
        val b = qTracked.box.inflate(6 * dp)
        drawRoundRect(OverlayColors.Selected.copy(alpha = qTracked.alpha), b.topLeft(), b.size(), CornerRadius(10 * dp), style = Stroke(3 * dp))
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
            drawFailedIcon(qTracked.box, qTracked.alpha, measurer)
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
                    drawBubble(AnswerText.summary(item.question, answer), qTracked.box, qTracked.alpha, measurer, bottomInsetPx)
                }
            } else if (qTracked != null) {
                val text = AnswerText.summary(item.question, answer)
                if (text.isNotBlank()) drawBubble(text, qTracked.box, qTracked.alpha, measurer, bottomInsetPx)
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

private fun DrawScope.drawFailedIcon(box: Box, alpha: Float, measurer: TextMeasurer) {
    val r = 11 * density
    val c = Offset(box.right.coerceAtMost(size.width - r - 4 * density), box.top - r * 0.2f)
    drawCircle(OverlayColors.Failed.copy(alpha = alpha), r, c)
    val layout = measurer.measure("!", TextStyle(color = Color.White.copy(alpha = alpha), fontSize = 15.sp, fontWeight = FontWeight.Bold))
    drawText(layout, topLeft = Offset(c.x - layout.size.width / 2f, c.y - layout.size.height / 2f))
}

private fun DrawScope.drawBubble(text: String, anchor: Box, alpha: Float, measurer: TextMeasurer, bottomInsetPx: Float) {
    val dp = density
    val pad = 10 * dp
    val maxTextWidth = (minOf(size.width * 0.8f, 360 * dp) - 2 * pad).toInt().coerceAtLeast(1)
    val layout: TextLayoutResult = measurer.measure(
        text = text,
        style = TextStyle(color = OverlayColors.BubbleText.copy(alpha = alpha), fontSize = 16.sp, fontWeight = FontWeight.Medium, lineHeight = 21.sp),
        overflow = TextOverflow.Ellipsis,
        maxLines = 5,
        constraints = Constraints(maxWidth = maxTextWidth),
    )
    val w = layout.size.width + 2 * pad
    val h = layout.size.height + 2 * pad
    val pos = BubblePlacement.place(
        anchor = anchor, w = w, h = h, viewW = size.width, viewH = size.height,
        margin = 8 * dp, gap = 6 * dp, safeBottom = size.height - bottomInsetPx,
    )
    val radius = CornerRadius(12 * dp)
    drawRoundRect(OverlayColors.BubbleBackground.copy(alpha = OverlayColors.BubbleBackground.alpha * alpha), Offset(pos.x, pos.y), Size(w, h), radius)
    drawRoundRect(OverlayColors.Correct.copy(alpha = 0.8f * alpha), Offset(pos.x, pos.y), Size(w, h), radius, style = Stroke(1.5f * dp))
    drawText(layout, topLeft = Offset(pos.x + pad, pos.y + pad))
}
