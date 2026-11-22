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
import pl.lk.papermind.model.CrosswordGrid
import pl.lk.papermind.model.EngineId
import pl.lk.papermind.model.GridCell
import pl.lk.papermind.model.OcrFrame
import pl.lk.papermind.model.ParsedPage
import pl.lk.papermind.ocr.OcrPipeline
import pl.lk.papermind.settings.AppSettings
import pl.lk.papermind.settings.SettingsRepository
import pl.lk.papermind.ui.crossword.GridTracker
import pl.lk.papermind.ui.crossword.crosswordLetters
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
    private val gridTracker: GridTracker = GridTracker(),
    private val connectionTimeoutMs: Long = 15_000,
) : ViewModel() {

    private val frozen = MutableStateFlow(false)
    private val frozenBitmap = MutableStateFlow<android.graphics.Bitmap?>(null)
    private val selected = MutableStateFlow<String?>(null)
    private val currentPage = MutableStateFlow<ParsedPage?>(null)
    private val showLineOutlines = MutableStateFlow(false)
    private val crosswordMode = MutableStateFlow(false)
    private var baseStabilityMs: Long? = null
    private val connection = MutableStateFlow<ConnectionTest>(ConnectionTest.Idle)

    private class Tracking(val lines: List<Box>, val tracked: Map<String, TrackedQuestion>, val grid: CrosswordGrid?)

    private val tracking: Flow<Tracking> = combine(
        currentPage,
        ocr.frames.filter { !frozen.value }.onStart { emit(OcrFrame(0, emptyList())) },
    ) { page, frame ->
        val questions = page?.questions.orEmpty()
        val trackedGrid = gridTracker.update(page?.grid, frame)
        Tracking(
            lines = frame.lines.map { it.box },
            tracked = tracker.update(questions, frame).associateBy { it.questionId },
            grid = trackedGrid,
        )
    }.conflate().flowOn(trackingDispatcher)

    private class Content(
        val items: List<QuestionUi>,
        val lines: List<Box>,
        val grid: CrosswordGrid?,
        val crosswordLetters: Map<GridCell, Char>,
    )

    private val content: Flow<Content> = combine(currentPage, tracking, answerService.answers) { page, tr, answers ->
        val items = page?.questions.orEmpty().map { q ->
            QuestionUi(q, answers[q.id] ?: AnswerState.Pending, tr.tracked[q.id])
        }
        val grid = tr.grid ?: page?.grid
        val letters = if (grid != null && page != null) crosswordLetters(grid, page.questions, answers) else emptyMap()
        Content(items, tr.lines, grid, letters)
    }

    private class Controls(
        val selectedId: String?,
        val frozen: Boolean,
        val showLineOutlines: Boolean,
        val connection: ConnectionTest,
        val frozenBitmap: android.graphics.Bitmap?,
        val crosswordMode: Boolean,
    )

    private val controls: Flow<Controls> = combine(
        selected, frozen, showLineOutlines, connection, frozenBitmap, crosswordMode,
    ) { array: Array<Any?> ->
        Controls(
            selectedId = array[0] as String?,
            frozen = array[1] as Boolean,
            showLineOutlines = array[2] as Boolean,
            connection = array[3] as ConnectionTest,
            frozenBitmap = array[4] as? android.graphics.Bitmap,
            crosswordMode = array[5] as Boolean,
        )
    }

    val uiState: StateFlow<UiState> = combine(
        content,
        controls,
        settingsRepository.settings,
    ) { c, ctl, settings ->
        val active = c.items.firstNotNullOfOrNull { (it.state as? AnswerState.Ready)?.answer?.engine }
        UiState(
            items = c.items,
            lineBoxes = c.lines,
            selectedId = ctl.selectedId,
            frozen = ctl.frozen,
            showLineOutlines = ctl.showLineOutlines,
            settings = settings,
            connection = ctl.connection,
            status = statusOf(ctl.frozen, c.items),
            activeEngine = active ?: settings.engine,
            frozenBitmap = ctl.frozenBitmap,
            grid = c.grid,
            crosswordLetters = c.crosswordLetters,
            crosswordMode = ctl.crosswordMode,
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

    fun toggleFreeze(bitmap: android.graphics.Bitmap? = null) {
        frozen.update { wasFrozen ->
            val next = !wasFrozen
            frozenBitmap.value = if (next) bitmap else null
            next
        }
    }

    fun toggleLineOutlines() = showLineOutlines.update { !it }

    /**
     * Przełącza tryb rozwiązywania krzyżówek:
     * zwiększa czas stabilizacji x2 (daje więcej czasu na nieruchome trzymanie kamery)
     * oraz wyświetla wskazówkę w UI.
     */
    fun toggleCrosswordMode() {
        crosswordMode.update { wasOn ->
            val next = !wasOn
            viewModelScope.launch {
                val current = settingsRepository.settings.first().stabilityMs
                if (next) {
                    baseStabilityMs = current
                    settingsRepository.update { it.copy(stabilityMs = (current * 2).coerceAtMost(5_000L)) }
                } else {
                    val restore = baseStabilityMs ?: (current / 2)
                    baseStabilityMs = null
                    settingsRepository.update { it.copy(stabilityMs = restore.coerceIn(150L, 5_000L)) }
                }
            }
            next
        }
    }

    /** Tap na pytaniu w panelu: podświetla je na nakładce (ponowny tap zdejmuje podświetlenie). */
    fun select(questionId: String) = selected.update { if (it == questionId) null else questionId }

    fun clear() {
        answerService.clear()
        ocr.reset()
        currentPage.value = null
        selected.value = null
        frozenBitmap.value = null
        tracker.reset()
        gridTracker.reset()
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
