package pl.lk.papermind.ui.fake

import kotlinx.coroutines.delay
import pl.lk.papermind.ai.AnswerEngine
import pl.lk.papermind.model.Answer
import pl.lk.papermind.model.EngineId
import pl.lk.papermind.model.ParsedPage

/** Fake silnik: konfigurowalna dostępność (do „Testuj połączenie") i proste odpowiedzi. */
class FakeEngine(
    override val id: EngineId,
    var available: Boolean = true,
    private val latencyMs: Long = 300,
) : AnswerEngine {
    override suspend fun isAvailable(): Boolean {
        delay(latencyMs)
        return available
    }

    override suspend fun answer(page: ParsedPage): List<Answer> = page.questions.map { q ->
        Answer(q.id, correctOptionLabels = q.options.firstOrNull()?.let { listOf(it.label) } ?: emptyList(),
            text = if (q.options.isEmpty()) "Fake" else "", confidence = 0.5f, engine = id)
    }
}
