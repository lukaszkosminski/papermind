package pl.lk.papermind.ui.fake

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import pl.lk.papermind.ai.AnswerService
import pl.lk.papermind.model.Answer
import pl.lk.papermind.model.AnswerState
import pl.lk.papermind.model.DetectedQuestion
import pl.lk.papermind.model.EngineId
import pl.lk.papermind.model.ParsedPage
import pl.lk.papermind.model.QuestionType

/** Fake: po [delayMs] zwraca deterministyczne odpowiedzi (pytania z id w [failIds] kończą się błędem). */
class FakeAnswerService(
    private val scope: CoroutineScope,
    private val delayMs: Long = 1_200,
    private val failIds: Set<String> = emptySet(),
    private val engine: EngineId = EngineId.GEMINI_NANO,
) : AnswerService {

    private val state = MutableStateFlow<Map<String, AnswerState>>(emptyMap())
    private val jobs = HashMap<String, Job>()
    override val answers: Flow<Map<String, AnswerState>> = state

    @Synchronized
    override fun submit(page: ParsedPage) {
        for (q in page.questions) {
            if (state.value.containsKey(q.id)) continue // dedup
            state.update { it + (q.id to AnswerState.Pending) }
            jobs[q.id] = scope.launch {
                delay(delayMs)
                val result = if (q.id in failIds) AnswerState.Failed("Symulowany błąd") else AnswerState.Ready(answerFor(q))
                state.update { it + (q.id to result) }
            }
        }
    }

    @Synchronized
    override fun clear() {
        jobs.values.forEach(Job::cancel)
        jobs.clear()
        state.value = emptyMap()
    }

    private fun answerFor(q: DetectedQuestion): Answer = when (q.type) {
        QuestionType.SINGLE_CHOICE -> Answer(q.id, listOf(q.options.getOrNull(1)?.label ?: "A"),
            explanation = "Przykładowe uzasadnienie wyboru.", confidence = 0.92f, engine = engine)
        QuestionType.MULTI_CHOICE -> Answer(q.id, q.options.filterIndexed { i, _ -> i % 2 == 0 }.map { it.label },
            explanation = "Przykładowe uzasadnienie.", confidence = 0.8f, engine = engine)
        QuestionType.OPEN -> Answer(q.id, text = "Proces, w którym rośliny zamieniają światło, wodę i CO₂ w glukozę i tlen.",
            explanation = "Zachodzi w chloroplastach.", confidence = 0.7f, engine = engine)
        QuestionType.MATH -> Answer(q.id, text = "89", explanation = "12·7 = 84, 84 + 5 = 89.", confidence = 0.99f, engine = engine)
        QuestionType.FORM_FIELD -> Answer(q.id, text = "Jan Kowalski", confidence = 0.4f, engine = engine)
    }
}
