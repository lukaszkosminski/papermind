package pl.lk.papermind.ui.crossword

import pl.lk.papermind.model.AnswerState
import pl.lk.papermind.model.CrosswordGrid
import pl.lk.papermind.model.DetectedQuestion
import pl.lk.papermind.model.GridCell
import pl.lk.papermind.model.QuestionType

/**
 * Mapuje odpowiedzi na hasła krzyżówki na litery w komórkach siatki.
 * W przypadku konfliktu liter na skrzyżowaniu wyrazów wybierana jest litera z odpowiedzi o wyższym confidence.
 *
 * TODO: zastąpić implementacją z pakietu ai (pl.lk.papermind.ai.crosswordLetters) po merge gałęzi.
 */
fun crosswordLetters(
    grid: CrosswordGrid,
    questions: List<DetectedQuestion>,
    answers: Map<String, AnswerState>,
): Map<GridCell, Char> {
    val clueMap = questions
        .mapNotNull { q -> q.clue?.let { (it.number to it.direction) to q } }
        .toMap()

    class CellEntry(val char: Char, val confidence: Float)
    val cellMap = grid.cells.associateBy { it.row to it.col }
    val assigned = mutableMapOf<Pair<Int, Int>, CellEntry>()

    for (slot in grid.slots) {
        val q = clueMap[slot.number to slot.direction] ?: continue
        val ready = (answers[q.id] as? AnswerState.Ready)?.answer ?: continue
        val text = ready.text.trim().uppercase()
        val len = minOf(slot.cells.size, text.length)
        for (i in 0 until len) {
            val cell = slot.cells[i]
            if (cell.blocked) continue
            val char = text[i]
            val key = cell.row to cell.col
            val existing = assigned[key]
            if (existing == null || ready.confidence > existing.confidence) {
                assigned[key] = CellEntry(char, ready.confidence)
            }
        }
    }

    val result = mutableMapOf<GridCell, Char>()
    for ((coord, entry) in assigned) {
        val cell = cellMap[coord] ?: continue
        result[cell] = entry.char
    }
    return result
}
