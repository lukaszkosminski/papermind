package pl.lk.papermind.ai

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import pl.lk.papermind.model.Answer
import pl.lk.papermind.model.AnswerState
import pl.lk.papermind.model.Box
import pl.lk.papermind.model.CrosswordClue
import pl.lk.papermind.model.DetectedQuestion
import pl.lk.papermind.model.Direction
import pl.lk.papermind.model.EngineId
import pl.lk.papermind.model.QuestionType

class CrosswordSolverTest {
    private val clues = Cw.clues()
    private val grid = Cw.grid

    @Test fun normalizeWord() {
        assertEquals("ŻABA", CrosswordSolver.normalizeWord(" ża-ba. "))
        assertEquals("ŁĄKA", CrosswordSolver.normalizeWord("łąka"))
        assertEquals("ABC", CrosswordSolver.normalizeWord("a b 1 c"))
    }

    @Test fun correctAnswersHaveNoProblems() {
        val a = CrosswordSolver.analyze(clues, Cw.all(), grid)
        assertTrue(a.problems.isEmpty())
    }

    @Test fun conflictLoserIsLowerConfidenceClue() {
        val answers = Cw.all("D2" to "TAXAM").toMutableMap()
        answers["idD2"] = Cw.ans("D2", "TAXAM", conf = 0.4f)
        val a = CrosswordSolver.analyze(clues, answers, grid)
        assertEquals(setOf("idD2"), a.conflicts)
        assertTrue(a.badLength.isEmpty())
    }

    @Test fun badLengthDetectedFromSlotEvenWithoutClueLength() {
        val a = CrosswordSolver.analyze(clues, Cw.all("A5" to "RAMK"), grid)
        assertEquals(setOf("idA5"), a.badLength)
        assertEquals(setOf("idA5"), a.problems)
    }

    @Test fun withoutGridOnlyLengthIsValidated() {
        val qs = Cw.clues(withLength = true)
        val a = CrosswordSolver.analyze(qs, Cw.all("A1" to "KOT", "D2" to "ZZZZZ"), null)
        assertEquals(setOf("idA1"), a.problems) // konfliktów bez siatki nie ma
    }

    @Test fun lettersPerCellAndConflictResolvedByConfidence() {
        val answers = Cw.all().toMutableMap()
        answers["idD2"] = Cw.ans("D2", "TXSAM", conf = 0.3f) // (1,2) nie krzyżuje się z niczym, (2,2)=S zgodne
        answers["idA4"] = Cw.ans("A4", "LAXKA", conf = 0.95f) // (2,2): X (0.95) kontra S (0.3 / D2)
        val letters = CrosswordSolver.letterMap(clues, answers, grid).mapKeys { it.key.row to it.key.col }
        assertEquals('X', letters[2 to 2])
        assertEquals('K', letters[0 to 0])
        assertNull(letters[1 to 1]) // pole zablokowane
        assertEquals(21, letters.size) // 25 - 4 czarne
    }

    @Test fun crosswordLettersUsesOnlyReadyAnswersOfCorrectLength() {
        val states: Map<String, AnswerState> = mapOf(
            "idA1" to AnswerState.Ready(Cw.ans("A1")),
            "idA4" to AnswerState.Ready(Cw.ans("A4", "LAS")), // zła długość -> pominięte
            "idA5" to AnswerState.Pending,
            "idD1" to AnswerState.Failed("x"),
        )
        val m = crosswordLetters(grid, clues, states)
        assertEquals(5, m.size)
        assertEquals("KOTEK", (0..4).map { c -> m.entries.first { it.key.row == 0 && it.key.col == c }.value }.joinToString(""))
    }

    @Test fun patternsComeFromOtherAnswers() {
        val known = Cw.all()
        val d2 = clues.first { it.id == "idD2" }
        val out = CrosswordSolver.withPatterns(listOf(d2), clues, known, grid, exclude = setOf("idD2"))
        assertEquals("T_S_M", out.single().clue!!.pattern)
        assertEquals(5, out.single().clue!!.length)
    }

    @Test fun patternMergesExistingClueLettersAndSkipsUnknownSlots() {
        val withPattern = clues.first { it.id == "idD1" }.let { it.copy(clue = it.clue!!.copy(pattern = "_O___")) }
        val out = CrosswordSolver.withPatterns(listOf(withPattern), clues, emptyMap(), grid)
        assertEquals("_O___", out.single().clue!!.pattern)
        val orphan = DetectedQuestion("x", "9", QuestionType.CROSSWORD_CLUE, "?", emptyList(), Box(0f, 0f, 0f, 0f), CrosswordClue(9, Direction.DOWN, 4))
        assertEquals(orphan, CrosswordSolver.withPatterns(listOf(orphan), clues, Cw.all(), grid).single())
    }

    @Test fun secondRoundAsksOnlyForConflictingClueWithPattern() = runTest {
        val first = Cw.all("D2" to "TAXAM").values.map { if (it.questionId == "idD2") it.copy(confidence = 0.4f) else it }
        val asked = ArrayList<List<DetectedQuestion>>()
        val result = CrosswordSolver.solve(Cw.page(), emptyMap(), first) { p ->
            asked += p.questions
            listOf(Cw.ans("D2", "TASAM", conf = 0.7f))
        }
        assertEquals(1, asked.size)
        assertEquals(listOf("idD2"), asked[0].map { it.id })
        assertEquals("T_S_M", asked[0][0].clue!!.pattern)
        assertEquals("TASAM", result.first { it.questionId == "idD2" }.text)
        assertEquals(6, result.size)
    }

    @Test fun neverMoreThanTwoRounds() = runTest {
        val first = Cw.all("D2" to "TAXAM").values.map { if (it.questionId == "idD2") it.copy(confidence = 0.4f) else it }
        var calls = 0
        val result = CrosswordSolver.solve(Cw.page(), emptyMap(), first) { _ -> calls++; listOf(Cw.ans("D2", "TAYAM", conf = 0.5f)) }
        assertEquals(1, calls) // pierwsza runda była poza solve; solve dopytuje raz
        assertEquals(CrosswordSolver.MAX_ROUNDS, 2)
        assertEquals(6, result.size)
    }

    @Test fun retryFailureKeepsFirstRoundResult() = runTest {
        val first = Cw.all("A5" to "RAMK").values.toList()
        val result = CrosswordSolver.solve(Cw.page(), emptyMap(), first) { throw EngineException("sieć") }
        val a5 = result.first { it.questionId == "idA5" }
        assertEquals("RAMK", a5.text)
        assertTrue(a5.confidence <= CrosswordSolver.BAD_LENGTH_CONFIDENCE)
        assertTrue(a5.explanation!!.contains("Długość"))
    }

    @Test fun noRetryWhenAllConsistent() = runTest {
        var calls = 0
        val out = CrosswordSolver.solve(Cw.page(), emptyMap(), Cw.all().values.toList()) { _ -> calls++; emptyList() }
        assertEquals(0, calls)
        assertEquals(6, out.size)
    }

    @Test fun badLengthWithoutGridTriggersRetryUsingClueLength() = runTest {
        val page = Cw.page(withGrid = false, withLength = true)
        val first = Cw.all("A1" to "KOT").values.toList()
        var asked: List<DetectedQuestion> = emptyList()
        val out = CrosswordSolver.solve(page, emptyMap(), first) { p -> asked = p.questions; listOf(Cw.ans("A1", "kotek")) }
        assertEquals(listOf("idA1"), asked.map { it.id })
        assertEquals("KOTEK", out.first { it.questionId == "idA1" }.text)
    }

    @Test fun chooseRanksFitThenClashesThenConfidence() {
        val q = clues.first { it.id == "idD2" }
        val fixed = CrosswordSolver.letterMap(clues.filter { it.id != "idD2" }, Cw.all(), grid)
        val bad = Cw.ans("D2", "TAS", conf = 0.99f)
        val clash = Cw.ans("D2", "TAXAM", conf = 0.9f)
        val good = Cw.ans("D2", "TAKAM".replace('K', 'S'), conf = 0.3f)
        assertEquals(clash, CrosswordSolver.choose(q, bad, clash, grid, fixed))
        assertEquals(good, CrosswordSolver.choose(q, clash, good, grid, fixed)) // brak konfliktu bije wyższą pewność
        val a = Cw.ans("D2", "TASAM", conf = 0.5f)
        val b = Cw.ans("D2", "TASAM", conf = 0.8f)
        assertEquals(b, CrosswordSolver.choose(q, a, b, grid, fixed))
        assertEquals(b, CrosswordSolver.choose(q, b, a, grid, fixed).let { b })
    }

    @Test fun panoramicOrOrphanCluesDoNotCrash() {
        val orphan = DetectedQuestion("o", "7", QuestionType.CROSSWORD_CLUE, "?", emptyList(), Box(0f, 0f, 0f, 0f), CrosswordClue(7, Direction.ACROSS))
        val qs = clues + orphan
        val answers = Cw.all() + ("o" to Answer("o", text = "COKOLWIEK", engine = EngineId.CLOUD))
        assertTrue(CrosswordSolver.analyze(qs, answers, grid).problems.isEmpty())
        assertEquals(21, CrosswordSolver.letterMap(qs, answers, grid).size)
        assertFalse(CrosswordSolver.isClue(orphan.copy(clue = null)))
    }
}
