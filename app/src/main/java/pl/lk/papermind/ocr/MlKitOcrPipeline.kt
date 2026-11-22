package pl.lk.papermind.ocr

import android.content.Context
import android.graphics.Matrix
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.util.Size
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.mlkit.vision.MlKitAnalyzer
import androidx.camera.view.LifecycleCameraController
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import pl.lk.papermind.model.Box
import pl.lk.papermind.model.OcrFrame
import pl.lk.papermind.model.OcrLine
import pl.lk.papermind.model.ParsedPage
import pl.lk.papermind.settings.AppSettings
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Pipeline OCR: CameraX [MlKitAnalyzer] (wyniki w układzie widoku) + ML Kit Text Recognition (model łaciński,
 * polskie znaki obsługiwane) → [OcrFrame] → [FrameStabilizer] → [RegexQuestionParser] → [ParsedPage].
 *
 * Konfiguracja kontrolera (backpressure + rozdzielczość analizy) należy do UI: wywołaj [configureController]
 * **raz**, przy tworzeniu `LifecycleCameraController` (przed `bindToLifecycle`). [bind] podpina wyłącznie
 * analizator, więc pauza/wznowienie ekranu nie rekonfiguruje use case'a i nie restartuje sesji kamery.
 *
 * - [frames] to konflated StateFlow z najnowszą klatką, [pages] emituje tylko ustabilizowane strony,
 * - lekkie throttling (nie analizujemy częściej niż co [MIN_ANALYSIS_INTERVAL_MS]).
 */
class MlKitOcrPipeline(
    context: Context,
    settings: Flow<AppSettings>,
) : OcrPipeline {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val recognizer: TextRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val parser: QuestionParser = RegexQuestionParser()

    @Volatile
    private var stabilityMs: Long = FrameStabilizer.DEFAULT_STABILITY_MS

    private val stabilizer = FrameStabilizer(
        parser = parser,
        stabilityMs = { stabilityMs },
        clock = { SystemClock.elapsedRealtime() },
    )

    private val _frames = MutableStateFlow(OcrFrame(timestampMs = 0L, lines = emptyList()))
    override val frames: Flow<OcrFrame> = _frames.asStateFlow()

    private val _pages = MutableSharedFlow<ParsedPage>(
        replay = 1,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val pages: Flow<ParsedPage> = _pages.asSharedFlow()

    /** Konflated kanał — przetwarzamy zawsze najnowszą klatkę, w jednym wątku (kolejność zachowana). */
    private val incoming = Channel<OcrFrame>(capacity = Channel.CONFLATED)

    private val boundController = AtomicReference<LifecycleCameraController?>(null)

    /** Reset wykonywany w wątku przetwarzania (stabilizator nie jest thread-safe). */
    private val resetRequested = AtomicBoolean(false)

    private val closed = AtomicBoolean(false)

    init {
        scope.launch {
            settings
                .catch { Log.w(TAG, "Błąd strumienia ustawień", it) }
                .collect { s ->
                    stabilityMs = s.stabilityMs.coerceIn(150L, 5_000L)
                }
        }
        scope.launch {
            for (frame in incoming) {
                if (resetRequested.getAndSet(false)) stabilizer.reset()
                _frames.value = frame
                val page = runCatching { stabilizer.onFrame(frame) }
                    .onFailure { Log.w(TAG, "Błąd stabilizacji/parsowania klatki", it) }
                    .getOrNull() ?: continue
                _pages.emit(page)
            }
        }
    }

    /**
     * Podpina **wyłącznie analizator** pod kontroler kamery (bez rekonfiguracji use case'a).
     * **Wywołuj z wątku głównego** (wymóg CameraController), [executor] powinien być wątkiem tła
     * (np. `Executors.newSingleThreadExecutor()`). Powtórne `bind` z tym samym kontrolerem jest no-op.
     */
    override fun bind(controller: LifecycleCameraController, executor: Executor) {
        if (closed.get()) {
            Log.w(TAG, "bind() po close() — ignoruję")
            return
        }
        val previous = boundController.getAndSet(controller)
        if (previous === controller) return
        if (previous != null) clearAnalyzer(previous)

        val analyzer = MlKitAnalyzer(
            listOf(recognizer),
            ImageAnalysis.COORDINATE_SYSTEM_VIEW_REFERENCED,
            executor,
        ) { result ->
            val text = result.getValue(recognizer)
            if (text != null) {
                incoming.trySend(
                    OcrFrame(
                        timestampMs = result.timestamp,
                        lines = toLines(text),
                    ),
                )
            } else {
                result.getThrowable(recognizer)?.let { Log.w(TAG, "ML Kit OCR error", it) }
            }
        }
        controller.setImageAnalysisAnalyzer(
            executor,
            ThrottledAnalyzer(analyzer, MIN_ANALYSIS_INTERVAL_MS) { SystemClock.elapsedRealtime() },
        )
    }

    override fun unbind(controller: LifecycleCameraController) {
        boundController.compareAndSet(controller, null)
        clearAnalyzer(controller)
    }

    /** Zapomina ostatnio wyemitowaną stronę — kolejna stabilna klatka zostanie przetworzona ponownie. */
    override fun reset() {
        if (!closed.get()) resetRequested.set(true)
    }

    /** Zwalnia zasoby (ML Kit, kanał, wątek). Idempotentne; UI woła przy końcu życia aplikacji. */
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        boundController.getAndSet(null)?.let { clearAnalyzer(it) }
        incoming.close()
        recognizer.close()
        scope.cancel()
    }

    /** CameraController wymaga wątku głównego; wywołanie z tła przekierowujemy. */
    private fun clearAnalyzer(controller: LifecycleCameraController) {
        val main = Looper.getMainLooper()
        if (Looper.myLooper() == main) {
            controller.clearImageAnalysisAnalyzer()
        } else {
            Handler(main).post { runCatching { controller.clearImageAnalysisAnalyzer() } }
        }
    }

    private fun toLines(text: Text): List<OcrLine> {
        val out = ArrayList<OcrLine>()
        for (block in text.textBlocks) {
            for (line in block.lines) {
                val t = line.text.trim()
                if (t.isEmpty()) continue
                val bb = line.boundingBox ?: continue
                val confidence = line.confidence.takeIf { it > 0f && it <= 1f }
                out += OcrLine(
                    text = t,
                    box = Box(bb.left.toFloat(), bb.top.toFloat(), bb.right.toFloat(), bb.bottom.toFloat()),
                    confidence = confidence,
                )
            }
        }
        return out
    }

    /**
     * Analizator-dekorator: pomija klatki, które przyszły szybciej niż [minIntervalMs] (oszczędza CPU/baterię).
     * Zamknięcie [ImageProxy] należy do nas, gdy nie przekazujemy klatki dalej.
     */
    private class ThrottledAnalyzer(
        private val delegate: ImageAnalysis.Analyzer,
        private val minIntervalMs: Long,
        private val clock: () -> Long,
    ) : ImageAnalysis.Analyzer {
        private var lastRunMs = Long.MIN_VALUE

        override fun analyze(image: ImageProxy) {
            val now = clock()
            if (lastRunMs != Long.MIN_VALUE && now - lastRunMs < minIntervalMs) {
                image.close()
                return
            }
            lastRunMs = now
            delegate.analyze(image)
        }

        // CameraController pyta analizator o układ współrzędnych i przekazuje mu transformację widoku —
        // musimy to forwardować do MlKitAnalyzer, inaczej boxy nie byłyby w pikselach PreviewView.
        override fun getTargetCoordinateSystem(): Int = delegate.targetCoordinateSystem

        override fun updateTransform(matrix: Matrix?) {
            delegate.updateTransform(matrix)
        }

        override fun getDefaultTargetResolution(): Size? = delegate.defaultTargetResolution
    }

    companion object {
        private const val TAG = "MlKitOcrPipeline"
        private const val MIN_ANALYSIS_INTERVAL_MS = 120L

        /**
         * 16:9 / 1080p — kompromis: wystarczająco dużo pikseli na mały druk, wciąż płynnie na S25 Ultra.
         * Gdy urządzenie nie wspiera 1080p, CameraX wybierze najbliższą dostępną rozdzielczość.
         */
        val ANALYSIS_RESOLUTION: ResolutionSelector = ResolutionSelector.Builder()
            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
            .setResolutionStrategy(
                ResolutionStrategy(
                    Size(1920, 1080),
                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                ),
            )
            .build()

        /**
         * Konfiguracja kontrolera kamery do OCR — wywołać **raz** przy tworzeniu `LifecycleCameraController`,
         * przed `bindToLifecycle`. Ustawia `STRATEGY_KEEP_ONLY_LATEST` i rozdzielczość analizy 1080p.
         * Wywołanie po związaniu z cyklem życia wymusza recreację use case'a (mrugnięcie kamery).
         */
        fun configureController(controller: LifecycleCameraController) {
            controller.setImageAnalysisBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            controller.setImageAnalysisResolutionSelector(ANALYSIS_RESOLUTION)
        }
    }
}
