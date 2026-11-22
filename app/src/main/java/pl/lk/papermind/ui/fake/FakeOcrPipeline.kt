package pl.lk.papermind.ui.fake

import androidx.camera.view.LifecycleCameraController
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import pl.lk.papermind.model.AnswerOption
import pl.lk.papermind.model.Box
import pl.lk.papermind.model.DetectedQuestion
import pl.lk.papermind.model.OcrFrame
import pl.lk.papermind.model.OcrLine
import pl.lk.papermind.model.ParsedPage
import pl.lk.papermind.model.QuestionType
import pl.lk.papermind.ocr.OcrPipeline
import java.util.concurrent.Executor
import kotlin.math.sin

/**
 * Fake do rozwoju UI bez kamery/ML Kit: po `bind` emituje klatki z przykładowym arkuszem
 * (lekko „drżącym", by sprawdzić śledzenie) i raz stronę z pytaniami.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FakeOcrPipeline(
    private val viewWidth: Float = 1080f,
    private val frameIntervalMs: Long = 150,
    private val pageDelayMs: Long = 1_500,
    private val wobblePx: Float = 18f,
) : OcrPipeline {

    private val bound = MutableStateFlow(false)

    override val frames: Flow<OcrFrame> = bound.flatMapLatest { on ->
        if (!on) emptyFlow() else flow {
            val start = System.currentTimeMillis()
            while (true) {
                val t = System.currentTimeMillis() - start
                val dx = (sin(t / 700.0) * wobblePx).toFloat()
                val dy = (sin(t / 900.0 + 1) * wobblePx).toFloat()
                emit(OcrFrame(System.currentTimeMillis(), sheet(dx, dy).lines))
                delay(frameIntervalMs)
            }
        }
    }

    override val pages: Flow<ParsedPage> = bound.flatMapLatest { on ->
        if (!on) emptyFlow() else flow {
            delay(pageDelayMs)
            val sheet = sheet(0f, 0f)
            emit(ParsedPage(System.currentTimeMillis(), sheet.questions, sheet.lines.joinToString("\n") { it.text }))
        }
    }

    override fun bind(controller: LifecycleCameraController, executor: Executor) {
        bound.value = true
    }

    override fun unbind(controller: LifecycleCameraController) {
        bound.value = false
    }

    private class Sheet(val lines: List<OcrLine>, val questions: List<DetectedQuestion>)

    private fun sheet(dx: Float, dy: Float): Sheet {
        val lines = ArrayList<OcrLine>()
        val questions = ArrayList<DetectedQuestion>()
        var y = 380f + dy
        val left = 70f + dx
        val lineH = 56f
        val gap = 40f

        fun line(text: String, indent: Float = 0f): Box {
            val box = Box(left + indent, y, minOf(viewWidth - 60f + dx, left + indent + text.length * 24f), y + lineH - 10f)
            lines += OcrLine(text, box, 0.95f)
            y += lineH
            return box
        }

        fun closed(id: String, no: String, type: QuestionType, text: String, options: List<Pair<String, String>>) {
            val qBox = line("$no. $text")
            val opts = options.map { (label, t) -> AnswerOption(label, t, line("$label. $t", 30f)) }
            questions += DetectedQuestion(id, no, type, text, opts, qBox)
            y += gap
        }

        fun single(id: String, no: String, type: QuestionType, text: String) {
            val box = line(if (type == QuestionType.FORM_FIELD) text else "$no. $text")
            questions += DetectedQuestion(id, no.takeIf { type != QuestionType.FORM_FIELD }, type, text, emptyList(), box)
            y += gap
        }

        closed("fake-1", "1", QuestionType.SINGLE_CHOICE, "Które organelle produkują ATP?",
            listOf("A" to "Rybosomy", "B" to "Mitochondria", "C" to "Aparat Golgiego", "D" to "Lizosomy"))
        closed("fake-2", "2", QuestionType.MULTI_CHOICE, "Które liczby są pierwsze?",
            listOf("A" to "7", "B" to "9", "C" to "11", "D" to "15"))
        single("fake-3", "3", QuestionType.OPEN, "Wyjaśnij krótko, czym jest fotosynteza.")
        single("fake-4", "4", QuestionType.MATH, "Oblicz 12 · 7 + 5.")
        single("fake-5", "", QuestionType.FORM_FIELD, "Imię i nazwisko: ____________")
        return Sheet(lines, questions)
    }
}
