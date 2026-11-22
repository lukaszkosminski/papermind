package pl.lk.papermind.model

/**
 * WSPÓLNE KONTRAKTY — zmiany tylko przez kierownika projektu.
 *
 * Układ współrzędnych: wszystkie [Box] są w pikselach widoku PreviewView
 * (CameraX MlKitAnalyzer z COORDINATE_SYSTEM_VIEW_REFERENCED), więc UI rysuje je bez transformacji.
 */
data class Box(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width get() = right - left
    val height get() = bottom - top
    fun union(o: Box) = Box(minOf(left, o.left), minOf(top, o.top), maxOf(right, o.right), maxOf(bottom, o.bottom))
}

/** Pojedyncza linia tekstu z OCR. */
data class OcrLine(val text: String, val box: Box, val confidence: Float? = null)

/** Wynik OCR jednej klatki. */
data class OcrFrame(
    val timestampMs: Long,
    val lines: List<OcrLine>,
) {
    val fullText: String get() = lines.joinToString("\n") { it.text }
}

enum class QuestionType { SINGLE_CHOICE, MULTI_CHOICE, OPEN, FORM_FIELD, MATH }

/** Opcja odpowiedzi w pytaniu zamkniętym, np. label="B", text="Mitochondrium". */
data class AnswerOption(val label: String, val text: String, val box: Box)

/**
 * Pytanie wykryte na kartce.
 * [id] — stabilny między klatkami (hash znormalizowanej treści pytania + opcji), służy do cache odpowiedzi.
 */
data class DetectedQuestion(
    val id: String,
    val number: String?,
    val type: QuestionType,
    val text: String,
    val options: List<AnswerOption>,
    val box: Box,
)

/** Ustabilizowana strona — emitowana gdy treść przestaje się zmieniać (kamera trzymana nieruchomo). */
data class ParsedPage(
    val timestampMs: Long,
    val questions: List<DetectedQuestion>,
    val rawText: String,
)

enum class EngineId { GEMINI_NANO, OPENCODE, CLOUD }

/**
 * Odpowiedź na pytanie.
 * - zamknięte: [correctOptionLabels] np. ["B"] lub ["A","C"]
 * - otwarte / formularz / matematyka: [text] (krótko, do nakładki)
 */
data class Answer(
    val questionId: String,
    val correctOptionLabels: List<String> = emptyList(),
    val text: String = "",
    val explanation: String? = null,
    val confidence: Float = 0.5f,
    val engine: EngineId,
)

sealed interface AnswerState {
    data object Pending : AnswerState
    data class Ready(val answer: Answer) : AnswerState
    data class Failed(val message: String) : AnswerState
}
