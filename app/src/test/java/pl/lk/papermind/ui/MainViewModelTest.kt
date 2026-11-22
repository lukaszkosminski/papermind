package pl.lk.papermind.ui

import androidx.camera.view.LifecycleCameraController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import pl.lk.papermind.ai.AnswerEngine
import pl.lk.papermind.ai.AnswerService
import pl.lk.papermind.model.Answer
import pl.lk.papermind.model.AnswerOption
import pl.lk.papermind.model.AnswerState
import pl.lk.papermind.model.Box
import pl.lk.papermind.model.CrosswordClue
import pl.lk.papermind.model.CrosswordGrid
import pl.lk.papermind.model.CrosswordSlot
import pl.lk.papermind.model.DetectedQuestion
import pl.lk.papermind.model.Direction
import pl.lk.papermind.model.EngineId
import pl.lk.papermind.model.GridCell
import pl.lk.papermind.model.OcrFrame
import pl.lk.papermind.model.ParsedPage
import pl.lk.papermind.model.QuestionType
import pl.lk.papermind.ocr.OcrPipeline
import pl.lk.papermind.settings.AppSettings
import pl.lk.papermind.ui.fake.FakeEngine
import pl.lk.papermind.ui.fake.InMemorySettingsRepository
import java.util.concurrent.Executor

@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    private class TestOcrPipeline : OcrPipeline {
        val frameFlow = MutableSharedFlow<OcrFrame>(replay = 1)
        val pageFlow = MutableSharedFlow<ParsedPage>(replay = 1)
        var bound = false

        override val frames: Flow<OcrFrame> = frameFlow
        override val pages: Flow<ParsedPage> = pageFlow

        override fun bind(controller: LifecycleCameraController, executor: Executor) {
            bound = true
        }

        override fun unbind(controller: LifecycleCameraController) {
            bound = false
        }
    }

    private class TestAnswerService : AnswerService {
        val answersState = MutableStateFlow<Map<String, AnswerState>>(emptyMap())
        override val answers: Flow<Map<String, AnswerState>> = answersState
        val submittedPages = mutableListOf<ParsedPage>()
        var cleared = false

        override fun submit(page: ParsedPage) {
            submittedPages.add(page)
            val initial = page.questions.associate { it.id to AnswerState.Pending }
            answersState.value = answersState.value + initial
        }

        override fun clear() {
            cleared = true
            answersState.value = emptyMap()
        }
    }

    private lateinit var ocr: TestOcrPipeline
    private lateinit var answerService: TestAnswerService
    private lateinit var settingsRepo: InMemorySettingsRepository
    private lateinit var nanoEngine: FakeEngine
    private lateinit var opencodeEngine: FakeEngine

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        ocr = TestOcrPipeline()
        answerService = TestAnswerService()
        settingsRepo = InMemorySettingsRepository(AppSettings(engine = EngineId.GEMINI_NANO))
        nanoEngine = FakeEngine(EngineId.GEMINI_NANO)
        opencodeEngine = FakeEngine(EngineId.OPENCODE)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createViewModel() = MainViewModel(
        ocr = ocr,
        answerService = answerService,
        settingsRepository = settingsRepo,
        engines = mapOf(EngineId.GEMINI_NANO to nanoEngine, EngineId.OPENCODE to opencodeEngine),
        trackingDispatcher = testDispatcher,
        connectionTimeoutMs = 1000,
    )

    private val sampleQuestion = DetectedQuestion(
        id = "q1",
        number = "1",
        type = QuestionType.SINGLE_CHOICE,
        text = "Które organelle produkują ATP?",
        options = listOf(
            AnswerOption("A", "Rybosomy", Box(80f, 200f, 300f, 240f)),
            AnswerOption("B", "Mitochondria", Box(80f, 260f, 300f, 300f)),
        ),
        box = Box(50f, 150f, 400f, 190f),
    )

    private val samplePage = ParsedPage(
        timestampMs = 1000L,
        questions = listOf(sampleQuestion),
        rawText = "1. Które organelle produkują ATP?\nA. Rybosomy\nB. Mitochondria",
    )

    @Test
    fun initialState_isScanningAndEmpty() = runTest(testDispatcher) {
        val vm = createViewModel()
        val job = launch { vm.uiState.collect {} }

        advanceUntilIdle()

        val state = vm.uiState.value
        assertEquals(ScanStatus.SCANNING, state.status)
        assertTrue(state.items.isEmpty())
        assertNull(state.selectedId)
        assertFalse(state.frozen)
        assertEquals(EngineId.GEMINI_NANO, state.activeEngine)
        assertEquals(ConnectionTest.Idle, state.connection)

        job.cancel()
    }

    @Test
    fun pageEmission_submitsToAnswerService_andUpdatesUiState() = runTest(testDispatcher) {
        val vm = createViewModel()
        val job = launch { vm.uiState.collect {} }

        advanceUntilIdle()

        ocr.pageFlow.emit(samplePage)
        advanceUntilIdle()

        assertEquals(1, answerService.submittedPages.size)
        val state = vm.uiState.value
        assertEquals(1, state.items.size)
        assertEquals("q1", state.items[0].question.id)
        assertEquals(AnswerState.Pending, state.items[0].state)
        assertEquals(ScanStatus.ANALYZING, state.status)

        // Now AnswerService transitions to Ready
        val readyAnswer = Answer(
            questionId = "q1",
            correctOptionLabels = listOf("B"),
            confidence = 0.95f,
            engine = EngineId.GEMINI_NANO,
        )
        answerService.answersState.value = mapOf("q1" to AnswerState.Ready(readyAnswer))
        advanceUntilIdle()

        val updatedState = vm.uiState.value
        assertEquals(ScanStatus.DONE, updatedState.status)
        val item = updatedState.items[0]
        assertTrue(item.state is AnswerState.Ready)
        assertEquals(EngineId.GEMINI_NANO, updatedState.activeEngine)

        job.cancel()
    }

    @Test
    fun select_togglesQuestionSelection() = runTest(testDispatcher) {
        val vm = createViewModel()
        val job = launch { vm.uiState.collect {} }

        advanceUntilIdle()

        vm.select("q1")
        advanceUntilIdle()
        assertEquals("q1", vm.uiState.value.selectedId)

        // Second click on same item unselects
        vm.select("q1")
        advanceUntilIdle()
        assertNull(vm.uiState.value.selectedId)

        job.cancel()
    }

    @Test
    fun freeze_pausesOcrAndSetsFrozenStatus() = runTest(testDispatcher) {
        val vm = createViewModel()
        val job = launch { vm.uiState.collect {} }

        advanceUntilIdle()

        vm.toggleFreeze()
        advanceUntilIdle()

        assertTrue(vm.uiState.value.frozen)
        assertEquals(ScanStatus.FROZEN, vm.uiState.value.status)

        // Further pages are ignored while frozen
        ocr.pageFlow.emit(samplePage)
        advanceUntilIdle()
        assertTrue(answerService.submittedPages.isEmpty())

        // Resume unfreezes
        vm.toggleFreeze()
        advanceUntilIdle()
        assertFalse(vm.uiState.value.frozen)

        job.cancel()
    }

    @Test
    fun freeze_managesFrozenBitmap_andClearsOnClear() = runTest(testDispatcher) {
        val vm = createViewModel()
        val job = launch { vm.uiState.collect {} }

        advanceUntilIdle()

        val unsafe = sun.misc.Unsafe::class.java.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null) as sun.misc.Unsafe
        val mockBitmap = unsafe.allocateInstance(android.graphics.Bitmap::class.java) as android.graphics.Bitmap

        vm.toggleFreeze(mockBitmap)
        advanceUntilIdle()

        assertTrue(vm.uiState.value.frozen)
        assertEquals(mockBitmap, vm.uiState.value.frozenBitmap)

        // Resuming unfreezes and clears bitmap
        vm.toggleFreeze()
        advanceUntilIdle()

        assertFalse(vm.uiState.value.frozen)
        assertNull(vm.uiState.value.frozenBitmap)

        // Freeze again with bitmap, then clear()
        vm.toggleFreeze(mockBitmap)
        advanceUntilIdle()
        assertEquals(mockBitmap, vm.uiState.value.frozenBitmap)

        vm.clear()
        advanceUntilIdle()
        assertNull(vm.uiState.value.frozenBitmap)

        job.cancel()
    }

    @Test
    fun clear_resetsState() = runTest(testDispatcher) {
        val vm = createViewModel()
        val job = launch { vm.uiState.collect {} }

        advanceUntilIdle()
        ocr.pageFlow.emit(samplePage)
        vm.select("q1")
        advanceUntilIdle()

        assertEquals(1, vm.uiState.value.items.size)
        assertEquals("q1", vm.uiState.value.selectedId)

        vm.clear()
        advanceUntilIdle()

        assertTrue(answerService.cleared)
        assertTrue(vm.uiState.value.items.isEmpty())
        assertNull(vm.uiState.value.selectedId)

        job.cancel()
    }

    @Test
    fun toggleLineOutlines_updatesShowLineOutlines() = runTest(testDispatcher) {
        val vm = createViewModel()
        val job = launch { vm.uiState.collect {} }

        advanceUntilIdle()
        assertFalse(vm.uiState.value.showLineOutlines)

        vm.toggleLineOutlines()
        advanceUntilIdle()
        assertTrue(vm.uiState.value.showLineOutlines)

        job.cancel()
    }

    @Test
    fun updateSettings_persistsToRepository() = runTest(testDispatcher) {
        val vm = createViewModel()
        val job = launch { vm.uiState.collect {} }

        advanceUntilIdle()

        vm.updateSettings { it.copy(engine = EngineId.OPENCODE, showExplanations = false) }
        advanceUntilIdle()

        assertEquals(EngineId.OPENCODE, vm.uiState.value.settings.engine)
        assertFalse(vm.uiState.value.settings.showExplanations)

        job.cancel()
    }

    @Test
    fun testConnection_success() = runTest(testDispatcher) {
        nanoEngine.available = true
        val vm = createViewModel()
        val job = launch { vm.uiState.collect {} }

        advanceUntilIdle()

        vm.testConnection()
        advanceUntilIdle()

        val conn = vm.uiState.value.connection
        assertTrue(conn is ConnectionTest.Result)
        assertTrue((conn as ConnectionTest.Result).ok)

        job.cancel()
    }

    @Test
    fun testConnection_failure() = runTest(testDispatcher) {
        nanoEngine.available = false
        val vm = createViewModel()
        val job = launch { vm.uiState.collect {} }

        advanceUntilIdle()

        vm.testConnection()
        advanceUntilIdle()

        val conn = vm.uiState.value.connection
        assertTrue(conn is ConnectionTest.Result)
        assertFalse((conn as ConnectionTest.Result).ok)

        job.cancel()
    }

    @Test
    fun testConnection_missingEngine() = runTest(testDispatcher) {
        val vm = MainViewModel(
            ocr = ocr,
            answerService = answerService,
            settingsRepository = settingsRepo,
            engines = emptyMap(), // no engine registered
            trackingDispatcher = testDispatcher,
        )
        val job = launch { vm.uiState.collect {} }

        advanceUntilIdle()

        vm.testConnection()
        advanceUntilIdle()

        val conn = vm.uiState.value.connection
        assertTrue(conn is ConnectionTest.Result)
        if (conn is ConnectionTest.Result) {
            assertFalse(conn.ok)
            assertTrue(conn.detail?.contains("Brak silnika") == true)
        }

        job.cancel()
    }

    @Test
    fun toggleCrosswordMode_doublesStabilityMs_andRestoresOnSecondToggle() = runTest(testDispatcher) {
        val initialStability = settingsRepo.settings.first().stabilityMs
        val vm = createViewModel()
        val job = launch { vm.uiState.collect {} }

        advanceUntilIdle()
        assertFalse(vm.uiState.value.crosswordMode)

        // Toggle on
        vm.toggleCrosswordMode()
        advanceUntilIdle()

        assertTrue(vm.uiState.value.crosswordMode)
        assertEquals(initialStability * 2, settingsRepo.settings.first().stabilityMs)

        // Toggle off
        vm.toggleCrosswordMode()
        advanceUntilIdle()

        assertFalse(vm.uiState.value.crosswordMode)
        assertEquals(initialStability, settingsRepo.settings.first().stabilityMs)

        job.cancel()
    }

    @Test
    fun pageEmission_withCrosswordGrid_updatesGridAndLettersInUiState() = runTest(testDispatcher) {
        val vm = createViewModel()
        val job = launch { vm.uiState.collect {} }

        advanceUntilIdle()

        val cell1 = GridCell(0, 0, Box(0f, 0f, 10f, 10f), blocked = false, number = 1)
        val cell2 = GridCell(0, 1, Box(10f, 0f, 20f, 10f), blocked = false)
        val cell3 = GridCell(0, 2, Box(20f, 0f, 30f, 10f), blocked = false)
        val sampleGrid = CrosswordGrid(
            rows = 1, cols = 3,
            box = Box(0f, 0f, 30f, 10f),
            cells = listOf(cell1, cell2, cell3),
            slots = listOf(CrosswordSlot(1, Direction.ACROSS, listOf(cell1, cell2, cell3))),
        )
        val clue = DetectedQuestion(
            id = "cw-1",
            number = "1",
            type = QuestionType.CROSSWORD_CLUE,
            text = "Bicz",
            options = emptyList(),
            box = Box(0f, 20f, 100f, 40f),
            clue = CrosswordClue(1, Direction.ACROSS, 3),
        )
        val cwPage = ParsedPage(
            timestampMs = 2000L,
            questions = listOf(clue),
            rawText = "1. Bicz (3)",
            grid = sampleGrid,
        )

        ocr.pageFlow.emit(cwPage)
        advanceUntilIdle()

        assertNotNull(vm.uiState.value.grid)
        assertEquals(1, vm.uiState.value.grid!!.slots.size)

        // Supply ready answer
        answerService.answersState.value = mapOf(
            "cw-1" to AnswerState.Ready(Answer("cw-1", text = "BAT", confidence = 0.95f, engine = EngineId.GEMINI_NANO)),
        )
        advanceUntilIdle()

        val letters = vm.uiState.value.crosswordLetters
        assertEquals('B', letters[cell1])
        assertEquals('A', letters[cell2])
        assertEquals('T', letters[cell3])

        job.cancel()
    }
}
