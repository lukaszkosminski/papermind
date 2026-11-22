package pl.lk.papermind.ai

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import pl.lk.papermind.model.Answer
import pl.lk.papermind.model.AnswerState
import pl.lk.papermind.model.EngineId
import pl.lk.papermind.model.ParsedPage
import pl.lk.papermind.settings.AppSettings
import pl.lk.papermind.settings.SettingsRepository

class FakeSettings(initial: AppSettings = AppSettings()) : SettingsRepository {
    val state = MutableStateFlow(initial)
    override val settings = state
    override suspend fun update(transform: (AppSettings) -> AppSettings) { state.value = transform(state.value) }
}

class FakeEngine(
    override val id: EngineId,
    var available: Boolean = true,
    var failure: Throwable? = null,
    var gate: CompletableDeferred<Unit>? = null,
    var respond: (ParsedPage) -> List<Answer> = { p -> p.questions.map { Answer(it.id, text = "ans-${it.id}", engine = EngineId.CLOUD) } },
) : AnswerEngine {
    val calls = ArrayList<List<String>>()
    var cancelled = 0
    override suspend fun isAvailable() = available
    override suspend fun answer(page: ParsedPage): List<Answer> {
        calls += page.questions.map { it.id }
        try {
            gate?.await()
        } catch (e: kotlinx.coroutines.CancellationException) {
            cancelled++
            throw e
        }
        failure?.let { throw it }
        return respond(page).map { it.copy(engine = id) }
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class AnswerServiceImplTest {
    private fun TestScope.service(vararg e: AnswerEngine, settings: AppSettings = AppSettings(engine = EngineId.CLOUD)) =
        AnswerServiceImpl(e.toList(), FakeSettings(settings), this)

    private suspend fun AnswerServiceImpl.current() = answers.first()

    @Test fun pendingImmediatelyThenReady() = runTest {
        val cloud = FakeEngine(EngineId.CLOUD)
        val s = service(cloud)
        s.submit(page(openQ("a"), openQ("b")))
        assertTrue(s.current().values.all { it is AnswerState.Pending })
        advanceUntilIdle()
        val m = s.current()
        assertEquals("ans-a", (m["a"] as AnswerState.Ready).answer.text)
        assertEquals(EngineId.CLOUD, (m["b"] as AnswerState.Ready).answer.engine)
        assertEquals(1, cloud.calls.size) // jedno wywołanie na stronę
    }

    @Test fun cachedQuestionsNotResent_onlyNewOnes() = runTest {
        val cloud = FakeEngine(EngineId.CLOUD)
        val s = service(cloud)
        s.submit(page(openQ("a")))
        advanceUntilIdle()
        s.submit(page(openQ("a"), openQ("b")))
        assertTrue(s.current()["a"] is AnswerState.Ready) // z cache od razu
        assertTrue(s.current()["b"] is AnswerState.Pending)
        advanceUntilIdle()
        assertEquals(listOf(listOf("a"), listOf("b")), cloud.calls)
        // identyczna strona: brak ponownego zapytania
        s.submit(page(openQ("a"), openQ("b")))
        advanceUntilIdle()
        assertEquals(2, cloud.calls.size)
    }

    @Test fun duplicateSubmitWhileInFlightIgnored() = runTest {
        val gate = CompletableDeferred<Unit>()
        val cloud = FakeEngine(EngineId.CLOUD, gate = gate)
        val s = service(cloud)
        s.submit(page(openQ("a")))
        runCurrent()
        s.submit(page(openQ("a")))
        runCurrent()
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(1, cloud.calls.size)
        assertTrue(s.current()["a"] is AnswerState.Ready)
    }

    @Test fun newDifferentPageCancelsPrevious() = runTest {
        val gate = CompletableDeferred<Unit>()
        val cloud = FakeEngine(EngineId.CLOUD, gate = gate)
        val s = service(cloud)
        s.submit(page(openQ("a")))
        runCurrent()
        s.submit(page(openQ("z")))
        runCurrent()
        assertEquals(1, cloud.cancelled)
        gate.complete(Unit)
        advanceUntilIdle()
        val m = s.current()
        assertEquals(setOf("z"), m.keys)
        assertTrue(m["z"] is AnswerState.Ready)
    }

    @Test fun fallbackToNextEngineOnFailureAndUnavailable() = runTest {
        val nano = FakeEngine(EngineId.GEMINI_NANO, available = false)
        val oc = FakeEngine(EngineId.OPENCODE, failure = EngineException("OpenCode: HTTP 500"))
        val cloud = FakeEngine(EngineId.CLOUD)
        val s = service(nano, oc, cloud, settings = AppSettings(engine = EngineId.GEMINI_NANO))
        s.submit(page(openQ("a")))
        advanceUntilIdle()
        assertEquals(EngineId.CLOUD, (s.current()["a"] as AnswerState.Ready).answer.engine)
        assertEquals(0, nano.calls.size)
        assertEquals(1, oc.calls.size)
    }

    @Test fun preferredEngineFirst() = runTest {
        val nano = FakeEngine(EngineId.GEMINI_NANO)
        val cloud = FakeEngine(EngineId.CLOUD)
        val s = service(nano, cloud, settings = AppSettings(engine = EngineId.CLOUD))
        s.submit(page(openQ("a")))
        advanceUntilIdle()
        assertEquals(1, cloud.calls.size)
        assertEquals(0, nano.calls.size)
    }

    @Test fun noFallbackWhenDisabled_failedWithMessage() = runTest {
        val oc = FakeEngine(EngineId.OPENCODE, failure = EngineException("OpenCode: serwer padł"))
        val cloud = FakeEngine(EngineId.CLOUD)
        val s = service(oc, cloud, settings = AppSettings(engine = EngineId.OPENCODE, fallbackEnabled = false))
        s.submit(page(openQ("a")))
        advanceUntilIdle()
        val st = s.current()["a"] as AnswerState.Failed
        assertTrue(st.message.contains("serwer padł"))
        assertEquals(0, cloud.calls.size)
    }

    @Test fun allEnginesUnavailableGivesReadableFailure() = runTest {
        val s = service(FakeEngine(EngineId.CLOUD, available = false))
        s.submit(page(openQ("a")))
        advanceUntilIdle()
        assertTrue((s.current()["a"] as AnswerState.Failed).message.contains("niedostępny"))
    }

    @Test fun missingAnswersFallBackToNextEngine() = runTest {
        val oc = FakeEngine(EngineId.OPENCODE, respond = { p -> p.questions.take(1).map { Answer(it.id, text = "oc", engine = EngineId.OPENCODE) } })
        val cloud = FakeEngine(EngineId.CLOUD)
        val s = service(oc, cloud, settings = AppSettings(engine = EngineId.OPENCODE))
        s.submit(page(openQ("a"), openQ("b")))
        advanceUntilIdle()
        val m = s.current()
        assertEquals(EngineId.OPENCODE, (m["a"] as AnswerState.Ready).answer.engine)
        assertEquals(EngineId.CLOUD, (m["b"] as AnswerState.Ready).answer.engine)
        assertEquals(listOf("b"), cloud.calls.single())
    }

    @Test fun failedQuestionsAreRetriedOnNextSubmit() = runTest {
        val cloud = FakeEngine(EngineId.CLOUD, failure = EngineException("boom"))
        val s = service(cloud)
        s.submit(page(openQ("a")))
        advanceUntilIdle()
        assertTrue(s.current()["a"] is AnswerState.Failed)
        cloud.failure = null
        s.submit(page(openQ("a")))
        advanceUntilIdle()
        assertTrue(s.current()["a"] is AnswerState.Ready)
    }

    @Test fun pageWithoutQuestionsUsesRawTextAsOpenQuestion() = runTest {
        val cloud = FakeEngine(EngineId.CLOUD)
        val s = service(cloud)
        s.submit(page(raw = "Jakiś tekst bez numeracji"))
        advanceUntilIdle()
        assertEquals(setOf("page"), s.current().keys)
        assertTrue(s.current()["page"] is AnswerState.Ready)
    }

    @Test fun pageWithoutQuestionsAndTextIgnored() = runTest {
        val cloud = FakeEngine(EngineId.CLOUD)
        val s = service(cloud)
        s.submit(page(raw = "  "))
        advanceUntilIdle()
        assertTrue(s.current().isEmpty())
        assertEquals(0, cloud.calls.size)
    }

    @Test fun rawTextChangeRefreshesPageQuestion() = runTest {
        val cloud = FakeEngine(EngineId.CLOUD)
        val s = service(cloud)
        s.submit(page(raw = "tekst 1"))
        advanceUntilIdle()
        s.submit(page(raw = "tekst 2"))
        advanceUntilIdle()
        assertEquals(2, cloud.calls.size)
    }

    @Test fun clearResetsStateAndCache() = runTest {
        val cloud = FakeEngine(EngineId.CLOUD)
        val s = service(cloud)
        s.submit(page(openQ("a")))
        advanceUntilIdle()
        s.clear()
        assertTrue(s.current().isEmpty())
        s.submit(page(openQ("a")))
        advanceUntilIdle()
        assertEquals(2, cloud.calls.size)
    }

    @Test fun noEnginesConfigured() = runTest {
        val s = service()
        s.submit(page(openQ("a")))
        advanceUntilIdle()
        assertTrue((s.current()["a"] as AnswerState.Failed).message.contains("silników"))
    }
}
