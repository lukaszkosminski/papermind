package pl.lk.papermind.ai

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import pl.lk.papermind.model.Answer
import pl.lk.papermind.model.EngineId
import pl.lk.papermind.model.ParsedPage
import pl.lk.papermind.settings.AppSettings
import pl.lk.papermind.settings.CloudProvider
import java.io.IOException
import java.util.Base64
import java.util.concurrent.TimeUnit

/** Cloud: Anthropic Messages API / Gemini generateContent / OpenAI Chat Completions (JSON mode). */
class CloudEngine(
    private val settings: Flow<AppSettings>,
    private val client: OkHttpClient = defaultClient(),
    private val baseUrls: Map<CloudProvider, String> = DEFAULT_BASE_URLS,
    private val clock: () -> Long = System::currentTimeMillis,
) : AnswerEngine {
    override val id = EngineId.CLOUD

    private var availableUntil = 0L
    private var availableKey = ""

    /** Klucz ustawiony + lekkie zapytanie o listę modeli (timeout 5 s); sukces cache'owany na [AVAILABILITY_TTL_MS]. */
    override suspend fun isAvailable(): Boolean {
        val s = settings.first().sanitized()
        if (s.cloudApiKey.isEmpty()) return false
        val fingerprint = "${s.cloudProvider}|${s.cloudApiKey}"
        if (availableKey == fingerprint && clock() < availableUntil) return true
        val ok = try {
            val c = client.newBuilder().callTimeout(5, TimeUnit.SECONDS).build()
            val r = c.newCall(buildProbe(s)).await()
            r.ok || r.code == 429 // 429 = klucz poprawny, tylko limit
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        }
        if (ok) { availableKey = fingerprint; availableUntil = clock() + AVAILABILITY_TTL_MS } else availableKey = ""
        return ok
    }

    override suspend fun answer(page: ParsedPage): List<Answer> {
        val s = settings.first().sanitized()
        if (s.cloudApiKey.isEmpty()) throw EngineException("Brak klucza API dla chmury")
        val label = s.cloudProvider.label()
        try {
            val prompt = PromptBuilder.build(page, s.language)
            val model = s.cloudModel.ifBlank { defaultModel(s.cloudProvider) }
            val request = buildRequest(s, model, prompt, page.snapshotJpeg)
            val result = client.newCall(request).await()
            if (!result.ok) throw EngineException("$label: HTTP ${result.code} ${errorMessage(result.body)}".trim())
            val text = withContext(Dispatchers.Default) { extractText(s.cloudProvider, result.body) }
            if (text.isBlank()) throw EngineException("$label: pusta odpowiedź modelu")
            return AnswerJsonParser.parse(text, page.questions, id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: EngineException) {
            throw e
        } catch (e: IOException) {
            throw EngineException("$label: błąd sieci (${e.message ?: e.javaClass.simpleName})", e)
        } catch (e: IllegalArgumentException) {
            throw EngineException("$label: niepoprawna konfiguracja (klucz API / model zawiera niedozwolone znaki)", e)
        } catch (e: Exception) {
            throw EngineException("$label: ${e.message ?: e.javaClass.simpleName}", e)
        }
    }

    private fun AppSettings.sanitized() = copy(cloudApiKey = cloudApiKey.trim(), cloudModel = cloudModel.trim())

    internal fun buildProbe(s: AppSettings): Request {
        val base = (baseUrls[s.cloudProvider] ?: DEFAULT_BASE_URLS.getValue(s.cloudProvider)).trimEnd('/')
        return when (s.cloudProvider) {
            CloudProvider.ANTHROPIC -> Request.Builder().url("$base/v1/models?limit=1")
                .header("x-api-key", s.cloudApiKey).header("anthropic-version", ANTHROPIC_VERSION).get().build()
            CloudProvider.GEMINI -> Request.Builder().url("$base/v1beta/models?pageSize=1")
                .header("x-goog-api-key", s.cloudApiKey).get().build()
            CloudProvider.OPENAI -> Request.Builder().url("$base/v1/models")
                .header("Authorization", "Bearer ${s.cloudApiKey}").get().build()
        }
    }

    internal fun buildRequest(s0: AppSettings, model: String, prompt: Prompt, imageJpeg: ByteArray? = null): Request {
        val s = s0.sanitized()
        val base = (baseUrls[s.cloudProvider] ?: DEFAULT_BASE_URLS.getValue(s.cloudProvider)).trimEnd('/')
        val media = "application/json".toMediaType()
        val image = imageJpeg?.takeIf { it.isNotEmpty() }?.let { Base64.getEncoder().encodeToString(it) }
        return when (s.cloudProvider) {
            CloudProvider.ANTHROPIC -> {
                val body = buildJsonObject {
                    put("model", model)
                    put("max_tokens", MAX_OUTPUT_TOKENS)
                    put("temperature", 0)
                    put("system", prompt.system)
                    putJsonArray("messages") {
                        add(buildJsonObject {
                            put("role", "user")
                            if (image == null) put("content", prompt.user) else putJsonArray("content") {
                                add(buildJsonObject {
                                    put("type", "image")
                                    putJsonObject("source") {
                                        put("type", "base64"); put("media_type", "image/jpeg"); put("data", image)
                                    }
                                })
                                add(buildJsonObject { put("type", "text"); put("text", prompt.user) })
                            }
                        })
                    }
                }
                Request.Builder().url("$base/v1/messages")
                    .header("x-api-key", s.cloudApiKey)
                    .header("anthropic-version", ANTHROPIC_VERSION)
                    .post(body.toString().toRequestBody(media)).build()
            }
            CloudProvider.GEMINI -> {
                val body = buildJsonObject {
                    putJsonObject("systemInstruction") {
                        putJsonArray("parts") { add(buildJsonObject { put("text", prompt.system) }) }
                    }
                    putJsonArray("contents") {
                        add(buildJsonObject {
                            put("role", "user")
                            putJsonArray("parts") {
                                if (image != null) add(buildJsonObject {
                                    putJsonObject("inline_data") { put("mime_type", "image/jpeg"); put("data", image) }
                                })
                                add(buildJsonObject { put("text", prompt.user) })
                            }
                        })
                    }
                    putJsonObject("generationConfig") {
                        put("responseMimeType", "application/json")
                        put("temperature", 0)
                        put("maxOutputTokens", MAX_OUTPUT_TOKENS)
                    }
                }
                Request.Builder().url("$base/v1beta/models/$model:generateContent")
                    .header("x-goog-api-key", s.cloudApiKey)
                    .post(body.toString().toRequestBody(media)).build()
            }
            CloudProvider.OPENAI -> {
                val body = buildJsonObject {
                    put("model", model)
                    putJsonObject("response_format") { put("type", "json_object") }
                    putJsonArray("messages") {
                        add(buildJsonObject { put("role", "system"); put("content", prompt.system) })
                        add(buildJsonObject {
                            put("role", "user")
                            if (image == null) put("content", prompt.user) else putJsonArray("content") {
                                add(buildJsonObject { put("type", "text"); put("text", prompt.user) })
                                add(buildJsonObject {
                                    put("type", "image_url")
                                    putJsonObject("image_url") { put("url", "data:image/jpeg;base64,$image") }
                                })
                            }
                        })
                    }
                }
                Request.Builder().url("$base/v1/chat/completions")
                    .header("Authorization", "Bearer ${s.cloudApiKey}")
                    .post(body.toString().toRequestBody(media)).build()
            }
        }
    }

    companion object {
        const val AVAILABILITY_TTL_MS = 60_000L
        const val ANTHROPIC_VERSION = "2023-06-01"
        const val MAX_OUTPUT_TOKENS = 4096
        val DEFAULT_BASE_URLS = mapOf(
            CloudProvider.ANTHROPIC to "https://api.anthropic.com",
            CloudProvider.GEMINI to "https://generativelanguage.googleapis.com",
            CloudProvider.OPENAI to "https://api.openai.com",
        )
        private val json = Json { ignoreUnknownKeys = true; isLenient = true }

        fun defaultModel(p: CloudProvider) = when (p) {
            CloudProvider.ANTHROPIC -> "claude-haiku-5-5"
            CloudProvider.GEMINI -> "gemini-3.8-flash"
            CloudProvider.OPENAI -> "gpt-6-luna"
        }

        private fun CloudProvider.label() = when (this) {
            CloudProvider.ANTHROPIC -> "Anthropic"
            CloudProvider.GEMINI -> "Gemini API"
            CloudProvider.OPENAI -> "OpenAI"
        }

        fun defaultClient(): OkHttpClient = Http.sharedClient.newBuilder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .callTimeout(75, TimeUnit.SECONDS)
            .build()

        internal fun extractText(p: CloudProvider, body: String): String {
            val root = runCatching { json.parseToJsonElement(body) }.getOrNull() as? JsonObject ?: return ""
            return when (p) {
                CloudProvider.ANTHROPIC -> (root["content"] as? JsonArray).orEmpty()
                    .filterIsInstance<JsonObject>()
                    .filter { it.str("type") == "text" }
                    .joinToString("") { it.str("text").orEmpty() }
                CloudProvider.GEMINI -> (((root["candidates"] as? JsonArray)?.firstOrNull() as? JsonObject)
                    ?.get("content") as? JsonObject)?.get("parts").let { it as? JsonArray }.orEmpty()
                    .filterIsInstance<JsonObject>()
                    .joinToString("") { it.str("text").orEmpty() }
                CloudProvider.OPENAI -> (((root["choices"] as? JsonArray)?.firstOrNull() as? JsonObject)
                    ?.get("message") as? JsonObject)?.str("content").orEmpty()
            }
        }

        internal fun errorMessage(body: String): String {
            val root = runCatching { json.parseToJsonElement(body) }.getOrNull() as? JsonObject
            val err = root?.get("error")
            val msg = when (err) {
                is JsonObject -> err.str("message")
                is JsonPrimitive -> err.contentOrNull
                else -> null
            }
            return (msg ?: body).take(200)
        }

        private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
        private fun JsonArray?.orEmpty(): JsonArray = this ?: JsonArray(emptyList())
    }
}
