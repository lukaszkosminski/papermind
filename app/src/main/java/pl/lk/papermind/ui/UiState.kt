package pl.lk.papermind.ui

import pl.lk.papermind.model.AnswerState
import pl.lk.papermind.model.Box
import pl.lk.papermind.model.CrosswordGrid
import pl.lk.papermind.model.DetectedQuestion
import pl.lk.papermind.model.EngineId
import pl.lk.papermind.model.GridCell
import pl.lk.papermind.settings.AppSettings
import pl.lk.papermind.ui.tracking.TrackedQuestion

/** Pytanie + stan odpowiedzi + aktualna pozycja w widoku (null = poza kadrem). */
data class QuestionUi(
    val question: DetectedQuestion,
    val state: AnswerState,
    val tracked: TrackedQuestion?,
)

enum class ScanStatus { SCANNING, ANALYZING, DONE, FROZEN }

sealed interface ConnectionTest {
    data object Idle : ConnectionTest
    data object Running : ConnectionTest
    data class Result(val ok: Boolean, val detail: String? = null) : ConnectionTest
}

data class UiState(
    val items: List<QuestionUi> = emptyList(),
    /** Boxy bieżących linii OCR (do opcjonalnych obrysów). */
    val lineBoxes: List<Box> = emptyList(),
    val selectedId: String? = null,
    val frozen: Boolean = false,
    val showLineOutlines: Boolean = false,
    val settings: AppSettings = AppSettings(),
    val connection: ConnectionTest = ConnectionTest.Idle,
    val status: ScanStatus = ScanStatus.SCANNING,
    /** Silnik, który faktycznie odpowiedział (z fallbacku), a bez odpowiedzi — wybrany w ustawieniach. */
    val activeEngine: EngineId = EngineId.GEMINI_NANO,
    /** Klatka podglądu z momentu wstrzymania (zachowywana przy rotacji ekranu). */
    val frozenBitmap: android.graphics.Bitmap? = null,
    /** Siatka krzyżówki (jeśli wykryta w bieżącej stronie, przeliczona przez GridTracker). */
    val grid: CrosswordGrid? = null,
    /** Litery wpisane w komórki siatki. */
    val crosswordLetters: Map<GridCell, Char> = emptyMap(),
    /** Tryb rozwiązywania krzyżówek (zwiększona stabilizacja x2 + wskazówka). */
    val crosswordMode: Boolean = false,
)
