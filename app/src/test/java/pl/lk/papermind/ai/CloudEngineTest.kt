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
        assertTrue(body["messages"]!!.jsonArray[0].jsonObject["content"]!!.jsonPrimitive.content.contains("id=q1"))
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

    @Test fun availabilityRequiresKey() = runTest {
        assertFalse(engine(AppSettings(cloudApiKey = "")).isAvailable())
        assertTrue(engine(AppSettings(cloudApiKey = "k")).isAvailable())
        assertEquals(0, server.requestCount)
    }

    @Test fun missingKeyThrows() = runTest {
        try { engine(AppSettings()).answer(page(choiceQ("q1"))); fail() } catch (e: EngineException) { assertTrue(e.message!!.contains("klucza")) }
    }
}
