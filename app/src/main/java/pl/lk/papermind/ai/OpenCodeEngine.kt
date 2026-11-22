package pl.lk.papermind.ai

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import pl.lk.papermind.model.Answer
import pl.lk.papermind.model.EngineId
import pl.lk.papermind.model.ParsedPage
import pl.lk.papermind.settings.AppSettings
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Klient serwera `opencode serve` na PC. Obsługuje oba warianty HTTP API:
 *  - V2 (opencode ≥ 2): `POST /api/session`, `POST /api/session/{id}/prompt`, `POST /api/experimental/session/{id}/wait`,
 *    `GET /api/session/{id}/message` (wykrywany przez `GET /api/info`),
 *  - klasyczny: `POST /session`, `POST /session/{id}/message` (synchroniczny, health: `/global/health`).
 * Jedna sesja wielokrotnego użytku (odświeżana po zmianie adresu/modelu, błędzie, anulowaniu i co [MAX_SESSION_USES] użyć).
 */
class OpenCodeEngine(
    private val settings: Flow<AppSettings>,
    private val client: OkHttpClient = defaultClient(),
) : AnswerEngine {
    override val id = EngineId.OPENCODE

    private enum class Api { V2, LEGACY }

    private class SessionState(val key: String, val api: Api, val id: String, var uses: Int = 0)

    private val lock = Mutex()
    private var session: SessionState? = null

    private fun AppSettings.sanitized() = copy(
        openCodeUrl = openCodeUrl.trim(), openCodeUser = openCodeUser.trim(),
        openCodePassword = openCodePassword.trim(), openCodeModel = openCodeModel.trim(),
    )

    override suspend fun isAvailable(): Boolean = try {
        val s = settings.first().sanitized()
        s.openCodeUrl.isNotBlank() && detectApi(s, quick = true) != null
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        false
    }

    /** V2 jeśli `/api/info` zwraca JSON, klasyczne jeśli `/global/health` ({"healthy":true}), inaczej null. */
    private suspend fun detectApi(s: AppSettings, quick: Boolean): Api? {
        val c = if (quick) client.newBuilder().callTimeout(4, TimeUnit.SECONDS).build() else client
        val info = runCatching { c.newCall(get(s, "/api/info")).await() }.getOrNull()
        if (info != null && info.ok && isJson(info) && parse(info.body) is JsonObject) return Api.V2
        val health = runCatching { c.newCall(get(s, "/global/health")).await() }.getOrNull() ?: return null
        if (health.ok && isJson(health)) {
            val healthy = ((parse(health.body) as? JsonObject)?.get("healthy") as? JsonPrimitive)?.booleanOrNull
            if (healthy != false) return Api.LEGACY
        }
        return null
    }

    /**
     * Ze zdjęciem (krzyżówki): pierwsza próba z załącznikiem; gdy model nie obsługuje obrazów (OpenCode odpowiada
     * wtedy prozą / błędem) — druga próba w nowej sesji, tylko tekst.
     */
    override suspend fun answer(page: ParsedPage): List<Answer> = lock.withLock {
        val s = settings.first().sanitized()
        if (s.openCodeUrl.isBlank()) throw EngineException("Nie ustawiono adresu serwera OpenCode")
        val image = page.snapshotJpeg?.takeIf { it.isNotEmpty() }
        if (image != null) {
            val withImage = try { run(s, page, image) } catch (e: EngineException) { emptyList() }
            if (withImage.isNotEmpty()) return@withLock withImage
            dropSession(s)
        }
        run(s, page.copy(snapshotJpeg = null), null)
    }

    private suspend fun run(s: AppSettings, page: ParsedPage, image: ByteArray?): List<Answer> {
        val prompt = PromptBuilder.build(page, s.language)
        val text = try {
            withSession(s) { st -> prompt(s, st, prompt.combined, image) }
        } catch (e: CancellationException) {
            // zapytanie mogło zostać w sesji — nie używaj jej ponownie
            dropSession(s)
            throw e
        } catch (e: EngineException) {
            dropSession(s)
            throw e
        } catch (e: IOException) {
            dropSession(s)
            throw EngineException("OpenCode: błąd połączenia (${e.message ?: e.javaClass.simpleName})", e)
        } catch (e: IllegalArgumentException) {
            dropSession(s)
            throw EngineException("OpenCode: niepoprawny adres serwera lub dane logowania", e)
        } catch (e: Exception) {
            dropSession(s)
            throw EngineException("OpenCode: ${e.message ?: e.javaClass.simpleName}", e)
        }
        if (text.isBlank()) throw EngineException("OpenCode: pusta odpowiedź modelu")
        return AnswerJsonParser.parse(text, page.questions, id)
    }

    private suspend fun <T> withSession(s: AppSettings, block: suspend (SessionState) -> T): T {
        val key = sessionKey(s)
        var st = session
        if (st == null || st.key != key || st.uses >= MAX_SESSION_USES) {
            dropSession(s)
            st = createSession(s, key)
            session = st
        }
        st.uses++
        return block(st)
    }

    private suspend fun createSession(s: AppSettings, key: String): SessionState {
        val api = detectApi(s, quick = false)
            ?: throw EngineException("OpenCode: serwer (${s.openCodeUrl}) nie odpowiada")
        val model = parseModel(s.openCodeModel)
        val body = buildJsonObject {
            put("title", "PaperMind")
            if (api == Api.V2 && model != null) {
                putJsonObject("model") { put("id", model.second); put("providerID", model.first) }
            }
        }
        val path = if (api == Api.V2) "/api/session" else "/session"
        val res = post(s, path, body.toString())
        checkOk(res, "utworzenie sesji")
        val root = parse(res.body) as? JsonObject
        val sid = if (api == Api.V2) ((root?.get("data") as? JsonObject)?.str("id")) else root?.str("id")
        return SessionState(key, api, sid ?: throw EngineException("OpenCode: brak id sesji w odpowiedzi"))
    }

    private suspend fun prompt(s: AppSettings, st: SessionState, text: String, image: ByteArray? = null): String = when (st.api) {
        // Uwaga: `image` jako data URI (base64 JPEG)
        Api.LEGACY -> {
            val model = parseModel(s.openCodeModel)
            val body = buildJsonObject {
                putJsonArray("parts") {
                    add(buildJsonObject { put("type", "text"); put("text", text) })
                    if (image != null) add(buildJsonObject {
                        put("type", "file"); put("mime", "image/jpeg"); put("filename", "page.jpg"); put("url", dataUri(image))
                    })
                }
                if (model != null) putJsonObject("model") { put("providerID", model.first); put("modelID", model.second) }
            }
            val res = post(s, "/session/${st.id}/message", body.toString())
            checkOk(res, "zapytanie")
            val root = parse(res.body) as? JsonObject ?: throw EngineException("OpenCode: niepoprawna odpowiedź")
            (root["info"] as? JsonObject)?.get("error")?.let { err ->
                throw EngineException("OpenCode: ${errText(err)}")
            }
            (root["parts"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
                .filter { it.str("type") == "text" }.joinToString("") { it.str("text").orEmpty() }
        }
        Api.V2 -> {
            val body = buildJsonObject {
                put("text", text)
                if (image != null) putJsonArray("files") {
                    add(buildJsonObject { put("uri", dataUri(image)); put("name", "page.jpg") })
                }
            }
            val sent = post(s, "/api/session/${st.id}/prompt", body.toString())
            checkOk(sent, "wysłanie promptu")
            val wait = post(s, "/api/experimental/session/${st.id}/wait", "{}")
            checkOk(wait, "oczekiwanie na odpowiedź")
            val res = client.newCall(get(s, "/api/session/${st.id}/message?order=desc&limit=10")).await()
            checkOk(res, "pobranie odpowiedzi")
            val data = ((parse(res.body) as? JsonObject)?.get("data") as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
            val assistant = data.firstOrNull { it.str("type") == "assistant" }
                ?: throw EngineException("OpenCode: brak odpowiedzi asystenta")
            (assistant["content"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
                .filter { it.str("type") == "text" }.joinToString("") { it.str("text").orEmpty() }
        }
    }

    private suspend fun dropSession(s: AppSettings) {
        val st = session ?: return
        session = null
        // best-effort sprzątanie po stronie serwera, także przy anulowaniu
        withContext(NonCancellable) {
            runCatching {
                val path = if (st.api == Api.V2) "/api/session/${st.id}" else "/session/${st.id}"
                client.newBuilder().callTimeout(3, TimeUnit.SECONDS).build()
                    .newCall(request(s, path).delete().build()).await()
            }
        }
    }

    // --- HTTP ---

    private fun request(s: AppSettings, path: String): Request.Builder {
        val b = Request.Builder().url(s.openCodeUrl.trim().trimEnd('/') + path)
        if (s.openCodePassword.isNotEmpty()) {
            b.header("Authorization", Credentials.basic(s.openCodeUser.ifBlank { "opencode" }, s.openCodePassword))
        }
        return b
    }

    private fun get(s: AppSettings, path: String) = request(s, path).get().build()

    private suspend fun post(s: AppSettings, path: String, json: String): HttpResult =
        client.newCall(request(s, path).post(json.toRequestBody("application/json".toMediaType())).build()).await()

    private fun checkOk(r: HttpResult, what: String) {
        if (r.ok) return
        val hint = if (r.code == 401) " (zły login/hasło)" else ""
        throw EngineException("OpenCode: HTTP ${r.code}$hint przy: $what")
    }

    private fun isJson(r: HttpResult) = r.contentType?.contains("json", ignoreCase = true) == true

    private fun sessionKey(s: AppSettings) = "${s.openCodeUrl.trim()}|${s.openCodeModel.trim()}|${s.openCodeUser}"

    companion object {
        const val MAX_SESSION_USES = 20
        private fun dataUri(jpeg: ByteArray) = "data:image/jpeg;base64," + java.util.Base64.getEncoder().encodeToString(jpeg)
        private val json = Json { ignoreUnknownKeys = true; isLenient = true }

        fun defaultClient(): OkHttpClient = Http.sharedClient.newBuilder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .callTimeout(150, TimeUnit.SECONDS)
            .build()

        /** "provider/model" → (provider, model); puste/niepoprawne → null (model domyślny serwera). */
        internal fun parseModel(s: String): Pair<String, String>? {
            val t = s.trim()
            val i = t.indexOf('/')
            return if (i > 0 && i < t.length - 1) t.substring(0, i) to t.substring(i + 1) else null
        }

        private fun parse(body: String) = runCatching { json.parseToJsonElement(body) }.getOrNull()
        private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
        private fun JsonArray?.orEmpty(): JsonArray = this ?: JsonArray(emptyList())
        private fun errText(e: kotlinx.serialization.json.JsonElement): String =
            ((e as? JsonObject)?.let { o -> (o["data"] as? JsonObject)?.str("message") ?: o.str("message") ?: o.str("name") }
                ?: e.toString()).take(200)
    }
}
