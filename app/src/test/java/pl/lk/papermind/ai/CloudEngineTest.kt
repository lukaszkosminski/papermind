package pl.lk.papermind.ai

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import pl.lk.papermind.model.EngineId
import pl.lk.papermind.settings.AppSettings
import pl.lk.papermind.settings.CloudProvider

class CloudEngineTest {
    private fun jstr(s: String) = kotlinx.serialization.json.JsonPrimitive(s).toString()
    private lateinit var server: MockWebServer
    private val answerJson = """{"answers":[{"id":"q1","options":["B"],"text":"Mitochondrium","explanation":"Wytwarza ATP.","confidence":0.95}]}"""

    @Before fun setUp() { server = MockWebServer().also { it.start() } }
    @After fun tearDown() { server.close() }

    private fun engine(s: AppSettings): CloudEngine {
        val base = server.url("/").toString().trimEnd('/')
        return CloudEngine(MutableStateFlow(s), baseUrls = CloudProvider.entries.associateWith { base })
    }

    private fun json(body: String) = MockResponse.Builder().code(200).addHeader("Content-Type", "application/json").body(body).build()
    

    @Test fun anthropic() = runTest {
        server.enqueue(json("""{"content":[{"type":"text","text":${jstr(answerJson)}}],"stop_reason":"end_turn"}"""))
        val e = engine(AppSettings(cloudProvider = CloudProvider.ANTHROPIC, cloudApiKey = "sk-ant", language = "pl"))
        val r = e.answer(page(choiceQ("q1")))
        assertEquals(listOf("B"), r.single().correctOptionLabels)
        assertEquals(EngineId.CLOUD, r.single().engine)
        val req = server.takeRequest()
        assertEquals("/v1/messages", req.url.encodedPath)
        assertEquals("sk-ant", req.headers["x-api-key"])
        assertEquals("2023-06-01", req.headers["anthropic-version"])
        val body = Json.parseToJsonElement(req.body!!.utf8()).jsonObject
        assertEquals("claude-haiku-5-5", body["model"]!!.jsonPrimitive.content)
        assertTrue(body["system"]!!.jsonPrimitive.content.contains("STRICT JSON"))
        assertTrue(body["messages"]!!.jsonArray[0].jsonObject["content"]!!.jsonPrimitive.content.contains("id=Q1"))
    }

    @Test fun anthropicCustomModel() = runTest {
        server.enqueue(json("""{"content":[{"type":"text","text":${jstr(answerJson)}}]}"""))
        engine(AppSettings(cloudProvider = CloudProvider.ANTHROPIC, cloudApiKey = "k", cloudModel = "claude-opus-5-5")).answer(page(choiceQ("q1")))
        val body = Json.parseToJsonElement(server.takeRequest().body!!.utf8()).jsonObject
        assertEquals("claude-opus-5-5", body["model"]!!.jsonPrimitive.content)
    }

    @Test fun gemini() = runTest {
        server.enqueue(json("""{"candidates":[{"content":{"parts":[{"text":${jstr(answerJson)}}]}}]}"""))
        val r = engine(AppSettings(cloudProvider = CloudProvider.GEMINI, cloudApiKey = "gk")).answer(page(choiceQ("q1")))
        assertEquals("Mitochondrium", r.single().text)
        val req = server.takeRequest()
        assertTrue(req.url.encodedPath.startsWith("/v1beta/models/") && req.url.encodedPath.endsWith(":generateContent"))
        assertEquals("gk", req.headers["x-goog-api-key"])
        val body = Json.parseToJsonElement(req.body!!.utf8()).jsonObject
        assertEquals("application/json", body["generationConfig"]!!.jsonObject["responseMimeType"]!!.jsonPrimitive.content)
        assertTrue(body["systemInstruction"] is JsonObject)
        assertTrue(body["contents"] is JsonArray)
    }

    @Test fun openai() = runTest {
        server.enqueue(json("""{"choices":[{"message":{"role":"assistant","content":${jstr(answerJson)}}}]}"""))
        val r = engine(AppSettings(cloudProvider = CloudProvider.OPENAI, cloudApiKey = "oa")).answer(page(choiceQ("q1")))
        assertEquals(0.95f, r.single().confidence, 1e-6f)
        val req = server.takeRequest()
        assertEquals("/v1/chat/completions", req.url.encodedPath)
        assertEquals("Bearer oa", req.headers["Authorization"])
        val body = Json.parseToJsonElement(req.body!!.utf8()).jsonObject
        assertEquals("json_object", body["response_format"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals(2, body["messages"]!!.jsonArray.size)
    }

    @Test fun httpErrorGivesReadableMessage() = runTest {
        server.enqueue(MockResponse.Builder().code(401).body("""{"error":{"type":"authentication_error","message":"invalid x-api-key"}}""").build())
        try {
            engine(AppSettings(cloudApiKey = "bad")).answer(page(choiceQ("q1")))
            fail()
        } catch (e: EngineException) {
            assertTrue(e.message!!, e.message!!.contains("401") && e.message!!.contains("invalid x-api-key"))
        }
    }

    @Test fun emptyModelOutputIsError() = runTest {
        server.enqueue(json("""{"content":[]}"""))
        try {
            engine(AppSettings(cloudApiKey = "k")).answer(page(choiceQ("q1")))
            fail()
        } catch (e: EngineException) {
            assertTrue(e.message!!.contains("pusta"))
        }
    }

    @Test fun availabilityBlankKeyNoRequest() = runTest {
        assertFalse(engine(AppSettings(cloudApiKey = "  ")).isAvailable())
        assertEquals(0, server.requestCount)
    }

    @Test fun availabilityProbesModelsEndpointAndCachesSuccess() = runTest {
        server.enqueue(json("""{"data":[]}"""))
        val e = engine(AppSettings(cloudProvider = CloudProvider.ANTHROPIC, cloudApiKey = "k"))
        assertTrue(e.isAvailable())
        assertTrue(e.isAvailable()) // z cache
        assertEquals(1, server.requestCount)
        val req = server.takeRequest()
        assertEquals("/v1/models", req.url.encodedPath)
        assertEquals("k", req.headers["x-api-key"])
    }

    @Test fun availabilityFalseOnBadKeyTrueOnRateLimit() = runTest {
        server.enqueue(MockResponse.Builder().code(401).body("{}").build())
        assertFalse(engine(AppSettings(cloudProvider = CloudProvider.OPENAI, cloudApiKey = "bad")).isAvailable())
        server.enqueue(MockResponse.Builder().code(429).body("{}").build())
        assertTrue(engine(AppSettings(cloudProvider = CloudProvider.GEMINI, cloudApiKey = "k")).isAvailable())
        assertEquals("/v1/models", server.takeRequest().url.encodedPath)
        assertTrue(server.takeRequest().url.encodedPath.startsWith("/v1beta/models"))
    }

    @Test fun availabilityFalseWhenServerDown() = runTest {
        val e = engine(AppSettings(cloudApiKey = "k"))
        server.close()
        assertFalse(e.isAvailable())
    }

    @Test fun `#10 whitespace and newlines around key are trimmed`() = runTest {
        server.enqueue(json("""{"content":[{"type":"text","text":${jstr(answerJson)}}]}"""))
        engine(AppSettings(cloudApiKey = "  sk-ant\n ", cloudModel = " claude-haiku-5-5\n")).answer(page(choiceQ("q1")))
        val req = server.takeRequest()
        assertEquals("sk-ant", req.headers["x-api-key"])
    }

    @Test fun `#10 key with illegal characters gives EngineException not crash`() = runTest {
        try {
            engine(AppSettings(cloudApiKey = "sk\u0001ant")).answer(page(choiceQ("q1")))
            fail()
        } catch (e: EngineException) {
            assertTrue(e.message!!.contains("konfiguracja"))
        }
    }

    @Test fun defaultModelsAreCurrent() {
        assertEquals("claude-haiku-5-5", CloudEngine.defaultModel(CloudProvider.ANTHROPIC))
        assertEquals("gemini-3.8-flash", CloudEngine.defaultModel(CloudProvider.GEMINI))
        assertEquals("gpt-6-luna", CloudEngine.defaultModel(CloudProvider.OPENAI))
    }

    @Test fun missingKeyThrows() = runTest {
        try { engine(AppSettings()).answer(page(choiceQ("q1"))); fail() } catch (e: EngineException) { assertTrue(e.message!!.contains("klucza")) }
    }

    // --- krzyżówki: zdjęcie ---
    private val jpeg = byteArrayOf(1, 2, 3, 4)
    private val jpegB64 = java.util.Base64.getEncoder().encodeToString(jpeg)
    private val cwAnswer = """{"answers":[{"id":"Q1","text":"kotek","confidence":0.9}]}"""

    @Test fun anthropicSendsImageBlock() = runTest {
        server.enqueue(json("""{"content":[{"type":"text","text":${jstr(cwAnswer)}}]}"""))
        val r = engine(AppSettings(cloudProvider = CloudProvider.ANTHROPIC, cloudApiKey = "k")).answer(Cw.page(jpeg = jpeg).let { it.copy(questions = it.questions.take(1)) })
        assertEquals("kotek", r.single().text)
        val content = Json.parseToJsonElement(server.takeRequest().body!!.utf8()).jsonObject["messages"]!!.jsonArray[0].jsonObject["content"]!!.jsonArray
        val img = content[0].jsonObject
        assertEquals("image", img["type"]!!.jsonPrimitive.content)
        assertEquals("image/jpeg", img["source"]!!.jsonObject["media_type"]!!.jsonPrimitive.content)
        assertEquals(jpegB64, img["source"]!!.jsonObject["data"]!!.jsonPrimitive.content)
        assertEquals("text", content[1].jsonObject["type"]!!.jsonPrimitive.content)
    }

    @Test fun geminiSendsInlineData() = runTest {
        server.enqueue(json("""{"candidates":[{"content":{"parts":[{"text":${jstr(cwAnswer)}}]}}]}"""))
        engine(AppSettings(cloudProvider = CloudProvider.GEMINI, cloudApiKey = "k")).answer(Cw.page(jpeg = jpeg).let { it.copy(questions = it.questions.take(1)) })
        val parts = Json.parseToJsonElement(server.takeRequest().body!!.utf8()).jsonObject["contents"]!!.jsonArray[0].jsonObject["parts"]!!.jsonArray
        val inline = parts[0].jsonObject["inline_data"]!!.jsonObject
        assertEquals("image/jpeg", inline["mime_type"]!!.jsonPrimitive.content)
        assertEquals(jpegB64, inline["data"]!!.jsonPrimitive.content)
        assertTrue(parts[1].jsonObject.containsKey("text"))
    }

    @Test fun openaiSendsImageUrlDataUri() = runTest {
        server.enqueue(json("""{"choices":[{"message":{"content":${jstr(cwAnswer)}}}]}"""))
        engine(AppSettings(cloudProvider = CloudProvider.OPENAI, cloudApiKey = "k")).answer(Cw.page(jpeg = jpeg).let { it.copy(questions = it.questions.take(1)) })
        val content = Json.parseToJsonElement(server.takeRequest().body!!.utf8()).jsonObject["messages"]!!.jsonArray[1].jsonObject["content"]!!.jsonArray
        assertEquals("text", content[0].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("data:image/jpeg;base64,$jpegB64", content[1].jsonObject["image_url"]!!.jsonObject["url"]!!.jsonPrimitive.content)
    }

    @Test fun noImageMeansPlainStringContent() = runTest {
        server.enqueue(json("""{"content":[{"type":"text","text":${jstr(cwAnswer)}}]}"""))
        engine(AppSettings(cloudApiKey = "k")).answer(Cw.page().let { it.copy(questions = it.questions.take(1)) })
        val c = Json.parseToJsonElement(server.takeRequest().body!!.utf8()).jsonObject["messages"]!!.jsonArray[0].jsonObject["content"]!!
        assertTrue(c is kotlinx.serialization.json.JsonPrimitive)
    }
}
