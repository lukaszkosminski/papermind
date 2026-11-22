package pl.lk.papermind.ui.crossword

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import pl.lk.papermind.model.Answer
import pl.lk.papermind.model.AnswerState
import pl.lk.papermind.model.Box
import pl.lk.papermind.model.CrosswordClue
import pl.lk.papermind.model.CrosswordGrid
import pl.lk.papermind.model.CrosswordSlot
import pl.lk.papermind.model.DetectedQuestion
import pl.lk.papermind.model.Direction
import pl.lk.papermind.model.EngineId
import pl.lk.papermind.model.GridCell
import pl.lk.papermind.model.QuestionType

class CrosswordLettersTest {

    private val cell00 = GridCell(0, 0, Box(0f, 0f, 10f, 10f), blocked = false, number = 1)
    private val cell01 = GridCell(0, 1, Box(10f, 0f, 20f, 10f), blocked = false)
    private val cell02 = GridCell(0, 2, Box(20f, 0f, 30f, 10f), blocked = false)
    private val cell10 = GridCell(1, 0, Box(0f, 10f, 10f, 20f), blocked = false)
    private val cell20 = GridCell(2, 0, Box(0f, 20f, 10f, 30f), blocked = false)
    private val cellBlocked = GridCell(1, 1, Box(10f, 10f, 20f, 20f), blocked = true)

    private val grid = CrosswordGrid(
        rows = 3,
        cols = 3,
        box = Box(0f, 0f, 30f, 30f),
        cells = listOf(cell00, cell01, cell02, cell10, cellBlocked, cell20),
        slots = listOf(
            CrosswordSlot(1, Direction.ACROSS, listOf(cell00, cell01, cell02)),
            CrosswordSlot(1, Direction.DOWN, listOf(cell00, cell10, cell20)),
        ),
    )

    private val clueAcross = DetectedQuestion(
        id = "clue-1a",
        number = "1",
        type = QuestionType.CROSSWORD_CLUE,
        text = "Bicz, batog",
        options = emptyList(),
        box = Box(0f, 50f, 100f, 70f),
        clue = CrosswordClue(1, Direction.ACROSS, 3),
    )

    private val clueDown = DetectedQuestion(
        id = "clue-1d",
        number = "1",
        type = QuestionType.CROSSWORD_CLUE,
        text = "Taniec sceniczny",
        options = emptyList(),
        box = Box(0f, 80f, 100f, 100f),
        clue = CrosswordClue(1, Direction.DOWN, 3),
    )

    @Test
    fun crosswordLetters_mapsAnswersToGridCells() {
        val answers = mapOf(
            "clue-1a" to AnswerState.Ready(Answer("clue-1a", text = "BAT", confidence = 0.9f, engine = EngineId.GEMINI_NANO)),
            "clue-1d" to AnswerState.Ready(Answer("clue-1d", text = "BAL", confidence = 0.95f, engine = EngineId.GEMINI_NANO)),
        )

        val letters = crosswordLetters(grid, listOf(clueAcross, clueDown), answers)

        assertEquals('B', letters[cell00])
        assertEquals('A', letters[cell01])
        assertEquals('T', letters[cell02])
        assertEquals('A', letters[cell10])
        assertEquals('L', letters[cell20])
    }

    @Test
    fun crosswordLetters_resolvesConflictByHigherConfidence() {
        // Crossing cell00: across says 'K' (conf 0.7), down says 'B' (conf 0.95)
        val answers = mapOf(
            "clue-1a" to AnswerState.Ready(Answer("clue-1a", text = "KAT", confidence = 0.7f, engine = EngineId.GEMINI_NANO)),
            "clue-1d" to AnswerState.Ready(Answer("clue-1d", text = "BAL", confidence = 0.95f, engine = EngineId.GEMINI_NANO)),
        )

        val letters = crosswordLetters(grid, listOf(clueAcross, clueDown), answers)

        // 'B' wins because 0.95 > 0.7
        assertEquals('B', letters[cell00])
        assertEquals('A', letters[cell01])
        assertEquals('T', letters[cell02])
    }

    @Test
    fun crosswordLetters_ignoresPendingAndFailedAnswers() {
        val answers = mapOf(
            "clue-1a" to AnswerState.Pending,
            "clue-1d" to AnswerState.Failed("Błąd sieci"),
        )

        val letters = crosswordLetters(grid, listOf(clueAcross, clueDown), answers)
        assertTrue(letters.isEmpty())
    }

    @Test
    fun crosswordLetters_handlesShorterAnswerThanSlot() {
        val answers = mapOf(
            "clue-1a" to AnswerState.Ready(Answer("clue-1a", text = "BA", confidence = 0.9f, engine = EngineId.GEMINI_NANO)),
        )

        val letters = crosswordLetters(grid, listOf(clueAcross), answers)
        assertEquals('B', letters[cell00])
        assertEquals('A', letters[cell01])
        assertNull(letters[cell02])
    }
}
