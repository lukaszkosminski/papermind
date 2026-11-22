package pl.lk.papermind.ai

import kotlinx.coroutines.flow.Flow
import pl.lk.papermind.model.Answer
import pl.lk.papermind.model.AnswerState
import pl.lk.papermind.model.EngineId
import pl.lk.papermind.model.ParsedPage

/** Agent B. Jeden backend AI. */
interface AnswerEngine {
    val id: EngineId
    suspend fun isAvailable(): Boolean
    /** Odpowiada hurtowo na wszystkie pytania strony (jedno wywołanie modelu). */
    suspend fun answer(page: ParsedPage): List<Answer>
}

/**
 * Agent B. Wybór silnika wg ustawień, fallback (np. brak sieci -> Nano), cache po DetectedQuestion.id,
 * deduplikacja (nie pytaj ponownie o tę samą stronę).
 */
interface AnswerService {
    /** Stan odpowiedzi per questionId. */
    val answers: Flow<Map<String, AnswerState>>
    fun submit(page: ParsedPage)
    fun clear()
}
