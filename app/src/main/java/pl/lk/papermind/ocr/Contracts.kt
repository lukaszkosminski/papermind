package pl.lk.papermind.ocr

import androidx.camera.view.LifecycleCameraController
import kotlinx.coroutines.flow.Flow
import pl.lk.papermind.model.DetectedQuestion
import pl.lk.papermind.model.OcrFrame
import pl.lk.papermind.model.ParsedPage
import java.util.concurrent.Executor

/** Agent A. Podpina analizę OCR pod kontroler kamery należący do UI. */
interface OcrPipeline {
    /** Surowe klatki OCR (do podglądu ramek tekstu). */
    val frames: Flow<OcrFrame>
    /** Strony po stabilizacji i parsowaniu — wejście do silników AI. */
    val pages: Flow<ParsedPage>
    fun bind(controller: LifecycleCameraController, executor: Executor)
    fun unbind(controller: LifecycleCameraController)
}

/** Agent A. Czysta logika (bez Androida) — testy jednostkowe JVM. */
interface QuestionParser {
    fun parse(frame: OcrFrame): List<DetectedQuestion>
}
