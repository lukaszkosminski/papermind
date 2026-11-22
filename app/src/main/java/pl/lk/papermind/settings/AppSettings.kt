package pl.lk.papermind.settings

import kotlinx.coroutines.flow.Flow
import pl.lk.papermind.model.EngineId

enum class CloudProvider { ANTHROPIC, GEMINI, OPENAI }

data class AppSettings(
    val engine: EngineId = EngineId.GEMINI_NANO,
    /** Gdy wybrany silnik niedostępny — spróbuj kolejnego. */
    val fallbackEnabled: Boolean = true,
    // OpenCode (`opencode serve` na PC)
    val openCodeUrl: String = "http://192.168.1.10:4096",
    val openCodeUser: String = "opencode",
    val openCodePassword: String = "",
    val openCodeModel: String = "",          // "provider/model", puste = domyślny z serwera
    // Cloud
    val cloudProvider: CloudProvider = CloudProvider.ANTHROPIC,
    val cloudApiKey: String = "",
    val cloudModel: String = "",             // puste = domyślny dla providera
    // Zachowanie
    val language: String = "pl",
    val showExplanations: Boolean = true,
    val stabilityMs: Long = 700,
)

/** Agent B implementuje (DataStore). */
interface SettingsRepository {
    val settings: Flow<AppSettings>
    suspend fun update(transform: (AppSettings) -> AppSettings)
}
