package pl.lk.papermind.ai

import pl.lk.papermind.model.Answer
import pl.lk.papermind.model.AnswerState
import pl.lk.papermind.model.CrosswordGrid
import pl.lk.papermind.model.CrosswordSlot
import pl.lk.papermind.model.DetectedQuestion
import pl.lk.papermind.model.GridCell
import pl.lk.papermind.model.ParsedPage
import pl.lk.papermind.model.QuestionType

/**
 * Czysta logika krzyżówek (bez Androida): normalizacja wyrazów, wstawianie w sloty siatki, wykrywanie konfliktów
 * na skrzyżowaniach i złej długości, wzorce znanych liter oraz dwurundowe doprecyzowanie odpowiedzi.
 */
object CrosswordSolver {
    /** Łącznie rund zapytania do silnika: pierwsza + jedna poprawkowa. */
    const val MAX_ROUNDS = 2

    /** Zmniejszona pewność wyrazu o złej długości (nie da się go wstawić w siatkę). */
    const val BAD_LENGTH_CONFIDENCE = 0.2f

    fun isClue(q: DetectedQuestion) = q.type == QuestionType.CROSSWORD_CLUE && q.clue != null

    /** Wielkie litery, bez spacji, myślników, cyfr i interpunkcji; polskie znaki zachowane. */
    fun normalizeWord(s: String): String = s.uppercase().filter { it.isLetter() }

    fun slotOf(q: DetectedQuestion, grid: CrosswordGrid?): CrosswordSlot? {
        val c = q.clue ?: return null
        return grid?.slots?.firstOrNull { it.number == c.number && it.direction == c.direction }
    }

    /** Oczekiwana długość: z siatki (pewniejsza), inaczej z nawiasu w haśle. */
    fun expectedLength(q: DetectedQuestion, grid: CrosswordGrid?): Int? = slotOf(q, grid)?.length ?: q.clue?.length

    private fun key(c: GridCell) = c.row to c.col

    /** Wyraz jest poprawnie długi (lub nie ma z czym porównać). */
    fun fits(q: DetectedQuestion, word: String, grid: CrosswordGrid?): Boolean =
        word.isNotEmpty() && (expectedLength(q, grid)?.let { it == word.length } ?: true)

    /** Zapisuje znormalizowany wyraz; zła długość obniża pewność i dopisuje uwagę. */
    fun normalizeAnswer(q: DetectedQuestion, a: Answer, grid: CrosswordGrid?): Answer {
        val word = normalizeWord(a.text)
        if (fits(q, word, grid)) return a.copy(text = word)
        val exp = expectedLength(q, grid)
        val note = "Długość nie pasuje (${word.length} zamiast $exp)"
        return a.copy(
            text = word,
            confidence = minOf(a.confidence, BAD_LENGTH_CONFIDENCE),
            explanation = listOfNotNull(a.explanation, note).joinToString(" — "),
        )
    }

    class Analysis(
        /** Hasła do ponownego zapytania = [badLength] ∪ [conflicts]. */
        val problems: Set<String>,
        val badLength: Set<String>,
        /** Hasła, które przegrały spór o literę na skrzyżowaniu (niższa pewność). */
        val conflicts: Set<String>,
    )

    private class Placed(val q: DetectedQuestion, val slot: CrosswordSlot, val word: String, val conf: Float, val order: Int)

    private fun placements(
        questions: List<DetectedQuestion>,
        answers: Map<String, Answer>,
        grid: CrosswordGrid?,
    ): Pair<List<Placed>, Set<String>> {
        val placed = ArrayList<Placed>()
        val bad = LinkedHashSet<String>()
        questions.filter(::isClue).forEachIndexed { i, q ->
            val a = answers[q.id] ?: return@forEachIndexed
            val w = normalizeWord(a.text)
            if (!fits(q, w, grid)) { bad += q.id; return@forEachIndexed }
            val slot = slotOf(q, grid) ?: return@forEachIndexed
            if (slot.length != w.length) { bad += q.id; return@forEachIndexed }
            placed += Placed(q, slot, w, a.confidence, i)
        }
        return placed to bad
    }

    /** Wykrywa złą długość i konflikty liter na skrzyżowaniach; przy sporze wygrywa wyższa pewność. */
    fun analyze(questions: List<DetectedQuestion>, answers: Map<String, Answer>, grid: CrosswordGrid?): Analysis {
        val (placed, bad) = placements(questions, answers, grid)
        val byCell = HashMap<Pair<Int, Int>, MutableList<Triple<Placed, Char, Int>>>()
        for (p in placed) p.slot.cells.forEachIndexed { i, c -> byCell.getOrPut(key(c)) { ArrayList() } += Triple(p, p.word[i], i) }
        val losers = LinkedHashSet<String>()
        for (entries in byCell.values) {
            if (entries.map { it.second }.distinct().size < 2) continue
            val winner = entries.maxWith(compareBy<Triple<Placed, Char, Int>>({ it.first.conf }, { -it.first.order }))
            entries.filter { it.second != winner.second }.forEach { losers += it.first.q.id }
        }
        return Analysis(bad + losers, bad, losers)
    }

    /** Litery per komórka z podanych odpowiedzi (przy konflikcie — wyższa pewność). */
    fun letterMap(questions: List<DetectedQuestion>, answers: Map<String, Answer>, grid: CrosswordGrid?): Map<GridCell, Char> {
        val (placed, _) = placements(questions, answers, grid)
        val best = LinkedHashMap<Pair<Int, Int>, Triple<GridCell, Char, Float>>()
        for (p in placed) p.slot.cells.forEachIndexed { i, c ->
            if (c.blocked) return@forEachIndexed
            val cur = best[key(c)]
            if (cur == null || p.conf > cur.third) best[key(c)] = Triple(c, p.word[i], p.conf)
        }
        return best.values.associate { it.first to it.second }
    }

    /**
     * Uzupełnia [targets] o wzorzec znanych liter (z innych, poprawnych odpowiedzi) i długość z siatki.
     * Bez siatki zostawia hasła bez zmian (wzorzec zbudowany wyłącznie z długości robi prompt).
     */
    fun withPatterns(
        targets: List<DetectedQuestion>,
        allQuestions: List<DetectedQuestion>,
        known: Map<String, Answer>,
        grid: CrosswordGrid?,
        exclude: Set<String> = emptySet(),
    ): List<DetectedQuestion> {
        if (grid == null) return targets
        val ids = targets.map { it.id }.toSet()
        val letters = letterMap(
            allQuestions.filter { it.id !in exclude && it.id !in ids },
            known.filterKeys { it !in exclude && it !in ids },
            grid,
        ).mapKeys { key(it.key) }
        return targets.map { q ->
            val slot = slotOf(q, grid) ?: return@map q
            val old = q.clue!!.pattern?.takeIf { it.length == slot.length }
            val pattern = slot.cells.mapIndexed { i, c ->
                letters[key(c)] ?: old?.get(i)?.takeIf { it != '_' } ?: '_'
            }.joinToString("")
            q.copy(clue = q.clue.copy(length = slot.length, pattern = pattern.takeIf { p -> p.any { it != '_' } } ?: old))
        }
    }

    /** Wybór wariantu: pasująca długość > mniej konfliktów ze stałymi literami > wyższa pewność (remis → nowy). */
    fun choose(q: DetectedQuestion, old: Answer?, new: Answer?, grid: CrosswordGrid?, fixed: Map<GridCell, Char>): Answer? {
        if (old == null) return new
        if (new == null) return old
        val fixedByKey = fixed.mapKeys { key(it.key) }
        fun rank(a: Answer): Triple<Int, Int, Float> {
            val w = normalizeWord(a.text)
            val ok = if (fits(q, w, grid)) 1 else 0
            val slot = slotOf(q, grid)
            val clashes = if (ok == 1 && slot != null) slot.cells.indices.count { i -> fixedByKey[key(slot.cells[i])]?.let { it != w[i] } == true } else Int.MAX_VALUE
            return Triple(ok, -minOf(clashes, 1_000), a.confidence)
        }
        val o = rank(old)
        val n = rank(new)
        val cmp = compareValuesBy(n, o, { it.first }, { it.second }, { it.third })
        return if (cmp >= 0) new else old
    }

    /**
     * Dwurundowe rozwiązywanie: [ask] wysyła stronę do silnika. Po pierwszej odpowiedzi wykrywa konflikty/złą długość
     * i dopytuje tylko o te hasła (z wzorcem znanych liter); wynik — znormalizowane odpowiedzi na hasła ze strony.
     * Błąd drugiej rundy nie psuje wyniku pierwszej.
     */
    suspend fun solve(
        page: ParsedPage,
        known: Map<String, Answer>,
        first: List<Answer>,
        allClues: List<DetectedQuestion> = page.questions.filter(::isClue),
        ask: suspend (ParsedPage) -> List<Answer>,
    ): List<Answer> {
        val grid = page.grid
        val targetsAll = page.questions.filter(::isClue)
        val clues = (allClues + targetsAll).distinctBy { it.id }
        val ids = targetsAll.map { it.id }.toSet()
        val best = LinkedHashMap<String, Answer>()
        first.filter { it.questionId in ids }.forEach { best[it.questionId] = it }
        for (round in 2..MAX_ROUNDS) {
            val all = known + best
            val problems = analyze(clues, all, grid).problems.filter { it in best }.toSet()
            if (problems.isEmpty()) break
            val targets = withPatterns(clues.filter { it.id in problems }, clues, all, grid, exclude = problems)
            val retry = try {
                ask(page.copy(questions = targets))
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                break
            }
            val fixed = letterMap(clues.filter { it.id !in problems }, all.filterKeys { it !in problems }, grid)
            val byId = retry.associateBy { it.questionId }
            for (q in targets) {
                val chosen = choose(q, best[q.id], byId[q.id], grid, fixed) ?: continue
                best[q.id] = chosen
            }
        }
        val byQ = clues.associateBy { it.id }
        return first.map { a ->
            val q = byQ[a.questionId]
            if (q == null) a else normalizeAnswer(q, best[a.questionId] ?: a, grid)
        }
    }
}

/**
 * Litery do narysowania w komórkach siatki. Odpowiedzi o złej długości są pomijane;
 * konflikt na skrzyżowaniu → litera z odpowiedzi o wyższej pewności.
 */
fun crosswordLetters(
    grid: CrosswordGrid,
    questions: List<DetectedQuestion>,
    answers: Map<String, AnswerState>,
): Map<GridCell, Char> {
    val ready = answers.mapNotNull { (id, st) -> (st as? AnswerState.Ready)?.let { id to it.answer } }.toMap()
    return CrosswordSolver.letterMap(questions, ready, grid)
}
