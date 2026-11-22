package pl.lk.papermind.ai

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import pl.lk.papermind.model.Answer
import pl.lk.papermind.model.AnswerState
import pl.lk.papermind.model.DetectedQuestion
import pl.lk.papermind.model.EngineId
import pl.lk.papermind.model.ParsedPage
import pl.lk.papermind.model.QuestionType
import pl.lk.papermind.settings.AppSettings
import pl.lk.papermind.settings.SettingsRepository

/**
 * Wybór silnika wg ustawień + fallback, cache odpowiedzi po id pytania (do silnika trafiają tylko nowe pytania),
 * natychmiastowe `Pending`, anulowanie zapytania przy zmianie strony.
 * Mapa [answers] zawiera stany pytań bieżącej strony.
 */
class AnswerServiceImpl(
    private val engines: List<AnswerEngine>,
    private val settingsRepository: SettingsRepository,
    private val scope: CoroutineScope,
) : AnswerService {

    private val state = MutableStateFlow<Map<String, AnswerState>>(emptyMap())
    override val answers: Flow<Map<String, AnswerState>> = state

    private val lock = Any()
    private val cache = HashMap<String, Answer>()
    private val pageTextHash = HashMap<String, Int>() // dla pytania `page` (id stałe, treść zmienna)
    private var currentIds: Set<String> = emptySet()
    private var currentJob: Job? = null
    private var currentPending: Set<String> = emptySet()
    private var generation = 0

    override fun submit(page: ParsedPage) {
        val questions = effectiveQuestions(page)
        if (questions.isEmpty()) return
        synchronized(lock) {
            val ids = questions.map { it.id }.toSet()
            // treść pytania `page` zmieniła się -> unieważnij cache
            var pageTextChanged = false
            questions.firstOrNull { it.id == PAGE_QUESTION_ID }?.let { q ->
                val h = q.text.hashCode()
                if (pageTextHash.put(PAGE_QUESTION_ID, h) != h) { cache.remove(PAGE_QUESTION_ID); pageTextChanged = true }
            }
            val missing = questions.filter { it.id !in cache }
            val missingIds = missing.map { it.id }.toSet()

            val samePage = ids == currentIds && !pageTextChanged
            if (!samePage) {
                currentJob?.cancel()
                currentJob = null
                currentPending = emptySet()
            }
            currentIds = ids

            // pytania już w locie (ta sama strona) nie są wysyłane ponownie
            val inFlight = if (currentJob?.isActive == true) currentPending else emptySet()
            val toSend = missing.filter { it.id !in inFlight }

            state.value = questions.associate { q ->
                q.id to (cache[q.id]?.let { AnswerState.Ready(it) }
                    ?: state.value[q.id]?.takeIf { it is AnswerState.Pending && q.id in inFlight }
                    ?: AnswerState.Pending)
            }
            if (toSend.isEmpty()) return
            if (missingIds.isNotEmpty()) currentPending = currentPending + toSend.map { it.id }

            val gen = ++generation
            val subPage = page.copy(questions = toSend)
            currentJob = scope.launch { process(gen, subPage, toSend) }
        }
    }

    override fun clear() {
        synchronized(lock) {
            generation++
            currentJob?.cancel()
            currentJob = null
            currentPending = emptySet()
            currentIds = emptySet()
            cache.clear()
            pageTextHash.clear()
            state.value = emptyMap()
        }
    }

    private suspend fun process(gen: Int, page: ParsedPage, questions: List<DetectedQuestion>) {
        try {
            val settings = settingsRepository.settings.first()
            val remaining = LinkedHashMap(questions.associateBy { it.id })
            val errors = ArrayList<String>()
            for (engine in orderedEngines(settings)) {
                if (remaining.isEmpty()) break
                val name = engineName(engine.id)
                try {
                    if (!engine.isAvailable()) {
                        errors += "$name: niedostępny"
                        continue
                    }
                    val result = engine.answer(page.copy(questions = remaining.values.toList()))
                    val valid = result.filter { it.questionId in remaining }
                    if (valid.isEmpty()) errors += "$name: brak użytecznej odpowiedzi"
                    for (a in valid) {
                        remaining.remove(a.questionId)
                        publish(gen, a.questionId, AnswerState.Ready(a), a)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    errors += e.message?.takeIf { it.isNotBlank() } ?: "$name: ${e.javaClass.simpleName}"
                }
            }
            if (remaining.isNotEmpty()) {
                val msg = if (errors.isEmpty()) "Brak skonfigurowanych silników AI" else errors.joinToString("; ")
                remaining.keys.forEach { publish(gen, it, AnswerState.Failed(msg), null) }
            }
        } finally {
            synchronized(lock) {
                if (gen == generation) { currentPending = emptySet() }
            }
        }
    }

    private fun publish(gen: Int, id: String, st: AnswerState, answer: Answer?) = synchronized(lock) {
        if (gen != generation) return
        if (answer != null) cache[id] = answer
        if (id in currentIds) state.value = state.value + (id to st)
    }

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
                options = emptyList(), box = pl.lk.papermind.model.Box(0f, 0f, 0f, 0f),
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
    }
}
