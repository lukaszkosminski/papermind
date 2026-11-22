package pl.lk.papermind.ui

import androidx.camera.view.LifecycleCameraController
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import pl.lk.papermind.AppContainer
import pl.lk.papermind.ai.AnswerEngine
import pl.lk.papermind.ai.AnswerService
import pl.lk.papermind.model.AnswerState
import pl.lk.papermind.model.Box
import pl.lk.papermind.model.EngineId
import pl.lk.papermind.model.OcrFrame
import pl.lk.papermind.model.ParsedPage
import pl.lk.papermind.ocr.OcrPipeline
import pl.lk.papermind.settings.AppSettings
import pl.lk.papermind.settings.SettingsRepository
import pl.lk.papermind.ui.tracking.OverlayTracker
import pl.lk.papermind.ui.tracking.TrackedQuestion
import java.util.concurrent.Executor

/**
 * Łączy OcrPipeline (strony + klatki) z AnswerService i buduje [UiState].
 * Pauza ([toggleFreeze]) ignoruje nowe klatki i strony — nakładka zostaje na ostatnich pozycjach.
 */
class MainViewModel(
    private val ocr: OcrPipeline,
    private val answerService: AnswerService,
    private val settingsRepository: SettingsRepository,
    private val engines: Map<EngineId, AnswerEngine> = emptyMap(),
    /** Dispatcher liczenia dopasowań nakładki (w testach: testowy). */
    trackingDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val tracker: OverlayTracker = OverlayTracker(),
    private val connectionTimeoutMs: Long = 15_000,
) : ViewModel() {

    private val frozen = MutableStateFlow(false)
    private val selected = MutableStateFlow<String?>(null)
    private val currentPage = MutableStateFlow<ParsedPage?>(null)
    private val showLineOutlines = MutableStateFlow(false)
    private val connection = MutableStateFlow<ConnectionTest>(ConnectionTest.Idle)

    private class Tracking(val lines: List<Box>, val tracked: Map<String, TrackedQuestion>)

    private val tracking: Flow<Tracking> = combine(
        currentPage,
        ocr.frames.filter { !frozen.value }.onStart { emit(OcrFrame(0, emptyList())) },
    ) { page, frame ->
        val questions = page?.questions.orEmpty()
        Tracking(
            lines = frame.lines.map { it.box },
            tracked = tracker.update(questions, frame).associateBy { it.questionId },
        )
    }.conflate().flowOn(trackingDispatcher)

    private class Content(val items: List<QuestionUi>, val lines: List<Box>)

    private val content: Flow<Content> = combine(currentPage, tracking, answerService.answers) { page, tr, answers ->
        val items = page?.questions.orEmpty().map { q ->
            QuestionUi(q, answers[q.id] ?: AnswerState.Pending, tr.tracked[q.id])
        }
        Content(items, tr.lines)
    }

    val uiState: StateFlow<UiState> = combine(
        content,
        combine(selected, frozen, showLineOutlines, connection) { sel, fr, lines, conn ->
            arrayOf<Any?>(sel, fr, lines, conn)
        },
        settingsRepository.settings,
    ) { c, ctl, settings ->
        val fr = ctl[1] as Boolean
        val active = c.items.firstNotNullOfOrNull { (it.state as? AnswerState.Ready)?.answer?.engine }
        UiState(
            items = c.items,
            lineBoxes = c.lines,
            selectedId = ctl[0] as String?,
            frozen = fr,
            showLineOutlines = ctl[2] as Boolean,
            settings = settings,
            connection = ctl[3] as ConnectionTest,
            status = statusOf(fr, c.items),
            activeEngine = active ?: settings.engine,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UiState())

    init {
        viewModelScope.launch {
            ocr.pages.collect { page ->
                if (!frozen.value) {
                    currentPage.value = page
                    selected.update { id -> id?.takeIf { sel -> page.questions.any { it.id == sel } } }
                    answerService.submit(page)
                }
            }
        }
    }

    fun bindCamera(controller: LifecycleCameraController, executor: Executor) = ocr.bind(controller, executor)

    fun unbindCamera(controller: LifecycleCameraController) = ocr.unbind(controller)

    fun toggleFreeze() = frozen.update { !it }

    fun toggleLineOutlines() = showLineOutlines.update { !it }

    /** Tap na pytaniu w panelu: podświetla je na nakładce (ponowny tap zdejmuje podświetlenie). */
    fun select(questionId: String) = selected.update { if (it == questionId) null else questionId }

    fun clear() {
        answerService.clear()
        currentPage.value = null
        selected.value = null
        tracker.reset()
    }

    fun updateSettings(transform: (AppSettings) -> AppSettings) {
        viewModelScope.launch { settingsRepository.update(transform) }
        connection.value = ConnectionTest.Idle
    }

    /** „Testuj połączenie" — sprawdza [AnswerEngine.isAvailable] silnika wybranego w ustawieniach. */
    fun testConnection() {
        if (connection.value == ConnectionTest.Running) return
        connection.value = ConnectionTest.Running
        viewModelScope.launch {
            val engineId = settingsRepository.settings.first().engine
            val engine = engines[engineId]
            connection.value = if (engine == null) {
                ConnectionTest.Result(false, "Brak silnika $engineId")
            } else try {
                val ok = withTimeoutOrNull(connectionTimeoutMs) { engine.isAvailable() }
                when (ok) {
                    true -> ConnectionTest.Result(true)
                    false -> ConnectionTest.Result(false)
                    null -> ConnectionTest.Result(false, "Przekroczono czas oczekiwania")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ConnectionTest.Result(false, e.message ?: e::class.java.simpleName)
            }
        }
    }

    private fun statusOf(frozen: Boolean, items: List<QuestionUi>) = when {
        frozen -> ScanStatus.FROZEN
        items.isEmpty() -> ScanStatus.SCANNING
        items.any { it.state is AnswerState.Pending } -> ScanStatus.ANALYZING
        else -> ScanStatus.DONE
    }

    companion object {
        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                MainViewModel(
                    ocr = container.ocrPipeline,
                    answerService = container.answerService,
                    settingsRepository = container.settingsRepository,
                    engines = container.engines,
                )
            }
        }
    }
}
