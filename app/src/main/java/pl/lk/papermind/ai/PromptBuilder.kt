package pl.lk.papermind.ai

import pl.lk.papermind.model.DetectedQuestion
import pl.lk.papermind.model.ParsedPage
import pl.lk.papermind.model.QuestionType

/** Prompt dla jednego wywołania modelu obejmującego całą stronę. */
data class Prompt(val system: String, val user: String) {
    /** Dla silników bez osobnego pola system prompt. */
    val combined: String get() = system + "\n\n" + user
}

object PromptBuilder {
    const val DEFAULT_MAX_RAW_CHARS = 6000

    private val languageNames = mapOf(
        "pl" to "Polish", "en" to "English", "de" to "German", "fr" to "French",
        "es" to "Spanish", "it" to "Italian", "uk" to "Ukrainian", "cs" to "Czech",
    )

    /**
     * @param language kod języka z ustawień (np. "pl"); null/pusty = język pytania.
     * @param compact tryb dla małych modeli on-device: krótsze instrukcje i uzasadnienia.
     * @param maxRawChars limit znaków surowego tekstu strony (kontekst).
     */
    fun build(
        page: ParsedPage,
        language: String? = null,
        compact: Boolean = false,
        maxRawChars: Int = DEFAULT_MAX_RAW_CHARS,
    ): Prompt = Prompt(system(language, compact), user(page, compact, maxRawChars))

    private fun system(language: String?, compact: Boolean): String {
        val lang = language?.takeIf { it.isNotBlank() }?.let { languageNames[it.lowercase()] ?: it }
        val langRule = if (lang != null) "Write all answers in $lang (the question language wins if it differs clearly)."
        else "Write all answers in the language of the question."
        val explanationRule = if (compact) "explanation: at most 1 short sentence." else "explanation: 1-2 sentences."
        return buildString {
            appendLine("You are an expert tutor solving a test or form seen through a phone camera. The text comes from OCR and may contain errors (broken words, wrong digits, merged lines) — silently correct them using context.")
            appendLine("Answer EVERY listed question. Be brief and accurate. Do not use tools. $langRule")
            appendLine("Rules per question type:")
            appendLine("- SINGLE_CHOICE: options = exactly one option label (e.g. [\"B\"]).")
            appendLine("- MULTI_CHOICE: options = all correct option labels (e.g. [\"A\",\"C\"]).")
            appendLine("- OPEN: options = [], text = short answer (max 12 words).")
            appendLine("- MATH: options = [], text = the final result only, explanation = the key steps.")
            appendLine("- FORM_FIELD: options = [], text = what to write into the field / the expected format (max 12 words).")
            appendLine("For choice questions you may also fill text with the chosen option's text.")
            appendLine("confidence: number from 0.0 to 1.0. $explanationRule")
            appendLine("Output STRICT JSON only — no markdown, no code fences, no commentary — exactly in this shape:")
            append("{\"answers\":[{\"id\":\"<question id>\",\"options\":[\"B\"],\"text\":\"short answer up to 12 words\",\"explanation\":\"1-2 sentences\",\"confidence\":0.9}]}")
        }
    }

    private fun user(page: ParsedPage, compact: Boolean, maxRawChars: Int): String = buildString {
        appendLine("QUESTIONS:")
        page.questions.forEach { appendQuestion(it) }
        val raw = truncate(page.rawText, maxRawChars)
        if (raw.isNotBlank()) {
            appendLine()
            appendLine("FULL PAGE OCR TEXT (context, may contain errors):")
            appendLine("\"\"\"")
            appendLine(raw)
            appendLine("\"\"\"")
        }
        appendLine()
        append(if (compact) "Return the JSON now." else "Return the JSON now, with one entry per question id listed above.")
    }

    private fun StringBuilder.appendQuestion(q: DetectedQuestion) {
        append("[id=").append(q.id).append(" | type=").append(q.type.name)
        q.number?.let { append(" | no=").append(it) }
        appendLine("]")
        appendLine(q.text.trim())
        if (q.options.isNotEmpty() && q.type != QuestionType.OPEN) {
            q.options.forEach { appendLine("${it.label}) ${it.text.trim()}") }
        }
        appendLine()
    }

    /** Ucina tekst do [max] znaków, preferując granicę linii; dodaje znacznik ucięcia. */
    fun truncate(text: String, max: Int): String {
        val t = text.trim()
        if (max <= 0) return ""
        if (t.length <= max) return t
        val cut = t.take(max)
        val nl = cut.lastIndexOf('\n')
        val base = if (nl > max / 2) cut.take(nl) else cut
        return base.trimEnd() + "\n[…]"
    }
}
