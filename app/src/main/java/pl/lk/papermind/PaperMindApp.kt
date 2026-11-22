package pl.lk.papermind

import android.app.Application
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import pl.lk.papermind.ai.AnswerEngine
import pl.lk.papermind.ai.AnswerService
import pl.lk.papermind.model.EngineId
import pl.lk.papermind.ocr.OcrPipeline
import pl.lk.papermind.settings.SettingsRepository
import pl.lk.papermind.ui.fake.FakeAnswerService
import pl.lk.papermind.ui.fake.FakeEngine
import pl.lk.papermind.ui.fake.FakeOcrPipeline
import pl.lk.papermind.ui.fake.InMemorySettingsRepository

/** Ręczne DI — jedno miejsce, w którym podmienia się fake'i na prawdziwe implementacje. */
class AppContainer(@Suppress("UNUSED_PARAMETER") context: Context) {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // TODO(integracja): val settingsRepository: SettingsRepository = DataStoreSettingsRepository(context)
    val settingsRepository: SettingsRepository = InMemorySettingsRepository()

    // TODO(integracja): val ocrPipeline: OcrPipeline = MlKitOcrPipeline(context, settingsRepository.settings)
    val ocrPipeline: OcrPipeline = FakeOcrPipeline()

    // TODO(integracja):
    //   val engines: Map<EngineId, AnswerEngine> = listOf(
    //       GeminiNanoEngine(context), OpenCodeEngine(settingsRepository.settings), CloudEngine(settingsRepository.settings),
    //   ).associateBy { it.id }
    val engines: Map<EngineId, AnswerEngine> = EngineId.entries.associateWith { FakeEngine(it) }

    // TODO(integracja): val answerService: AnswerService = AnswerServiceImpl(engines.values.toList(), settingsRepository, appScope)
    val answerService: AnswerService = FakeAnswerService(appScope)
}

class PaperMindApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
