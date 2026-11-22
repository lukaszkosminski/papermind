package pl.lk.papermind.ai

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import pl.lk.papermind.model.Answer
import pl.lk.papermind.model.AnswerState
import pl.lk.papermind.model.Box
import pl.lk.papermind.model.DetectedQuestion
import pl.lk.papermind.model.EngineId
import pl.lk.papermind.model.ParsedPage
import pl.lk.papermind.model.QuestionType
import pl.lk.papermind.settings.AppSettings
import pl.lk.papermind.settings.SettingsRepository

/**
 * Wybór silnika wg ustawień + fallback (z limitem czasu na silnik), cache odpowiedzi po id pytania
 * (z rozmytym dopasowaniem przy szumie OCR), natychmiastowe `Pending`, backoff dla `Failed`.
 *
 * - Wynik zapytania zawsze trafia do cache — także gdy użytkownik zdążył przejść na inną stronę.
 * - Zapytanie w locie jest anulowane tylko, gdy nowa strona nie zawiera żadnego z jego pytań
 *   (drżenie ręki generuje nowe strony z tymi samymi pytaniami).
 * - Pytania zakończone błędem wracają do silnika dopiero po backoffie ([RETRY_BACKOFF_MS]); reset przy [clear] i zmianie ustawień.
 * Mapa [answers] zawiera stany pytań bieżącej strony.
 */
class AnswerServiceImpl(
    private val engines: List<AnswerEngine>,
    private val settingsRepository: SettingsRepository,
    private val scope: CoroutineScope,
    private val timeouts: Map<EngineId, Long> = DEFAULT_TIMEOUTS_MS,
    private val clock: () -> Long = System::currentTimeMillis,
) : AnswerService {

    private class Cached(val answer: Answer, val text: String, val labels: List<String>)
    private class Failure(val message: String, val attempts: Int, val retryAt: Long)

    private val state = MutableStateFlow<Map<String, AnswerState>>(emptyMap())
    override val answers: Flow<Map<String, AnswerState>> = state

    private val lock = Any()
    private val cache = HashMap<String, Cached>()
    private val failures = HashMap<String, Failure>()
    private val inFlight = HashMap<String, Job>()
    private var currentIds: Set<String> = emptySet()
    private var pageTextHash: Int? = null
    private var epoch = 0

    init {
        // zmiana ustawień (np. nowy klucz API) kasuje backoff — błędne pytania można ponowić od razu
        scope.launch {
            settingsRepository.settings.distinctUntilChanged().drop(1).collect {
                synchronized(lock) { failures.clear() }
            }
        }
    }

    override fun submit(page: ParsedPage) {
        val questions = effectiveQuestions(page)
        if (questions.isEmpty()) return
        synchronized(lock) {
            val ids = questions.map { it.id }.toSet()
            val now = clock()

            // treść pytania `page` (id stałe) zmieniła się -> unieważnij
            if (PAGE_QUESTION_ID in ids) {
                val h = questions.first { it.id == PAGE_QUESTION_ID }.text.hashCode()
                if (pageTextHash != null && pageTextHash != h) {
                    cache.remove(PAGE_QUESTION_ID)
                    failures.remove(PAGE_QUESTION_ID)
                    inFlight.remove(PAGE_QUESTION_ID)?.cancel()
                }
                pageTextHash = h
            }

            // zapytania bez żadnego pytania z nowej strony są nieaktualne
            inFlight.values.distinct().forEach { job ->
                val jobIds = inFlight.filterValues { it === job }.keys
                if (jobIds.none { it in ids }) {
                    job.cancel()
                    jobIds.forEach { inFlight.remove(it) }
                }
            }
            currentIds = ids

            questions.forEach { q -> if (q.id !in cache) fuzzyLookup(q)?.let { cache[q.id] = Cached(it.answer.copy(questionId = q.id), q.text, labels(q)) } }

            val next = LinkedHashMap<String, AnswerState>()
            val toSend = ArrayList<DetectedQuestion>()
            for (q in questions) {
                val cached = cache[q.id]
                val fail = failures[q.id]
                when {
                    cached != null -> next[q.id] = AnswerState.Ready(cached.answer)
                    q.id in inFlight -> next[q.id] = AnswerState.Pending
                    fail != null && now < fail.retryAt -> next[q.id] = AnswerState.Failed(fail.message)
                    else -> { next[q.id] = AnswerState.Pending; toSend += q }
                }
            }
            state.value = next
            if (toSend.isEmpty()) return

            val myEpoch = epoch
            // Krzyżówka: całe hasła naraz; odpowiedzi znane z cache służą solverowi (wzorce liter, konflikty)
            val known = questions.filter(CrosswordSolver::isClue).mapNotNull { q -> cache[q.id]?.let { q.id to it.answer } }.toMap()
            val sub = page.copy(questions = toSend)
            val job = scope.launch(start = CoroutineStart.LAZY) { process(myEpoch, sub, toSend, known, questions.filter(CrosswordSolver::isClue)) }
            toSend.forEach { inFlight[it.id] = job }
            job.invokeOnCompletion {
                synchronized(lock) { inFlight.entries.removeAll { it.value === job } }
            }
            job.start()
        }
    }

    override fun clear() {
        synchronized(lock) {
            epoch++
            inFlight.values.distinct().forEach { it.cancel() }
            inFlight.clear()
            currentIds = emptySet()
            pageTextHash = null
            cache.clear()
            failures.clear()
            state.value = emptyMap()
        }
    }

    private suspend fun process(myEpoch: Int, page: ParsedPage, questions: List<DetectedQuestion>, initialKnown: Map<String, Answer>, pageClues: List<DetectedQuestion>) {
        val settings = settingsRepository.settings.first()
        val remaining = LinkedHashMap(questions.associateBy { it.id })
        val errors = ArrayList<String>()
        val known = HashMap(initialKnown)
        for (engine in orderedEngines(settings)) {
            if (remaining.isEmpty()) break
            val name = engineName(engine.id)
            val hasClues = remaining.values.any(CrosswordSolver::isClue)
            val limit = (timeouts[engine.id] ?: DEFAULT_TIMEOUT_MS) * (if (hasClues) CROSSWORD_TIMEOUT_FACTOR else 1)
            try {
                val available = withTimeoutOrNull(AVAILABILITY_TIMEOUT_MS) { engine.isAvailable() }
                if (available != true) {
                    errors += if (available == null) "$name: brak odpowiedzi (sprawdzanie dostępności)" else "$name: niedostępny"
                    continue
                }
                val result = withTimeoutOrNull(limit) {
                    val targets = CrosswordSolver.withPatterns(remaining.values.toList(), pageClues, known, page.grid)
                    val req = page.copy(questions = targets)
                    val first = engine.answer(req)
                    if (hasClues) CrosswordSolver.solve(req, known, first, pageClues) { engine.answer(it) } else first
                }
                if (result == null) {
                    errors += "$name: przekroczono czas oczekiwania (${limit / 1000} s)"
                    continue
                }
                val valid = result.filter { it.questionId in remaining }
                if (valid.isEmpty()) errors += "$name: brak użytecznej odpowiedzi"
                for (a in valid) {
                    val q = remaining.remove(a.questionId) ?: continue
                    if (CrosswordSolver.isClue(q)) known[q.id] = a
                    publishReady(myEpoch, q, a)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                errors += e.message?.takeIf { it.isNotBlank() } ?: "$name: ${e.javaClass.simpleName}"
            }
        }
        if (remaining.isNotEmpty()) {
            val msg = if (errors.isEmpty()) "Brak skonfigurowanych silników AI" else errors.joinToString("; ")
            remaining.keys.forEach { publishFailed(myEpoch, it, msg) }
        }
    }

    /** Odpowiedź zawsze do cache (o ile nie było [clear]); do UI tylko gdy pytanie jest na bieżącej stronie. */
    private fun publishReady(myEpoch: Int, q: DetectedQuestion, a: Answer) = synchronized(lock) {
        if (myEpoch != epoch) return
        cache[q.id] = Cached(a, q.text, labels(q))
        failures.remove(q.id)
        if (q.id in currentIds) state.value = state.value + (q.id to AnswerState.Ready(a))
    }

    private fun publishFailed(myEpoch: Int, id: String, message: String) = synchronized(lock) {
        if (myEpoch != epoch) return
        val attempts = (failures[id]?.attempts ?: 0) + 1
        val delay = RETRY_BACKOFF_MS[minOf(attempts, RETRY_BACKOFF_MS.size) - 1]
        failures[id] = Failure(message, attempts, clock() + delay)
        if (id in currentIds) state.value = state.value + (id to AnswerState.Failed(message))
    }

    /** Pytanie bez trafienia po id, ale o niemal identycznej treści i opcjach (szum OCR zmienił hash id). */
    private fun fuzzyLookup(q: DetectedQuestion): Cached? {
        if (q.id == PAGE_QUESTION_ID || q.type == QuestionType.CROSSWORD_CLUE) return null // hasła krzyżówki: id zawiera numer+treść
        val labels = labels(q)
        var best: Cached? = null
        var bestScore = FUZZY_THRESHOLD
        for (c in cache.values) {
            if (c.labels != labels) continue
            if (digits(c.text) != digits(q.text)) continue // "12+7" vs "12+8" to różne pytania
            val score = dice(c.text, q.text)
            if (score >= bestScore) { best = c; bestScore = score }
        }
        return best
    }

    private fun digits(t: String) = t.filter { it.isDigit() }

    private fun labels(q: DetectedQuestion) = q.options.map { it.label.trim().trimEnd('.', ')').uppercase() }

    private fun orderedEngines(s: AppSettings): List<AnswerEngine> {
        val preferred = engines.filter { it.id == s.engine }
        if (!s.fallbackEnabled) return preferred
        return preferred + engines.filter { it.id != s.engine }.sortedBy { it.id.ordinal }
    }

    private fun effectiveQuestions(page: ParsedPage): List<DetectedQuestion> {
        if (page.questions.isNotEmpty()) return page.questions
        val text = page.rawText.trim()
        if (text.isEmpty()) return emptyList()
        return listOf(
            DetectedQuestion(
                id = PAGE_QUESTION_ID, number = null, type = QuestionType.OPEN, text = text,
                options = emptyList(), box = Box(0f, 0f, 0f, 0f),
            ),
        )
    }

    private fun engineName(id: EngineId) = when (id) {
        EngineId.GEMINI_NANO -> "Gemini Nano"
        EngineId.OPENCODE -> "OpenCode"
        EngineId.CLOUD -> "Chmura"
    }

    companion object {
        const val PAGE_QUESTION_ID = "page"
        const val DEFAULT_TIMEOUT_MS = 30_000L
        const val AVAILABILITY_TIMEOUT_MS = 8_000L
        const val FUZZY_THRESHOLD = 0.85
        /** Krzyżówka = więcej haseł + druga runda poprawkowa, więc limit czasu na silnik ×2. */
        const val CROSSWORD_TIMEOUT_FACTOR = 2
        val DEFAULT_TIMEOUTS_MS = mapOf(
            EngineId.GEMINI_NANO to 20_000L,
            EngineId.OPENCODE to 45_000L,
            EngineId.CLOUD to 30_000L,
        )
        /** Odstępy przed ponowieniem nieudanego pytania: 1. błąd → 10 s, 2. → 20 s, kolejne → 60 s. */
        val RETRY_BACKOFF_MS = longArrayOf(10_000, 20_000, 60_000)

        /** Współczynnik Dice'a na bigramach znaków (po normalizacji do liter/cyfr). */
        internal fun dice(a: String, b: String): Double {
            val x = a.lowercase().filter { it.isLetterOrDigit() }
            val y = b.lowercase().filter { it.isLetterOrDigit() }
            if (x == y) return 1.0
            if (x.length < 2 || y.length < 2) return 0.0
            val counts = HashMap<String, Int>()
            for (i in 0 until x.length - 1) counts.merge(x.substring(i, i + 2), 1, Int::plus)
            var overlap = 0
            for (i in 0 until y.length - 1) {
                val g = y.substring(i, i + 2)
                val c = counts[g] ?: 0
                if (c > 0) { counts[g] = c - 1; overlap++ }
            }
            return 2.0 * overlap / ((x.length - 1) + (y.length - 1))
        }
    }
}
