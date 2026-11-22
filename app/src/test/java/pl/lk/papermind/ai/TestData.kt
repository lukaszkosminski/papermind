package pl.lk.papermind.ai

import pl.lk.papermind.model.AnswerOption
import pl.lk.papermind.model.Box
import pl.lk.papermind.model.DetectedQuestion
import pl.lk.papermind.model.ParsedPage
import pl.lk.papermind.model.QuestionType

val ZERO_BOX = Box(0f, 0f, 0f, 0f)

fun choiceQ(id: String = "q1", type: QuestionType = QuestionType.SINGLE_CHOICE) = DetectedQuestion(
    id = id, number = "1", type = type, text = "Która organella produkuje ATP?",
    options = listOf(
        AnswerOption("A", "Jądro", ZERO_BOX),
        AnswerOption("B", "Mitochondrium", ZERO_BOX),
        AnswerOption("C", "Rybosom", ZERO_BOX),
    ),
    box = ZERO_BOX,
)

fun openQ(id: String, type: QuestionType = QuestionType.OPEN, text: String = "Stolica Polski?") =
    DetectedQuestion(id, null, type, text, emptyList(), ZERO_BOX)

fun page(vararg q: DetectedQuestion, raw: String = "raw text") = ParsedPage(0L, q.toList(), raw)
