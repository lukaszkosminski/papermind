package pl.lk.papermind.ai

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.Credentials
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import pl.lk.papermind.model.EngineId
import pl.lk.papermind.settings.AppSettings

class OpenCodeEngineTest {
    private fun jstr(s: String) = kotlinx.serialization.json.JsonPrimitive(s).toString()

    private lateinit var server: MockWebServer
    private val answerJson = """{"answers":[{"id":"q1","options":["B"],"text":"Mitochondrium","confidence":0.9}]}"""
    private val seen = ArrayList<String>()
    private val bodies = HashMap<String, String>()
    private val headers = ArrayList<String?>()

    @Before fun setUp() { server = MockWebServer(); }
    @After fun tearDown() { server.close() }

    private fun ok(body: String, type: String = "application/json") =
        MockResponse.Builder().code(200).addHeader("Content-Type", type).body(body).build()

    private fun start(handler: (RecordedRequest, String) -> MockResponse) {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val key = "${request.method} ${request.url.encodedPath}"
                seen += key
                headers += request.headers["Authorization"]
                bodies[key] = request.body?.utf8().orEmpty()
                return handler(request, key)
            }
        }
        server.start()
    }

    private fun engine(s: AppSettings = AppSettings()): OpenCodeEngine =
        OpenCodeEngine(MutableStateFlow(s.copy(openCodeUrl = server.url("/").toString().trimEnd('/'))))

    private fun v2Handler(r: RecordedRequest, key: String): MockResponse = when {
        key == "GET /api/info" -> ok("""{"version":"2.3.2"}""")
        key == "POST /api/session" -> ok("""{"data":{"id":"ses_1"}}""")
        key == "POST /api/session/ses_1/prompt" -> ok("""{"data":{"id":"msg_1"}}""")
        key == "POST /api/experimental/session/ses_1/wait" -> MockResponse.Builder().code(204).build()
        key == "GET /api/session/ses_1/message" -> ok(
            """{"data":[{"type":"idle"},{"type":"assistant","content":[{"type":"reasoning","text":"hmm"},{"type":"text","text":${jstr(answerJson)}}]},{"type":"user","text":"x"}]}""",
        )
        key.startsWith("DELETE") -> MockResponse.Builder().code(204).build()
        else -> MockResponse.Builder().code(404).build()
    }

    private fun legacyHandler(r: RecordedRequest, key: String): MockResponse = when {
        key == "GET /api/info" -> ok("<html>spa</html>", "text/html")
        key == "GET /global/health" -> ok("""{"healthy":true,"version":"1.4.0"}""")
        key == "POST /session" -> ok("""{"id":"ses_L"}""")
        key == "POST /session/ses_L/message" -> ok(
            """{"info":{"id":"m"},"parts":[{"type":"step-start"},{"type":"text","text":${jstr("```json\n$answerJson\n```")}}]}""",
        )
        else -> MockResponse.Builder().code(404).build()
    }

    @Test fun v2FlowAndSessionReuse() = runTest {
        start(::v2Handler)
        val e = engine(AppSettings(openCodeModel = "opencode/big-pickle"))
        assertTrue(e.isAvailable())
        val r1 = e.answer(page(choiceQ("q1")))
        val r2 = e.answer(page(choiceQ("q1")))
        assertEquals(listOf("B"), r1.single().correctOptionLabels)
        assertEquals(EngineId.OPENCODE, r2.single().engine)
        assertEquals(1, seen.count { it == "POST /api/session" })
        assertEquals(2, seen.count { it == "POST /api/session/ses_1/prompt" })
        val create = Json.parseToJsonElement(bodies["POST /api/session"]!!).jsonObject
        assertEquals("big-pickle", create["model"]!!.jsonObject["id"]!!.jsonPrimitive.content)
        assertEquals("opencode", create["model"]!!.jsonObject["providerID"]!!.jsonPrimitive.content)
        val prompt = Json.parseToJsonElement(bodies["POST /api/session/ses_1/prompt"]!!).jsonObject["text"]!!.jsonPrimitive.content
        assertTrue(prompt.contains("id=Q1") && prompt.contains("STRICT JSON"))
    }

    @Test fun legacyFlowWithModelAndFencedJson() = runTest {
        start(::legacyHandler)
        val e = engine(AppSettings(openCodeModel = "anthropic/claude-sonnet-5-5"))
        assertTrue(e.isAvailable())
        val r = e.answer(page(choiceQ("q1")))
        assertEquals(listOf("B"), r.single().correctOptionLabels)
        val body = Json.parseToJsonElement(bodies["POST /session/ses_L/message"]!!).jsonObject
        assertEquals("text", body["parts"]!!.jsonArray[0].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("anthropic", body["model"]!!.jsonObject["providerID"]!!.jsonPrimitive.content)
        assertEquals("claude-sonnet-5-5", body["model"]!!.jsonObject["modelID"]!!.jsonPrimitive.content)
    }

    @Test fun basicAuthSentWhenPasswordSet() = runTest {
        start(::legacyHandler)
        val e = engine(AppSettings(openCodeUser = "opencode", openCodePassword = "pw"))
        e.answer(page(choiceQ("q1")))
        assertTrue(headers.isNotEmpty())
        assertTrue(headers.all { it == Credentials.basic("opencode", "pw") })
    }

    @Test fun noAuthHeaderWithoutPassword() = runTest {
        start(::legacyHandler)
        engine().answer(page(choiceQ("q1")))
        assertTrue(headers.all { it == null })
    }

    @Test fun unavailableWhenServerDown() = runTest {
        server.start()
        val url = server.url("/").toString().trimEnd('/')
        server.close()
        assertFalse(OpenCodeEngine(MutableStateFlow(AppSettings(openCodeUrl = url))).isAvailable())
    }

    @Test fun unavailableWhenWrongServer() = runTest {
        start { _, _ -> ok("<html></html>", "text/html") }
        assertFalse(engine().isAvailable())
    }

    @Test fun unauthorizedGivesReadableError() = runTest {
        start { _, key -> if (key == "GET /api/info") ok("""{"version":"2"}""") else MockResponse.Builder().code(401).build() }
        try { engine().answer(page(choiceQ("q1"))); fail() } catch (e: EngineException) {
            assertTrue(e.message!!, e.message!!.contains("401") && e.message!!.contains("hasło"))
        }
    }

    @Test fun failedRequestResetsSession() = runTest {
        var fail = true
        start { r, key ->
            if (key == "POST /session/ses_L/message" && fail) MockResponse.Builder().code(500).build() else legacyHandler(r, key)
        }
        val e = engine()
        try { e.answer(page(choiceQ("q1"))); fail() } catch (_: EngineException) {}
        fail = false
        assertEquals(listOf("B"), e.answer(page(choiceQ("q1"))).single().correctOptionLabels)
        assertEquals(2, seen.count { it == "POST /session" })
    }

    @Test fun `#10 url user and password are trimmed`() = runTest {
        start(::legacyHandler)
        engine(AppSettings(openCodeUser = " opencode ", openCodePassword = " pw\n")).answer(page(choiceQ("q1")))
        assertTrue(headers.all { it == Credentials.basic("opencode", "pw") })
    }

    @Test fun `#10 invalid url gives EngineException not crash`() = runTest {
        val e = OpenCodeEngine(MutableStateFlow(AppSettings(openCodeUrl = "nie url ://")))
        try { e.answer(page(choiceQ("q1"))); fail() } catch (ex: EngineException) { assertTrue(ex.message!!.startsWith("OpenCode")) }
        assertFalse(e.isAvailable())
    }

    @Test fun statusReportsV2() = runTest {
        start(::v2Handler)
        val st = engine().status()
        val detail = st.detail!!
        assertTrue(detail, st.available)
        assertTrue(detail.contains("v2"))
    }

    @Test fun statusReportsLegacy() = runTest {
        start(::legacyHandler)
        val st = engine().status()
        val detail = st.detail!!
        assertTrue(detail, st.available)
        assertTrue(detail.contains("klasyczne"))
    }

    @Test fun status401GivesPasswordHint() = runTest {
        start { _, key -> if (key == "GET /api/info") MockResponse.Builder().code(401).build() else MockResponse.Builder().code(404).build() }
        val st = engine().status()
        val detail = st.detail!!
        assertFalse(st.available)
        assertTrue(detail, detail.contains("401") && detail.contains("hasło"))
    }

    @Test fun statusBlankUrl() = runTest {
        val st = OpenCodeEngine(MutableStateFlow(AppSettings(openCodeUrl = "  "))).status()
        val detail = st.detail!!
        assertFalse(st.available)
        assertTrue(detail.contains("adresu"))
    }

    @Test fun statusUnreachableServer() = runTest {
        server.start()
        val url = server.url("/").toString().trimEnd('/')
        server.close()
        val st = OpenCodeEngine(MutableStateFlow(AppSettings(openCodeUrl = url))).status()
        val detail = st.detail!!
        assertFalse(st.available)
        assertTrue(detail, detail.contains("nie odpowiada"))
    }

    @Test fun parseModel() {
        assertEquals("a" to "b/c", OpenCodeEngine.parseModel(" a/b/c "))
        assertEquals(null, OpenCodeEngine.parseModel(""))
        assertEquals(null, OpenCodeEngine.parseModel("nomodel"))
    }

    // --- krzyżówki: zdjęcie ---
    private val jpeg = byteArrayOf(9, 8, 7)
    private val dataUri get() = "data:image/jpeg;base64," + java.util.Base64.getEncoder().encodeToString(jpeg)
    private val cwPage get() = Cw.page(jpeg = jpeg).let { it.copy(questions = it.questions.take(1)) }
    private val cwJson = """{"answers":[{"id":"Q1","text":"KOTEK","confidence":0.9}]}"""

    @Test fun v2AttachesImageAsFile() = runTest {
        start { r, key -> if (key == "GET /api/session/ses_1/message") ok("""{"data":[{"type":"assistant","content":[{"type":"text","text":${jstr(cwJson)}}]}]}""") else v2Handler(r, key) }
        val r = engine().answer(cwPage)
        assertEquals("KOTEK", r.single().text)
        val files = Json.parseToJsonElement(bodies["POST /api/session/ses_1/prompt"]!!).jsonObject["files"]!!.jsonArray
        assertEquals(dataUri, files[0].jsonObject["uri"]!!.jsonPrimitive.content)
        assertTrue(Json.parseToJsonElement(bodies["POST /api/session/ses_1/prompt"]!!).jsonObject["text"]!!.jsonPrimitive.content.contains("photo of the page is attached"))
    }

    @Test fun legacyAttachesImageAsFilePart() = runTest {
        start { r, key ->
            if (key == "POST /session/ses_L/message") ok("""{"info":{},"parts":[{"type":"text","text":${jstr(cwJson)}}]}""") else legacyHandler(r, key)
        }
        engine().answer(cwPage)
        val parts = Json.parseToJsonElement(bodies["POST /session/ses_L/message"]!!).jsonObject["parts"]!!.jsonArray
        val file = parts.map { it.jsonObject }.first { it["type"]!!.jsonPrimitive.content == "file" }
        assertEquals("image/jpeg", file["mime"]!!.jsonPrimitive.content)
        assertEquals(dataUri, file["url"]!!.jsonPrimitive.content)
    }

    @Test fun fallsBackToTextOnlyWhenModelCannotReadImage() = runTest {
        var n = 0
        start { r, key ->
            if (key == "POST /session/ses_L/message") {
                n++
                if (n == 1) ok("""{"info":{},"parts":[{"type":"text","text":"I can't view the image - this model does not support image input."}]}""")
                else ok("""{"info":{},"parts":[{"type":"text","text":${jstr(cwJson)}}]}""")
            } else legacyHandler(r, key)
        }
        val r = engine().answer(cwPage)
        assertEquals("KOTEK", r.single().text)
        assertEquals(2, n)
        assertEquals(2, seen.count { it == "POST /session" }) // druga próba w nowej sesji
        val parts = Json.parseToJsonElement(bodies["POST /session/ses_L/message"]!!).jsonObject["parts"]!!.jsonArray
        assertTrue(parts.none { it.jsonObject["type"]!!.jsonPrimitive.content == "file" })
        assertFalse(bodies["POST /session/ses_L/message"]!!.contains("photo of the page is attached"))
    }
}
