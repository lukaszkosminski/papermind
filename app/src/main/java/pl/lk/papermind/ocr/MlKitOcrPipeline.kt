package pl.lk.papermind.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.RectF
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
import pl.lk.papermind.diag.DiagLog
import pl.lk.papermind.model.Box
import pl.lk.papermind.model.CrosswordGrid
import pl.lk.papermind.model.OcrFrame
import pl.lk.papermind.model.OcrLine
import pl.lk.papermind.model.ParsedPage
import pl.lk.papermind.model.QuestionType
import pl.lk.papermind.settings.AppSettings
import java.io.ByteArrayOutputStream
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
 * Krzyżówki: co [GRID_INTERVAL_MS] kopiujemy płaszczyznę Y klatki i uruchamiamy [CrosswordGridDetector] poza
 * wątkiem UI; siatka jest mapowana z pikseli obrazu na piksele widoku macierzą sensor→view (te same wartości,
 * których używa ML Kit). Gdy strona zawiera siatkę lub hasła, do `ParsedPage` dołączamy zdjęcie JPEG
 * (dłuższy bok ≤ [MAX_SNAPSHOT_SIDE] px, jakość [JPEG_QUALITY]).
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
    private val gridDetector = CrosswordGridDetector()

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
    private val incoming = Channel<FramePacket>(capacity = Channel.CONFLATED)

    private val boundController = AtomicReference<LifecycleCameraController?>(null)

    /** Reset wykonywany w wątku przetwarzania (stabilizator nie jest thread-safe). */
    private val resetRequested = AtomicBoolean(false)

    private val closed = AtomicBoolean(false)

    /** Ostatnie klatki Y do detekcji siatki (klucz: timestamp klatki). Mały bufor, tylko najnowsze. */
    private val snapshots = SnapshotBuffer()

    // Stan siatki — dotykany wyłącznie w wątku przetwarzania klatek.
    private var currentGrid: CrosswordGrid? = null
    private var currentGridText: String = ""
    private var currentGridSnapshot: GridSnapshot? = null
    private var lastSnapshot: GridSnapshot? = null

    init {
        scope.launch {
            settings
                .catch { Log.w(TAG, "Błąd strumienia ustawień", it) }
                .collect { s ->
                    stabilityMs = s.stabilityMs.coerceIn(150L, 5_000L)
                }
        }
        scope.launch {
            for (packet in incoming) {
                if (resetRequested.getAndSet(false)) stabilizer.reset()
                _frames.value = packet.frame
                processGrid(packet)
                val page = runCatching { stabilizer.onFrame(packet.frame, currentGrid) }
                    .onFailure { Log.w(TAG, "Błąd stabilizacji/parsowania klatki", it) }
                    .getOrNull() ?: continue
                _pages.emit(withSnapshotJpeg(page))
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
                val frame = OcrFrame(
                    timestampMs = result.timestamp,
                    lines = toLines(text),
                )
                if (frame.lines.isEmpty()) DiagLog.log("OCR", "klatka bez tekstu")
                incoming.trySend(FramePacket(frame, snapshots.take(result.timestamp)))
            } else {
                result.getThrowable(recognizer)?.let { Log.w(TAG, "ML Kit OCR error", it) }
            }
        }
        controller.setImageAnalysisAnalyzer(
            executor,
            FrameAnalyzer(
                delegate = analyzer,
                minIntervalMs = MIN_ANALYSIS_INTERVAL_MS,
                gridIntervalMs = GRID_INTERVAL_MS,
                clock = { SystemClock.elapsedRealtime() },
                onSnapshot = { image, sensorToView ->
                    captureSnapshot(image, sensorToView)?.let { snapshots.put(it) }
                },
            ),
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

    // ------------------------------------------------------------------ krzyżówki

    /** Detekcja siatki dla klatki ze snapshotem; aktualizuje [currentGrid]. Tylko w wątku przetwarzania. */
    private fun processGrid(packet: FramePacket) {
        val snapshot = packet.snapshot
        if (snapshot != null) {
            lastSnapshot = snapshot
            val detected = runCatching { detectGrid(snapshot, packet.frame) }
                .onFailure { Log.w(TAG, "Błąd detekcji siatki", it) }
                .getOrNull()
            if (detected != null) {
                currentGrid = detected
                currentGridText = packet.frame.fullText
                currentGridSnapshot = snapshot
            }
        }
        // Siatka zostaje na kolejnych klatkach, dopóki tekst nie zmieni się wyraźnie (inna strona w kadrze).
        if (currentGrid != null &&
            !TextNormalizer.similarityAtLeast(currentGridText, packet.frame.fullText, GRID_STALE_TEXT_SIMILARITY)
        ) {
            currentGrid = null
            currentGridSnapshot = null
        }
    }

    private fun detectGrid(snapshot: GridSnapshot, frame: OcrFrame): CrosswordGrid? {
        val viewToImage = Matrix()
        if (!snapshot.imageToView.invert(viewToImage)) return null
        val numbers = frame.lines.mapNotNull { line ->
            val box = mapBox(line.box, viewToImage) ?: return@mapNotNull null
            OcrLine(line.text, box, line.confidence)
        }
        val grid = gridDetector.detect(
            luma = snapshot.luma,
            width = snapshot.width,
            height = snapshot.height,
            rowStride = snapshot.rowStride,
            numbers = numbers,
        ) ?: return null
        return grid.remapBoxes { box -> mapBox(box, snapshot.imageToView) ?: box }
    }

    /** Dołącza zdjęcie JPEG tylko dla stron z krzyżówką (siatka lub hasła). */
    private fun withSnapshotJpeg(page: ParsedPage): ParsedPage {
        val crossword = page.grid != null || page.questions.any { it.type == QuestionType.CROSSWORD_CLUE }
        if (!crossword) return page
        val snapshot = currentGridSnapshot ?: lastSnapshot ?: return page
        val jpeg = runCatching { encodeJpeg(snapshot) }
            .onFailure { Log.w(TAG, "Błąd kodowania JPEG", it) }
            .getOrNull() ?: return page
        return page.copy(snapshotJpeg = jpeg)
    }

    /** Konwersja Y → JPEG (skala szarości) poza wątkiem UI; dłuższy bok ≤ [MAX_SNAPSHOT_SIDE]. */
    private fun encodeJpeg(snapshot: GridSnapshot): ByteArray {
        val longSide = maxOf(snapshot.width, snapshot.height)
        val scale = if (longSide > MAX_SNAPSHOT_SIDE) MAX_SNAPSHOT_SIDE.toFloat() / longSide else 1f
        val outW = (snapshot.width * scale).toInt().coerceAtLeast(1)
        val outH = (snapshot.height * scale).toInt().coerceAtLeast(1)
        val pixels = IntArray(outW * outH)
        for (y in 0 until outH) {
            val srcY = (y / scale).toInt().coerceIn(0, snapshot.height - 1)
            val rowBase = srcY * snapshot.rowStride
            var out = y * outW
            for (x in 0 until outW) {
                val srcX = (x / scale).toInt().coerceIn(0, snapshot.width - 1)
                val raw = snapshot.luma[rowBase + srcX].toInt() and 0xFF
                // kamera zwykle daje zakres 16..235 — rozciągamy do 0..255
                val v = (((raw - 16) * 255) / 219).coerceIn(0, 255)
                pixels[out++] = 0xFF000000.toInt() or (v shl 16) or (v shl 8) or v
            }
        }
        val bitmap = Bitmap.createBitmap(pixels, outW, outH, Bitmap.Config.ARGB_8888)
        val oriented = if (snapshot.rotationDegrees % 360 != 0) {
            val matrix = Matrix().apply { postRotate(snapshot.rotationDegrees.toFloat()) }
            Bitmap.createBitmap(bitmap, 0, 0, outW, outH, matrix, true).also { bitmap.recycle() }
        } else {
            bitmap
        }
        return ByteArrayOutputStream().use { stream ->
            oriented.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, stream)
            oriented.recycle()
            stream.toByteArray()
        }
    }

    /** Kopiuje płaszczyznę Y i macierz obraz→widok; wywoływane na wątku analizy, przed ML Kit. */
    private fun captureSnapshot(image: ImageProxy, sensorToView: Matrix?): GridSnapshot? {
        if (sensorToView == null) return null
        val plane = image.planes.firstOrNull() ?: return null
        val width = image.width
        val height = image.height
        if (width <= 0 || height <= 0) return null
        val rowStride = plane.rowStride
        if (rowStride < width) return null
        // duplicate(): nie ruszamy pozycji bufora współdzielonego z resztą pipeline'u
        val buffer = plane.buffer.duplicate()
        val luma = ByteArray(width * height)
        if (rowStride == width) {
            val count = minOf(buffer.remaining(), luma.size)
            buffer.get(luma, 0, count)
            if (count < luma.size) java.util.Arrays.fill(luma, count, luma.size, 255.toByte())
        } else {
            val row = ByteArray(rowStride)
            var outPos = 0
            for (y in 0 until height) {
                if (buffer.remaining() < width) break
                val toRead = minOf(rowStride, buffer.remaining())
                buffer.get(row, 0, toRead)
                System.arraycopy(row, 0, luma, outPos, width)
                outPos += width
            }
            if (outPos < luma.size) java.util.Arrays.fill(luma, outPos, luma.size, 255.toByte())
        }
        val imageToView = analysisToView(image, sensorToView) ?: return null
        return GridSnapshot(
            timestampMs = image.imageInfo.timestamp,
            luma = luma,
            width = width,
            height = height,
            rowStride = width,
            imageToView = imageToView,
            rotationDegrees = image.imageInfo.rotationDegrees,
        )
    }

    /**
     * Macierz piksel obrazu (bufor bez rotacji) → piksel widoku. Odtwarza obliczenia `MlKitAnalyzer`:
     * sensor→buffer ∘ rotacja ML Kit, odwrócone, złożone z sensor→view z `updateTransform`.
     */
    private fun analysisToView(image: ImageProxy, sensorToView: Matrix): Matrix? = try {
        val sensorToBuffer = Matrix(image.imageInfo.sensorToBufferTransformMatrix)
        val w = image.width.toFloat()
        val h = image.height.toFloat()
        val rotation = image.imageInfo.rotationDegrees
        val source = RectF(0f, 0f, w, h)
        val rotated = if (rotation % 180 == 0) RectF(0f, 0f, w, h) else RectF(0f, 0f, h, w)
        sensorToBuffer.postConcat(rectToRect(source, rotated, rotation))
        val analysisToSensor = Matrix()
        if (!sensorToBuffer.invert(analysisToSensor)) return null
        analysisToSensor.postConcat(sensorToView)
        analysisToSensor
    } catch (t: Throwable) {
        Log.w(TAG, "Nie udało się policzyć macierzy obraz→widok", t)
        null
    }

    /** Odpowiednik camera-core `TransformUtils.getRectToRect` (mapowanie przez przestrzeń znormalizowaną). */
    private fun rectToRect(source: RectF, target: RectF, rotationDegrees: Int): Matrix {
        val normalized = RectF(-1f, -1f, 1f, 1f)
        val toNormalized = Matrix()
        toNormalized.setRectToRect(source, normalized, Matrix.ScaleToFit.FILL)
        toNormalized.postRotate(rotationDegrees.toFloat())
        val toTarget = Matrix()
        toTarget.setRectToRect(normalized, target, Matrix.ScaleToFit.FILL)
        toNormalized.postConcat(toTarget)
        return toNormalized
    }

    private fun mapBox(box: Box, matrix: Matrix): Box? {
        val points = floatArrayOf(
            box.left, box.top,
            box.right, box.top,
            box.right, box.bottom,
            box.left, box.bottom,
        )
        matrix.mapPoints(points)
        val left = minOf(points[0], points[2], points[4], points[6])
        val top = minOf(points[1], points[3], points[5], points[7])
        val right = maxOf(points[0], points[2], points[4], points[6])
        val bottom = maxOf(points[1], points[3], points[5], points[7])
        if (!left.isFinite() || !top.isFinite() || !right.isFinite() || !bottom.isFinite()) return null
        return Box(left, top, right, bottom)
    }

    // ------------------------------------------------------------------ typy pomocnicze

    private class FramePacket(val frame: OcrFrame, val snapshot: GridSnapshot?)

    /**
     * Jedna klatka Y + macierz obraz→widok. Trzymana tylko do najbliższego przetworzenia
     * (maks. [SnapshotBuffer.maxEntries]); nie trafia do UI.
     */
    private class GridSnapshot(
        val timestampMs: Long,
        val luma: ByteArray,
        val width: Int,
        val height: Int,
        val rowStride: Int,
        val imageToView: Matrix,
        val rotationDegrees: Int,
    )

    /** Bufor snapshotów dopasowywanych po timestampie klatki (kilka najnowszych, starsze wygasają). */
    private class SnapshotBuffer(
        private val maxEntries: Int = 3,
        private val maxAgeMs: Long = 2_000L,
    ) {
        private val lock = Any()
        private val items = java.util.ArrayDeque<GridSnapshot>()

        fun put(snapshot: GridSnapshot) = synchronized(lock) {
            items.addLast(snapshot)
            while (items.size > maxEntries) items.removeFirst()
            while (items.isNotEmpty() && snapshot.timestampMs - items.first.timestampMs > maxAgeMs) {
                items.removeFirst()
            }
        }

        fun take(timestampMs: Long): GridSnapshot? = synchronized(lock) {
            val iterator = items.iterator()
            var found: GridSnapshot? = null
            while (iterator.hasNext()) {
                val s = iterator.next()
                if (s.timestampMs == timestampMs) {
                    found = s
                    iterator.remove()
                }
            }
            found
        }
    }

    /**
     * Analizator-dekorator: pomija klatki, które przyszły szybciej niż [minIntervalMs] (oszczędza CPU/baterię)
     * i co [gridIntervalMs] kopiuje płaszczyznę Y do detekcji siatki krzyżówki.
     * Zamknięcie [ImageProxy] należy do nas, gdy nie przekazujemy klatki dalej.
     */
    private class FrameAnalyzer(
        private val delegate: ImageAnalysis.Analyzer,
        private val minIntervalMs: Long,
        private val gridIntervalMs: Long,
        private val clock: () -> Long,
        private val onSnapshot: (ImageProxy, Matrix?) -> Unit,
    ) : ImageAnalysis.Analyzer {
        private var lastRunMs = Long.MIN_VALUE
        private var lastCaptureMs = Long.MIN_VALUE

        @Volatile
        private var sensorToView: Matrix? = null

        private val gate = Sharpness.Gate()
        private var seen = 0
        private var rejected = 0
        private var statAt = 0L

        override fun analyze(image: ImageProxy) {
            val now = clock()
            // ostrość liczymy dla każdej klatki (tanio); do OCR trafiają tylko ostre — rozmyte ruchem są pomijane
            val sharp = runCatching {
                val plane = image.planes.first()
                gate.accept(Sharpness.score(plane.buffer, image.width, image.height, plane.rowStride), now)
            }.getOrDefault(true)
            seen++
            if (!sharp) rejected++
            if (now - statAt >= 2_000L) {
                DiagLog.log("Kamera", "ostatnie 2 s: $seen klatek, $rejected odrzuconych (rozmyte), ${image.width}x${image.height}")
                seen = 0; rejected = 0; statAt = now
            }
            if (!sharp || (lastRunMs != Long.MIN_VALUE && now - lastRunMs < minIntervalMs)) {
                image.close()
                return
            }
            lastRunMs = now
            if (lastCaptureMs == Long.MIN_VALUE || now - lastCaptureMs >= gridIntervalMs) {
                lastCaptureMs = now
                runCatching { onSnapshot(image, sensorToView) }
                    .onFailure { Log.w(TAG, "Błąd przechwytywania klatki Y", it) }
            }
            delegate.analyze(image)
        }

        // CameraController pyta analizator o układ współrzędnych i przekazuje mu transformację widoku —
        // musimy to forwardować do MlKitAnalyzer, inaczej boxy nie byłyby w pikselach PreviewView.
        override fun getTargetCoordinateSystem(): Int = delegate.targetCoordinateSystem

        override fun updateTransform(matrix: Matrix?) {
            sensorToView = matrix?.let { Matrix(it) }
            delegate.updateTransform(matrix)
        }

        override fun getDefaultTargetResolution(): Size? = delegate.defaultTargetResolution
    }

    companion object {
        private const val TAG = "MlKitOcrPipeline"
        private const val MIN_ANALYSIS_INTERVAL_MS = 80L

        /** Detekcja siatki najwyżej co tyle ms (nie każda klatka — oszczędność CPU). */
        private const val GRID_INTERVAL_MS = 500L

        /** Poniżej tego podobieństwa tekstu uznajemy, że w kadrze jest już inna strona (stara siatka nie pasuje). */
        private const val GRID_STALE_TEXT_SIMILARITY = 0.6

        private const val MAX_SNAPSHOT_SIDE = 1600
        private const val JPEG_QUALITY = 80

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
