package pl.lk.papermind.ui.overlay

import pl.lk.papermind.model.Answer
import pl.lk.papermind.model.AnswerOption
import pl.lk.papermind.model.DetectedQuestion
import pl.lk.papermind.model.QuestionType

/** Czysta logika prezentacji odpowiedzi (wspólna dla nakładki i panelu). */
object AnswerText {
    val closedTypes = setOf(QuestionType.SINGLE_CHOICE, QuestionType.MULTI_CHOICE)

    private fun normLabel(s: String) = s.trim().trimEnd('.', ')', ':').uppercase()

    /** Opcje wskazane jako poprawne (porównanie etykiet bez wielkości liter i znaków ".)"). */
    fun correctOptions(q: DetectedQuestion, a: Answer): List<AnswerOption> {
        val wanted = a.correctOptionLabels.map(::normLabel).toSet()
        return q.options.filter { normLabel(it.label) in wanted }
    }

    /** Czy odpowiedź należy rysować jako zaznaczenie opcji (a nie dymek z tekstem). */
    fun isChoiceAnswer(q: DetectedQuestion, a: Answer): Boolean =
        q.type in closedTypes && a.correctOptionLabels.isNotEmpty()

    /** Krótki tekst odpowiedzi do dymka / panelu. */
    fun summary(q: DetectedQuestion, a: Answer): String {
        if (a.correctOptionLabels.isNotEmpty() && q.options.isNotEmpty()) {
            val opts = correctOptions(q, a)
            if (opts.isNotEmpty()) return opts.joinToString(", ") { "${it.label}. ${it.text}".trim() }
        }
        if (a.text.isNotBlank()) return a.text.trim()
        return a.correctOptionLabels.joinToString(", ")
    }

    /** Skrót treści pytania do listy. */
    fun shorten(text: String, max: Int = 90): String {
        val t = text.replace(Regex("\\s+"), " ").trim()
        return if (t.length <= max) t else t.take(max - 1).trimEnd() + "…"
    }
}
