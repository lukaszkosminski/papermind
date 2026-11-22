package pl.lk.papermind

import android.app.Application
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import pl.lk.papermind.ai.AnswerEngine
import pl.lk.papermind.ai.AnswerService
import pl.lk.papermind.ai.AnswerServiceImpl
import pl.lk.papermind.ai.CloudEngine
import pl.lk.papermind.ai.GeminiNanoEngine
import pl.lk.papermind.ai.OpenCodeEngine
import pl.lk.papermind.model.EngineId
import pl.lk.papermind.ocr.MlKitOcrPipeline
import pl.lk.papermind.ocr.OcrPipeline
import pl.lk.papermind.settings.DataStoreSettingsRepository
import pl.lk.papermind.settings.SettingsRepository

/** Ręczne DI. Fake'i z ui/fake służą testom i podglądom. */
class AppContainer(context: Context) {
    private val appContext = context.applicationContext
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val settingsRepository: SettingsRepository = DataStoreSettingsRepository(appContext)

    val ocrPipeline: OcrPipeline = MlKitOcrPipeline(appContext, settingsRepository.settings)

    val engines: Map<EngineId, AnswerEngine> = listOf(
        GeminiNanoEngine(appContext, settingsRepository.settings),
        OpenCodeEngine(settingsRepository.settings),
        CloudEngine(settingsRepository.settings),
    ).associateBy { it.id }

    val answerService: AnswerService = AnswerServiceImpl(engines.values.toList(), settingsRepository, appScope)
}

class PaperMindApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
