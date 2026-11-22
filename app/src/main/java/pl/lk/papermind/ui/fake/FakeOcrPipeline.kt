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
import pl.lk.papermind.model.CrosswordClue
import pl.lk.papermind.model.CrosswordGrid
import pl.lk.papermind.model.CrosswordSlot
import pl.lk.papermind.model.DetectedQuestion
import pl.lk.papermind.model.Direction
import pl.lk.papermind.model.GridCell
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
            emit(ParsedPage(System.currentTimeMillis(), sheet.questions, sheet.lines.joinToString("\n") { it.text }, grid = sheet.grid))
        }
    }

    override fun bind(controller: LifecycleCameraController, executor: Executor) {
        bound.value = true
    }

    override fun unbind(controller: LifecycleCameraController) {
        bound.value = false
    }

    private class Sheet(val lines: List<OcrLine>, val questions: List<DetectedQuestion>, val grid: CrosswordGrid? = null)

    private fun sheet(dx: Float, dy: Float): Sheet {
        val lines = ArrayList<OcrLine>()
        val questions = ArrayList<DetectedQuestion>()

        // 1. Siatka krzyżówki 5x5
        val gridLeft = 140f + dx
        val gridTop = 80f + dy
        val cellSize = 120f
        val gridWidth = cellSize * 5
        val gridHeight = cellSize * 5
        val gridBox = Box(gridLeft, gridTop, gridLeft + gridWidth, gridTop + gridHeight)

        val blockedSet = setOf(0 to 3, 1 to 1, 1 to 3, 3 to 1, 3 to 3, 4 to 1)
        val cellNumbers = mapOf(
            (0 to 0) to 1,
            (0 to 2) to 2,
            (2 to 0) to 3,
            (0 to 4) to 4,
            (4 to 2) to 5,
        )

        val cells = ArrayList<GridCell>()
        for (r in 0 until 5) {
            for (c in 0 until 5) {
                val b = Box(gridLeft + c * cellSize, gridTop + r * cellSize, gridLeft + (c + 1) * cellSize, gridTop + (r + 1) * cellSize)
                val blocked = (r to c) in blockedSet
                val num = cellNumbers[r to c]
                cells += GridCell(r, c, b, blocked, num)
                if (num != null) {
                    val numBox = Box(b.left + 4f, b.top + 4f, b.left + 24f, b.top + 24f)
                    lines += OcrLine(num.toString(), numBox, 0.98f)
                }
            }
        }
        val cellMap = cells.associateBy { it.row to it.col }

        val slots = listOf(
            CrosswordSlot(1, Direction.ACROSS, listOf(cellMap[0 to 0]!!, cellMap[0 to 1]!!, cellMap[0 to 2]!!)), // BAT (3)
            CrosswordSlot(3, Direction.ACROSS, listOf(cellMap[2 to 0]!!, cellMap[2 to 1]!!, cellMap[2 to 2]!!, cellMap[2 to 3]!!, cellMap[2 to 4]!!)), // LOKAL (5)
            CrosswordSlot(5, Direction.ACROSS, listOf(cellMap[4 to 2]!!, cellMap[4 to 3]!!, cellMap[4 to 4]!!)), // TAK (3)
            CrosswordSlot(1, Direction.DOWN, listOf(cellMap[0 to 0]!!, cellMap[1 to 0]!!, cellMap[2 to 0]!!, cellMap[3 to 0]!!, cellMap[4 to 0]!!)), // BALET (5)
            CrosswordSlot(2, Direction.DOWN, listOf(cellMap[0 to 2]!!, cellMap[1 to 2]!!, cellMap[2 to 2]!!, cellMap[3 to 2]!!, cellMap[4 to 2]!!)), // TEKST (5)
            CrosswordSlot(4, Direction.DOWN, listOf(cellMap[0 to 4]!!, cellMap[1 to 4]!!, cellMap[2 to 4]!!, cellMap[3 to 4]!!, cellMap[4 to 4]!!)), // LALEK (5)
        )
        val grid = CrosswordGrid(5, 5, gridBox, cells, slots)

        var y = gridTop + gridHeight + 36f
        val left = 70f + dx
        val lineH = 50f
        val gap = 32f

        fun line(text: String, indent: Float = 0f): Box {
            val box = Box(left + indent, y, minOf(viewWidth - 60f + dx, left + indent + text.length * 22f), y + lineH - 10f)
            lines += OcrLine(text, box, 0.95f)
            y += lineH
            return box
        }

        fun clue(id: String, num: Int, dir: Direction, len: Int, text: String) {
            val box = line("$num. $text ($len)")
            questions += DetectedQuestion(
                id = id,
                number = num.toString(),
                type = QuestionType.CROSSWORD_CLUE,
                text = text,
                options = emptyList(),
                box = box,
                clue = CrosswordClue(num, dir, len),
            )
            y += 8f
        }

        line("POZIOMO:")
        clue("cw-1a", 1, Direction.ACROSS, 3, "Bicz, batog")
        clue("cw-3a", 3, Direction.ACROSS, 5, "Pomieszczenie użytkowe")
        clue("cw-5a", 5, Direction.ACROSS, 3, "Potwierdzenie, zgoda")
        y += gap / 2

        line("PIONOWO:")
        clue("cw-1d", 1, Direction.DOWN, 5, "Taniec na puentach")
        clue("cw-2d", 2, Direction.DOWN, 5, "Słowa piosenki lub artykuł")
        clue("cw-4d", 4, Direction.DOWN, 5, "Zabawki dla dzieci")
        y += gap

        fun closed(id: String, no: String, type: QuestionType, text: String, options: List<Pair<String, String>>) {
            val qBox = line("$no. $text")
            val opts = options.map { (label, t) -> AnswerOption(label, t, line("$label. $t", 30f)) }
            questions += DetectedQuestion(id, no, type, text, opts, qBox)
            y += gap
        }

        closed("fake-1", "1", QuestionType.SINGLE_CHOICE, "Które organelle produkują ATP?",
            listOf("A" to "Rybosomy", "B" to "Mitochondria", "C" to "Aparat Golgiego", "D" to "Lizosomy"))

        return Sheet(lines, questions, grid)
    }
}
