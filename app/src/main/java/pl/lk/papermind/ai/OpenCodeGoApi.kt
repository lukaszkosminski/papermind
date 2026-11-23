package pl.lk.papermind.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import okhttp3.Request
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit

/** OpenCode Go (subskrypcja z kluczem API): nagłówki wymagane przez bramkę i pobieranie listy modeli. */
object OpenCodeGoApi {
    const val BASE_URL = "https://opencode.ai/inference/go"
    /** Preferowany model (wymaga regionu „Global” w Console → Settings → Privacy). */
    const val DEFAULT_MODEL = "deepseek-v4.1-flash"
    private const val USER_AGENT = "papermind/0.1"

    /** Stabilny identyfikator sesji (wymagany przez Go do routingu i cache'u promptów) — jeden na uruchomienie aplikacji. */
    private val sessionId = "ses_" + UUID.randomUUID().toString().replace("-", "")

    /** Modele z listy, które przyjmują obrazy (uzupełniane przy pobraniu listy). */
    @Volatile var imageModels: Set<String> = emptySet()

    class Model(val id: String, val name: String, val vision: Boolean)

    fun headers(b: Request.Builder, apiKey: String, authenticate: Boolean = true): Request.Builder = b
        .header("User-Agent", USER_AGENT)
        .header("x-opencode-session", sessionId)
        .header("Content-Type", "application/json")
        .apply { if (authenticate) header("Authorization", "Bearer ${apiKey.trim()}") }

    /** Lista modeli tekstowych dostępnych w Go (bez darmowych — działają tylko wewnątrz OpenCode). */
    suspend fun models(apiKey: String): List<Model> = withContext(Dispatchers.IO) {
        val req = headers(Request.Builder().url("$BASE_URL/v1/models"), apiKey.trim()).get().build()
        val client = Http.sharedClient.newBuilder().callTimeout(15, TimeUnit.SECONDS).build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
            parseModels(resp.body.string())
        }.also { list -> imageModels = list.filter { it.vision }.mapTo(HashSet()) { it.id } }
    }

    internal fun parseModels(body: String): List<Model> {
        val data: JsonArray = Json.parseToJsonElement(body).jsonObject["data"]?.jsonArray ?: return emptyList()
        return data.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val id = (o["id"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
            if (id.contains("free")) return@mapNotNull null
            val type = (o["type"] as? JsonPrimitive)?.contentOrNull
            if (type != null && type != "chat") return@mapNotNull null
            val name = (o["name"] as? JsonPrimitive)?.contentOrNull ?: id
            val input = ((o["modalities"] as? JsonObject)?.get("input") as? JsonArray).orEmpty()
            Model(id, name, vision = input.any { (it as? JsonPrimitive)?.contentOrNull?.startsWith("image/") == true })
        }.sortedBy { it.name.lowercase() }
    }

    private fun JsonArray?.orEmpty() = this ?: JsonArray(emptyList())
}
