package pl.lk.papermind.ai

import pl.lk.papermind.model.Box
import pl.lk.papermind.model.CrosswordClue
import pl.lk.papermind.model.CrosswordGrid
import pl.lk.papermind.model.CrosswordSlot
import pl.lk.papermind.model.DetectedQuestion
import pl.lk.papermind.model.Direction
import pl.lk.papermind.model.GridCell
import pl.lk.papermind.model.ParsedPage
import pl.lk.papermind.model.QuestionType

/**
 * Krzyżówka 5×5:
 *
 *   K O T E K      A1 KOTEK   D1 KOLOR
 *   O # A # R      A4 LASKA   D2 TASAM
 *   L A S K A      A5 RAMKI   D3 KRAJI
 *   O # A # J
 *   R A M K I
 */
object Cw {
    val solution = mapOf(
        "A1" to "KOTEK", "A4" to "LASKA", "A5" to "RAMKI",
        "D1" to "KOLOR", "D2" to "TASAM", "D3" to "KRAJI",
    )
    private val blocked = setOf(1 to 1, 1 to 3, 3 to 1, 3 to 3)
    val cells: List<GridCell> = (0 until 5).flatMap { r ->
        (0 until 5).map { c -> GridCell(r, c, Box(c * 10f, r * 10f, c * 10f + 10f, r * 10f + 10f), (r to c) in blocked) }
    }
    private fun cell(r: Int, c: Int) = cells[r * 5 + c]
    val slots = listOf(
        CrosswordSlot(1, Direction.ACROSS, (0..4).map { cell(0, it) }),
        CrosswordSlot(4, Direction.ACROSS, (0..4).map { cell(2, it) }),
        CrosswordSlot(5, Direction.ACROSS, (0..4).map { cell(4, it) }),
        CrosswordSlot(1, Direction.DOWN, (0..4).map { cell(it, 0) }),
        CrosswordSlot(2, Direction.DOWN, (0..4).map { cell(it, 2) }),
        CrosswordSlot(3, Direction.DOWN, (0..4).map { cell(it, 4) }),
    )
    val grid = CrosswordGrid(5, 5, Box(0f, 0f, 50f, 50f), cells, slots)

    private fun q(key: String, text: String, length: Int? = null) = DetectedQuestion(
        id = "id$key", number = key.drop(1), type = QuestionType.CROSSWORD_CLUE, text = text, options = emptyList(), box = Box(0f, 0f, 1f, 1f),
        clue = CrosswordClue(key.drop(1).toInt(), if (key[0] == 'A') Direction.ACROSS else Direction.DOWN, length),
    )

    fun clues(withLength: Boolean = false): List<DetectedQuestion> = listOf(
        q("A1", "Domowy mruczek", if (withLength) 5 else null), q("A4", "Pieszczota", if (withLength) 5 else null),
        q("A5", "Obramowanie obrazu", if (withLength) 5 else null), q("D1", "Barwa", if (withLength) 5 else null),
        q("D2", "Fasolowe zwierzę", if (withLength) 5 else null), q("D3", "Kraje (daw.)", if (withLength) 5 else null),
    )

    fun page(withGrid: Boolean = true, withLength: Boolean = false, jpeg: ByteArray? = null) =
        ParsedPage(0L, clues(withLength), "Poziomo Pionowo", if (withGrid) grid else null, jpeg)

    fun ans(key: String, text: String = solution.getValue(key), conf: Float = 0.9f, engine: pl.lk.papermind.model.EngineId = pl.lk.papermind.model.EngineId.CLOUD) =
        pl.lk.papermind.model.Answer("id$key", text = text, confidence = conf, engine = engine)

    fun all(vararg override: Pair<String, String>): Map<String, pl.lk.papermind.model.Answer> {
        val o = override.toMap()
        return solution.keys.associate { "id$it" to ans(it, o[it] ?: solution.getValue(it)) }
    }
}
