package pl.lk.papermind.ai

import pl.lk.papermind.model.CrosswordGrid
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

    /** Krótki alias pytania w prompcie (zamiast 16-znakowego hasha, który małe modele przekręcają). */
    fun alias(index: Int) = "Q${index + 1}"

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
    ): Prompt {
        val crossword = page.questions.any(CrosswordSolver::isClue)
        return Prompt(system(language, compact, crossword), user(page, compact, maxRawChars, crossword))
    }

    private fun system(language: String?, compact: Boolean, crossword: Boolean): String {
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
            appendLine("- CROSSWORD_CLUE: options = [], text = the answer WORD only (see crossword rules below).")
            appendLine("For choice questions you may also fill text with the chosen option's text.")
            if (crossword) {
                appendLine("Crossword rules: for every CROSSWORD_CLUE give ONE word of EXACTLY the stated length (count the letters!), UPPERCASE, no spaces, hyphens, digits or punctuation. Polish letters (Ą Ć Ę Ł Ń Ó Ś Ź Ż) are allowed and count as one letter. The answer language is the language of the clue. If a pattern like _A__E is given, the word must match it (known letters in the same positions). Words crossing in the grid share letters — use the listed crossings to verify your answers. Put only the word in text; explanation: at most 8 words, optional.")
            }
            appendLine("confidence: number from 0.0 to 1.0. $explanationRule")
            appendLine("Output STRICT JSON only — no markdown, no code fences, no commentary — exactly in this shape:")
            append("{\"answers\":[{\"id\":\"Q1\",\"options\":[\"B\"],\"text\":\"short answer up to 12 words\",\"explanation\":\"1-2 sentences\",\"confidence\":0.9}]}")
        }
    }

    private fun user(page: ParsedPage, compact: Boolean, maxRawChars: Int, crossword: Boolean): String = buildString {
        if (crossword && page.snapshotJpeg != null) {
            appendLine("A photo of the page is attached. Use it to read the crossword grid and clues; the OCR text below may contain errors.")
            appendLine()
        }
        appendLine("QUESTIONS:")
        page.questions.forEachIndexed { i, q -> appendQuestion(alias(i), q, page.grid) }
        if (crossword) {
            val lines = crossings(page, if (compact) MAX_CROSSINGS_COMPACT else MAX_CROSSINGS)
            if (lines.isNotEmpty()) {
                appendLine("CROSSINGS (letter positions are 1-based):")
                lines.forEach { appendLine(it) }
                appendLine()
            }
        }
        val raw = truncate(page.rawText, maxRawChars)
        if (raw.isNotBlank()) {
            appendLine()
            appendLine("FULL PAGE OCR TEXT (context, may contain errors):")
            appendLine("\"\"\"")
            appendLine(raw)
            appendLine("\"\"\"")
        }
        appendLine()
        append(if (compact) "Return the JSON now." else "Return the JSON now, with one entry per question id (Q1, Q2, …) listed above.")
    }

    private fun StringBuilder.appendQuestion(alias: String, q: DetectedQuestion, grid: CrosswordGrid?) {
        append("[id=").append(alias).append(" | type=").append(q.type.name)
        if (q.clue == null) q.number?.let { append(" | no=").append(it) }
        q.clue?.let { c ->
            append(" | no=").append(c.number).append(' ').append(c.direction.name)
            CrosswordSolver.expectedLength(q, grid)?.let { append(" | length=").append(it) }
            c.pattern?.takeIf { p -> p.any { it != '_' } }?.let { append(" | pattern=").append(it) }
        }
        appendLine("]")
        appendLine(q.text.trim())
        if (q.options.isNotEmpty() && q.type != QuestionType.OPEN) {
            q.options.forEach { appendLine("${it.label}) ${it.text.trim()}") }
        }
        appendLine()
    }

    private const val MAX_CROSSINGS = 80
    private const val MAX_CROSSINGS_COMPACT = 14

    /**
     * Opis skrzyżowań siatki między hasłami ze strony, np. `Q3[3 ACROSS] letter 2 = Q1[1 DOWN] letter 4`.
     * Hasła bez wpisu w siatce są pomijane.
     */
    internal fun crossings(page: ParsedPage, limit: Int): List<String> {
        val grid = page.grid ?: return emptyList()
        val tagged = page.questions.mapIndexedNotNull { i, q ->
            val slot = CrosswordSolver.slotOf(q, grid) ?: return@mapIndexedNotNull null
            Triple(alias(i), q.clue!!, slot)
        }
        val byCell = HashMap<Pair<Int, Int>, MutableList<Pair<Int, Triple<String, pl.lk.papermind.model.CrosswordClue, pl.lk.papermind.model.CrosswordSlot>>>>()
        for (t in tagged) t.third.cells.forEachIndexed { i, c -> byCell.getOrPut(c.row to c.col) { ArrayList() } += (i + 1) to t }
        val out = ArrayList<String>()
        val seen = HashSet<String>()
        for (t in tagged) {
            t.third.cells.forEachIndexed { i, c ->
                for ((pos, other) in byCell[c.row to c.col].orEmpty()) {
                    if (other.third.direction == t.third.direction) continue
                    val a = "${t.first}[${t.second.number} ${t.second.direction.name}] letter ${i + 1}"
                    val b = "${other.first}[${other.second.number} ${other.second.direction.name}] letter $pos"
                    val line = if (t.third.direction.ordinal < other.third.direction.ordinal) "$a = $b" else "$b = $a"
                    if (seen.add(line)) out += line
                }
            }
        }
        return out.sorted().take(limit)
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
