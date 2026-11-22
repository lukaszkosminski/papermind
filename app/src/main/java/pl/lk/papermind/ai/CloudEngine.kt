package pl.lk.papermind.ai

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
import java.util.concurrent.TimeUnit

/** Cloud: Anthropic Messages API / Gemini generateContent / OpenAI Chat Completions (JSON mode). */
class CloudEngine(
    private val settings: Flow<AppSettings>,
    private val client: OkHttpClient = defaultClient(),
    private val baseUrls: Map<CloudProvider, String> = DEFAULT_BASE_URLS,
) : AnswerEngine {
    override val id = EngineId.CLOUD

    override suspend fun isAvailable(): Boolean = settings.first().cloudApiKey.isNotBlank()

    override suspend fun answer(page: ParsedPage): List<Answer> {
        val s = settings.first()
        if (s.cloudApiKey.isBlank()) throw EngineException("Brak klucza API dla chmury")
        val prompt = PromptBuilder.build(page, s.language)
        val model = s.cloudModel.ifBlank { defaultModel(s.cloudProvider) }
        val request = buildRequest(s, model, prompt)
        val result = try {
            client.newCall(request).await()
        } catch (e: IOException) {
            throw EngineException("${s.cloudProvider.label()}: błąd sieci (${e.message ?: e.javaClass.simpleName})", e)
        }
        if (!result.ok) throw EngineException("${s.cloudProvider.label()}: HTTP ${result.code} ${errorMessage(result.body)}".trim())
        val text = withContext(Dispatchers.Default) { extractText(s.cloudProvider, result.body) }
        if (text.isBlank()) throw EngineException("${s.cloudProvider.label()}: pusta odpowiedź modelu")
        return AnswerJsonParser.parse(text, page.questions, id)
    }

    internal fun buildRequest(s: AppSettings, model: String, prompt: Prompt): Request {
        val base = (baseUrls[s.cloudProvider] ?: DEFAULT_BASE_URLS.getValue(s.cloudProvider)).trimEnd('/')
        val media = "application/json".toMediaType()
        return when (s.cloudProvider) {
            CloudProvider.ANTHROPIC -> {
                val body = buildJsonObject {
                    put("model", model)
                    put("max_tokens", MAX_OUTPUT_TOKENS)
                    put("temperature", 0)
                    put("system", prompt.system)
                    putJsonArray("messages") {
                        add(buildJsonObject { put("role", "user"); put("content", prompt.user) })
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
                            putJsonArray("parts") { add(buildJsonObject { put("text", prompt.user) }) }
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
                        add(buildJsonObject { put("role", "user"); put("content", prompt.user) })
                    }
                }
                Request.Builder().url("$base/v1/chat/completions")
                    .header("Authorization", "Bearer ${s.cloudApiKey}")
                    .post(body.toString().toRequestBody(media)).build()
            }
        }
    }

    companion object {
        const val ANTHROPIC_VERSION = "2023-06-01"
        const val MAX_OUTPUT_TOKENS = 2048
        val DEFAULT_BASE_URLS = mapOf(
            CloudProvider.ANTHROPIC to "https://api.anthropic.com",
            CloudProvider.GEMINI to "https://generativelanguage.googleapis.com",
            CloudProvider.OPENAI to "https://api.openai.com",
        )
        private val json = Json { ignoreUnknownKeys = true; isLenient = true }

        fun defaultModel(p: CloudProvider) = when (p) {
            CloudProvider.ANTHROPIC -> "claude-haiku-5-5"
            CloudProvider.GEMINI -> "gemini-2.5-flash"
            CloudProvider.OPENAI -> "gpt-5-mini"
        }

        private fun CloudProvider.label() = when (this) {
            CloudProvider.ANTHROPIC -> "Anthropic"
            CloudProvider.GEMINI -> "Gemini API"
            CloudProvider.OPENAI -> "OpenAI"
        }

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
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
