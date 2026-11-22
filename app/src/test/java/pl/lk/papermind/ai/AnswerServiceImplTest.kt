package pl.lk.papermind.ai

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
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
import pl.lk.papermind.model.QuestionType
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
    private fun TestScope.service(
        vararg e: AnswerEngine,
        settings: AppSettings = AppSettings(engine = EngineId.CLOUD),
        repo: FakeSettings = FakeSettings(settings),
        timeouts: Map<EngineId, Long> = AnswerServiceImpl.DEFAULT_TIMEOUTS_MS,
    ) =
        AnswerServiceImpl(e.toList(), repo, CoroutineScope(StandardTestDispatcher(testScheduler) + Job()), timeouts = timeouts, clock = { testScheduler.currentTime })

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
        advanceTimeBy(AnswerServiceImpl.RETRY_BACKOFF_MS[0] + 1)
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

    // --- regresje po review ---

    @Test fun `#1 result of in-flight query is kept when page changes but shares questions`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val cloud = FakeEngine(EngineId.CLOUD, gate = gate)
        val s = service(cloud)
        s.submit(page(openQ("a"), openQ("b")))
        runCurrent()
        s.submit(page(openQ("a"))) // drżenie ręki: inna strona, wspólne pytanie -> bez anulowania
        runCurrent()
        assertEquals(0, cloud.cancelled)
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(setOf("a"), s.current().keys)
        assertTrue(s.current()["a"] is AnswerState.Ready)
        // "b" nie było na bieżącej stronie, ale odpowiedź trafiła do cache
        s.submit(page(openQ("a"), openQ("b")))
        assertTrue(s.current()["b"] is AnswerState.Ready)
        advanceUntilIdle()
        assertEquals(1, cloud.calls.size)
    }

    @Test fun `#1 overlapping new questions are sent without resending in-flight ones`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val cloud = FakeEngine(EngineId.CLOUD, gate = gate)
        val s = service(cloud)
        s.submit(page(openQ("a")))
        runCurrent()
        s.submit(page(openQ("a"), openQ("c")))
        runCurrent()
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf(listOf("a"), listOf("c")), cloud.calls)
        assertTrue(s.current().values.all { it is AnswerState.Ready })
    }

    @Test fun `#2 failed question is not retried within backoff, then backs off progressively`() = runTest {
        val cloud = FakeEngine(EngineId.CLOUD, failure = EngineException("HTTP 401"))
        val s = service(cloud)
        repeat(5) {
            s.submit(page(openQ("a")))
            advanceTimeBy(1_000)
        }
        advanceUntilIdle()
        assertEquals(1, cloud.calls.size)
        assertTrue(s.current()["a"] is AnswerState.Failed) // stan błędu nie miga na Pending
        advanceTimeBy(10_000)
        s.submit(page(openQ("a")))
        advanceUntilIdle()
        assertEquals(2, cloud.calls.size)
        // drugi błąd -> backoff 20 s
        advanceTimeBy(15_000)
        s.submit(page(openQ("a")))
        advanceUntilIdle()
        assertEquals(2, cloud.calls.size)
        advanceTimeBy(6_000)
        s.submit(page(openQ("a")))
        advanceUntilIdle()
        assertEquals(3, cloud.calls.size)
    }

    @Test fun `#2 settings change and clear reset backoff`() = runTest {
        val repo = FakeSettings(AppSettings(engine = EngineId.CLOUD))
        val cloud = FakeEngine(EngineId.CLOUD, failure = EngineException("HTTP 401"))
        val s = service(cloud, repo = repo)
        runCurrent()
        s.submit(page(openQ("a")))
        advanceUntilIdle()
        s.submit(page(openQ("a")))
        advanceUntilIdle()
        assertEquals(1, cloud.calls.size)
        repo.update { it.copy(cloudApiKey = "nowy-klucz") }
        runCurrent()
        s.submit(page(openQ("a")))
        advanceUntilIdle()
        assertEquals(2, cloud.calls.size)
        s.clear()
        s.submit(page(openQ("a")))
        advanceUntilIdle()
        assertEquals(3, cloud.calls.size)
    }

    @Test fun `#5 hung engine times out and falls back`() = runTest {
        val hung = FakeEngine(EngineId.OPENCODE, gate = CompletableDeferred())
        val cloud = FakeEngine(EngineId.CLOUD)
        val s = service(hung, cloud, settings = AppSettings(engine = EngineId.OPENCODE),
            timeouts = mapOf(EngineId.OPENCODE to 45_000L, EngineId.CLOUD to 30_000L))
        s.submit(page(openQ("a")))
        runCurrent()
        assertTrue(s.current()["a"] is AnswerState.Pending)
        advanceTimeBy(44_000)
        assertTrue(s.current()["a"] is AnswerState.Pending)
        advanceTimeBy(1_500)
        runCurrent()
        assertEquals(EngineId.CLOUD, (s.current()["a"] as AnswerState.Ready).answer.engine)
        assertEquals(1, hung.cancelled)
    }

    @Test fun `#5 timeout without fallback reports readable failure`() = runTest {
        val hung = FakeEngine(EngineId.OPENCODE, gate = CompletableDeferred())
        val s = service(hung, settings = AppSettings(engine = EngineId.OPENCODE, fallbackEnabled = false),
            timeouts = mapOf(EngineId.OPENCODE to 5_000L))
        s.submit(page(openQ("a")))
        advanceUntilIdle()
        assertTrue((s.current()["a"] as AnswerState.Failed).message.contains("czas"))
    }

    @Test fun `#17 fuzzy cache reuses answer for OCR-noised question with same options`() = runTest {
        val cloud = FakeEngine(EngineId.CLOUD)
        val s = service(cloud)
        s.submit(page(choiceQ("old")))
        advanceUntilIdle()
        val noisy = choiceQ("new").copy(text = "Która organeIla produkuje ATP ?") // l -> I, spacja
        s.submit(page(noisy))
        val st = s.current()["new"] as AnswerState.Ready
        assertEquals("new", st.answer.questionId)
        advanceUntilIdle()
        assertEquals(1, cloud.calls.size)
    }

    @Test fun `#17 fuzzy cache does not match different text or different options`() = runTest {
        val cloud = FakeEngine(EngineId.CLOUD)
        val s = service(cloud)
        s.submit(page(choiceQ("old")))
        advanceUntilIdle()
        val other = choiceQ("other").copy(text = "Który narząd odpowiada za filtrowanie krwi u człowieka?")
        val fewerOptions = choiceQ("fewer").let { it.copy(options = it.options.take(2)) }
        s.submit(page(other, fewerOptions))
        advanceUntilIdle()
        assertEquals(2, cloud.calls.size)
        assertEquals(listOf("other", "fewer"), cloud.calls[1])
    }

    @Test fun diceBasics() {
        assertEquals(1.0, AnswerServiceImpl.dice("Ala ma kota", "ala  MA kota!"), 1e-9)
        assertTrue(AnswerServiceImpl.dice("abcdef", "uvwxyz") < 0.1)
    }

    @Test fun `#17 fuzzy cache never merges questions that differ in digits`() = runTest {
        val cloud = FakeEngine(EngineId.CLOUD)
        val s = service(cloud)
        s.submit(page(openQ("m1", QuestionType.MATH, "Ile wynosi wynik działania 12 + 7 w systemie dziesiętnym?")))
        advanceUntilIdle()
        s.submit(page(openQ("m2", QuestionType.MATH, "Ile wynosi wynik działania 12 + 8 w systemie dziesiętnym?")))
        advanceUntilIdle()
        assertEquals(2, cloud.calls.size)
    }

    // --- krzyżówki ---
    private fun cwRespond(vararg wrong: Pair<String, String>): (ParsedPage) -> List<Answer> {
        val w = wrong.toMap()
        return { p ->
            p.questions.map { q ->
                val key = (if (q.clue!!.direction == pl.lk.papermind.model.Direction.ACROSS) "A" else "D") + q.clue!!.number
                Cw.ans(key, w[key] ?: Cw.solution.getValue(key), conf = if (key in w) 0.4f else 0.9f)
            }
        }
    }

    @Test fun `krzyzowka - cala krzyzowka jednym zapytaniem`() = runTest {
        val cloud = FakeEngine(EngineId.CLOUD, respond = cwRespond())
        val s = service(cloud)
        s.submit(Cw.page())
        advanceUntilIdle()
        assertEquals(1, cloud.calls.size)
        assertEquals(6, cloud.calls[0].size)
        assertTrue(s.current().values.all { it is AnswerState.Ready })
    }

    @Test fun `krzyzowka - konflikt wywoluje druga runde tylko dla konfliktowego hasla`() = runTest {
        var round = 0
        val cloud = FakeEngine(EngineId.CLOUD, respond = { p ->
            round++
            if (round == 1) cwRespond("D2" to "TAXAM")(p) else cwRespond()(p)
        })
        val s = service(cloud)
        s.submit(Cw.page())
        advanceUntilIdle()
        assertEquals(2, cloud.calls.size)
        assertEquals(listOf("idD2"), cloud.calls[1])
        assertEquals("TASAM", (s.current()["idD2"] as AnswerState.Ready).answer.text)
    }

    @Test fun `krzyzowka - znane odpowiedzi z cache daja wzorzec nowym haslom`() = runTest {
        val seenPatterns = ArrayList<String?>()
        val cloud = FakeEngine(EngineId.CLOUD, respond = { p ->
            p.questions.forEach { seenPatterns += it.clue?.pattern }
            cwRespond()(p)
        })
        val s = service(cloud)
        val all = Cw.clues()
        s.submit(Cw.page().copy(questions = all.filter { it.id != "idD2" }))
        advanceUntilIdle()
        seenPatterns.clear()
        s.submit(Cw.page())
        advanceUntilIdle()
        assertEquals(listOf(listOf("idD2")), cloud.calls.drop(1))
        assertEquals(listOf<String?>("T_S_M"), seenPatterns)
    }

    @Test fun `krzyzowka - timeout silnika jest podwojony`() = runTest {
        val hung = FakeEngine(EngineId.OPENCODE, gate = CompletableDeferred())
        val cloud = FakeEngine(EngineId.CLOUD, respond = cwRespond())
        val s = service(hung, cloud, settings = AppSettings(engine = EngineId.OPENCODE),
            timeouts = mapOf(EngineId.OPENCODE to 10_000L, EngineId.CLOUD to 10_000L))
        s.submit(Cw.page())
        runCurrent()
        advanceTimeBy(15_000)
        assertTrue(s.current().values.all { it is AnswerState.Pending }) // zwykły limit 10 s już minął
        advanceTimeBy(6_000)
        runCurrent()
        assertTrue(s.current().values.all { it is AnswerState.Ready })
    }

    @Test fun `krzyzowka - hasla nie sa dopasowywane rozmytym cache`() = runTest {
        val cloud = FakeEngine(EngineId.CLOUD, respond = cwRespond())
        val s = service(cloud)
        s.submit(Cw.page())
        advanceUntilIdle()
        val noisy = Cw.clues().map { it.copy(id = it.id + "x") }
        s.submit(Cw.page().copy(questions = noisy))
        advanceUntilIdle()
        assertEquals(2, cloud.calls.size)
    }
}
