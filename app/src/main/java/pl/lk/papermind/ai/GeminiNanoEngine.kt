package pl.lk.papermind.ai

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.common.GenAiException
import com.google.mlkit.genai.prompt.GenerateContentRequest
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.ImagePart
import com.google.mlkit.genai.prompt.GenerativeModel
import com.google.mlkit.genai.prompt.SystemInstruction
import com.google.mlkit.genai.prompt.TextPart
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import pl.lk.papermind.model.Answer
import pl.lk.papermind.model.EngineId
import pl.lk.papermind.model.ParsedPage
import pl.lk.papermind.settings.AppSettings

/**
 * Gemini Nano on-device przez ML Kit GenAI Prompt API (AICore).
 * Okno kontekstu jest małe — używamy trybu compact i skracamy rawText do budżetu tokenów.
 */
class GeminiNanoEngine(
    private val context: Context,
    private val settings: Flow<AppSettings>? = null,
) : AnswerEngine {
    override val id = EngineId.GEMINI_NANO

    private val lock = Mutex()
    private val downloadScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var model: GenerativeModel? = null
    @Volatile private var downloadStarted = false
    @Volatile private var downloadedBytes = 0L
    @Volatile private var lastDownloadError: String? = null

    private fun client(): GenerativeModel = model ?: Generation.getClient().also { model = it }

    override suspend fun isAvailable(): Boolean = status().available

    override suspend fun status(): EngineStatus {
        val aiCore = aiCoreVersion()
            ?: return EngineStatus(false, "Brak aplikacji AICore (Android AICore) — zainstaluj/zaktualizuj ją w Sklepie Play")
        return try {
            val st = client().checkStatus()
            Log.i(TAG, "checkStatus=$st aicore=$aiCore")
            when (st) {
                FeatureStatus.AVAILABLE -> EngineStatus(true, "Gemini Nano gotowy (AICore $aiCore)")
                FeatureStatus.DOWNLOADABLE -> {
                    startDownload()
                    EngineStatus(false, lastDownloadError?.let { "Pobieranie modelu nie powiodło się: $it" }
                        ?: "Model Gemini Nano nie jest pobrany — rozpoczęto pobieranie (Wi-Fi, kilka minut). Spróbuj ponownie później.")
                }
                FeatureStatus.DOWNLOADING -> {
                    startDownload() // podpina się pod postęp, jeśli pobieranie zaczął ktoś inny
                    val mb = downloadedBytes / 1_000_000
                    EngineStatus(false, "Trwa pobieranie modelu Gemini Nano" + (if (mb > 0) " ($mb MB)" else "") + " — poczekaj i spróbuj ponownie.")
                }
                else -> EngineStatus(false, UNAVAILABLE_HINT + " (AICore $aiCore)")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "checkStatus failed", e)
            EngineStatus(false, describe(e) + " (AICore $aiCore)")
        }
    }

    private fun aiCoreVersion(): String? = try {
        context.packageManager.getPackageInfo(AICORE_PACKAGE, 0).versionName ?: "?"
    } catch (_: PackageManager.NameNotFoundException) {
        null
    }

    private fun startDownload() {
        if (downloadStarted) return
        downloadStarted = true
        lastDownloadError = null
        client().download()
            .onEach {
                when (it) {
                    is DownloadStatus.DownloadProgress -> downloadedBytes = it.totalBytesDownloaded
                    is DownloadStatus.DownloadFailed -> {
                        Log.w(TAG, "download failed", it.e)
                        lastDownloadError = describe(it.e)
                        downloadStarted = false
                    }
                    is DownloadStatus.DownloadCompleted -> downloadStarted = false
                    else -> Unit
                }
            }
            .launchIn(downloadScope)
    }

    override suspend fun answer(page: ParsedPage): List<Answer> = lock.withLock {
        val m = client()
        val language = settings?.first()?.language
        val tokenLimit = runCatching { m.getTokenLimit() }.getOrNull()
        val image = page.snapshotJpeg?.takeIf { it.isNotEmpty() }
        // Zdjęcie (krzyżówki): Nano jest multimodalne (ImagePart), ale kosztuje tokeny kontekstu i bywa
        // niedostępne na urządzeniu — przy błędzie powtarzamy tylko z tekstem.
        val withImage = image?.let { generate(m, page, language, tokenLimit, it) }
        if (!withImage.isNullOrEmpty()) return@withLock withImage
        generate(m, page.copy(snapshotJpeg = null), language, tokenLimit, null) ?: throw EngineException("Gemini Nano zwrócił pustą odpowiedź")
    }

    /** null gdy próba ze zdjęciem się nie powiodła (wyjątek). Bez zdjęcia wyjątki są propagowane. */
    private suspend fun generate(
        m: GenerativeModel, page: ParsedPage, language: String?, tokenLimit: Int?, image: ByteArray?,
    ): List<Answer>? {
        val prompt = PromptBuilder.build(page, language, compact = true, maxRawChars = rawBudget(page, tokenLimit, image != null))
        val request = try {
            val builder = if (image != null) {
                GenerateContentRequest.Builder(SystemInstruction(prompt.system), ImagePart(image), TextPart(prompt.user))
            } else {
                GenerateContentRequest.Builder(SystemInstruction(prompt.system), TextPart(prompt.user))
            }
            builder.apply {
                temperature = 0f
                candidateCount = 1
                maxOutputTokens = MAX_OUTPUT_TOKENS
            }.build()
        } catch (e: Exception) {
            if (image != null) return null
            throw EngineException("Gemini Nano: ${e.message ?: e.javaClass.simpleName}", e)
        }
        val text = try {
            m.generateContent(request).candidates.firstOrNull()?.text
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (image != null) return null
            throw EngineException("Gemini Nano: ${e.message ?: e.javaClass.simpleName}", e)
        }
        if (text == null) return if (image != null) null else throw EngineException("Gemini Nano zwrócił pustą odpowiedź")
        return AnswerJsonParser.parse(text, page.questions, id)
    }

    internal companion object {
        private const val TAG = "PaperMind/Nano"
        private const val AICORE_PACKAGE = "com.google.android.aicore"
        private const val UNAVAILABLE_HINT = "Gemini Nano niedostępny: AICore nie ma jeszcze konfiguracji dla tej funkcji albo urządzenie jej nie wspiera. " +
            "Zaktualizuj „Android AICore” i „Usługi systemowe Google Play” w Sklepie Play, zostaw telefon z internetem (do kilku godzin), zrestartuj. " +
            "Odblokowany bootloader = brak wsparcia."

        /** Czytelny opis błędu AICore z podpowiedzią (tabela błędów z dokumentacji ML Kit GenAI). */
        fun describe(e: Throwable): String {
            val g = e as? GenAiException ?: (e.cause as? GenAiException)
            if (g == null) return "Błąd Gemini Nano: ${e.message ?: e.javaClass.simpleName}"
            val codes = "kod ${g.errorCode}/${g.aiCoreErrorCode}"
            val hint = when {
                g.aiCoreErrorCode == 601 || g.errorCode == 4 ->
                    "nie można połączyć się z usługą AICore — zaktualizuj AICore w Sklepie Play i przeinstaluj PaperMind"
                g.aiCoreErrorCode == 606 || g.errorCode == 3 ->
                    "AICore nie pobrał jeszcze konfiguracji (może potrwać do kilku godzin; pomaga restart) lub bootloader jest odblokowany"
                g.errorCode == 1 || g.errorCode == 0 ->
                    "problem z siecią podczas pobierania — połącz z internetem i spróbuj za kilka minut"
                else -> g.message ?: "nieznany błąd"
            }
            return "Gemini Nano: $hint ($codes)"
        }
        const val MAX_OUTPUT_TOKENS = 1024
        private const val DEFAULT_TOKEN_LIMIT = 4000
        private const val CHARS_PER_TOKEN = 3
        private const val IMAGE_TOKENS = 600 // zapas na zdjęcie w kontekście

        /** Ile znaków surowego tekstu zmieści się obok instrukcji, pytań i miejsca na odpowiedź. */
        fun rawBudget(page: ParsedPage, tokenLimit: Int?, withImage: Boolean = false): Int {
            val limit = tokenLimit ?: DEFAULT_TOKEN_LIMIT
            val questionChars = page.questions.sumOf { q -> q.text.length + q.options.sumOf { it.text.length + 6 } + 40 }
            val usedTokens = 700 + MAX_OUTPUT_TOKENS / 2 + questionChars / CHARS_PER_TOKEN + (if (withImage) IMAGE_TOKENS else 0)
            return ((limit - usedTokens) * CHARS_PER_TOKEN).coerceIn(0, 6000)
        }
    }
}
