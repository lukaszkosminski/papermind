package pl.lk.papermind.ui.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import pl.lk.papermind.model.Answer
import pl.lk.papermind.model.AnswerOption
import pl.lk.papermind.model.Box
import pl.lk.papermind.model.CrosswordClue
import pl.lk.papermind.model.DetectedQuestion
import pl.lk.papermind.model.Direction
import pl.lk.papermind.model.EngineId
import pl.lk.papermind.model.QuestionType

class AnswerTextTest {

    private val singleChoiceQuestion = DetectedQuestion(
        id = "q1",
        number = "1",
        type = QuestionType.SINGLE_CHOICE,
        text = "Ktore organelle produkuja ATP?",
        options = listOf(
            AnswerOption("A", "Rybosomy", Box(0f, 0f, 10f, 10f)),
            AnswerOption("B", "Mitochondria", Box(0f, 10f, 10f, 20f)),
            AnswerOption("C", "Aparat Golgiego", Box(0f, 20f, 10f, 30f)),
        ),
        box = Box(0f, 0f, 100f, 100f),
    )

    private val openQuestion = DetectedQuestion(
        id = "q2",
        number = "2",
        type = QuestionType.OPEN,
        text = "Wyjaśnij czym jest fotosynteza.",
        options = emptyList(),
        box = Box(0f, 0f, 100f, 100f),
    )

    @Test
    fun correctOptions_matchesNormalizedLabels() {
        val answer = Answer(
            questionId = "q1",
            correctOptionLabels = listOf("b.", "b)", "B"),
            engine = EngineId.GEMINI_NANO,
        )
        val matched = AnswerText.correctOptions(singleChoiceQuestion, answer)
        assertEquals(1, matched.size)
        assertEquals("B", matched[0].label)
        assertEquals("Mitochondria", matched[0].text)
    }

    @Test
    fun isChoiceAnswer_returnsTrueOnlyForClosedWithLabels() {
        val closedWithLabels = Answer("q1", correctOptionLabels = listOf("B"), engine = EngineId.GEMINI_NANO)
        val closedWithoutLabels = Answer("q1", text = "B", engine = EngineId.GEMINI_NANO)
        val openWithLabels = Answer("q2", correctOptionLabels = listOf("B"), engine = EngineId.GEMINI_NANO)

        assertTrue(AnswerText.isChoiceAnswer(singleChoiceQuestion, closedWithLabels))
        assertFalse(AnswerText.isChoiceAnswer(singleChoiceQuestion, closedWithoutLabels))
        assertFalse(AnswerText.isChoiceAnswer(openQuestion, openWithLabels))
    }

    @Test
    fun summary_formatsClosedQuestionWithOptions() {
        val answer = Answer("q1", correctOptionLabels = listOf("B"), engine = EngineId.GEMINI_NANO)
        assertEquals("B. Mitochondria", AnswerText.summary(singleChoiceQuestion, answer))
    }

    @Test
    fun summary_formatsOpenQuestionWithText() {
        val answer = Answer("q2", text = "Proces tworzenia glukozy", engine = EngineId.GEMINI_NANO)
        assertEquals("Proces tworzenia glukozy", AnswerText.summary(openQuestion, answer))
    }

    @Test
    fun shorten_limitsLengthAndAddsEllipsis() {
        val shortText = "Krótki tekst"
        assertEquals("Krótki tekst", AnswerText.shorten(shortText, 20))

        val longText = "To jest bardzo długie pytanie, które powinno zostać skrócone, aby zmieściło się w panelu."
        val shortened = AnswerText.shorten(longText, 30)
        assertTrue(shortened.endsWith("…"))
        assertTrue(shortened.length <= 30)
    }

    @Test
    fun letterCountSuffix_formatsPolishFormsCorrectly() {
        assertEquals("1 litera", AnswerText.letterCountSuffix(1))
        assertEquals("2 litery", AnswerText.letterCountSuffix(2))
        assertEquals("3 litery", AnswerText.letterCountSuffix(3))
        assertEquals("4 litery", AnswerText.letterCountSuffix(4))
        assertEquals("5 liter", AnswerText.letterCountSuffix(5))
        assertEquals("12 liter", AnswerText.letterCountSuffix(12))
        assertEquals("22 litery", AnswerText.letterCountSuffix(22))
    }

    @Test
    fun summary_formatsCrosswordClueWithLength() {
        val clue = DetectedQuestion(
            id = "cw1",
            number = "1",
            type = QuestionType.CROSSWORD_CLUE,
            text = "Stolica Francji",
            options = emptyList(),
            box = Box(0f, 0f, 100f, 50f),
            clue = CrosswordClue(1, Direction.ACROSS, 5),
        )
        val answer = Answer("cw1", text = "Paryż", engine = EngineId.GEMINI_NANO)
        assertEquals("PARYŻ (5 liter)", AnswerText.summary(clue, answer))
    }
}
