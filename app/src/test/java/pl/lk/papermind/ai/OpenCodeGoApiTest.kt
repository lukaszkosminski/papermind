package pl.lk.papermind.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import okhttp3.Request

class OpenCodeGoApiTest {
    @Test fun parseModelsSkipsFreeAndNonChatAndDetectsVision() {
        val body = """{"data":[
          {"id":"kimi-k3","name":"Kimi K3","type":"chat","modalities":{"input":["text","image/png"]}},
          {"id":"beam-free","name":"Beam Free","type":"chat"},
          {"id":"embed-1","name":"Embed","type":"embedding"},
          {"id":"glm-5.3","name":"GLM 5.3","type":"chat","modalities":{"input":["text"]}}]}"""
        val m = OpenCodeGoApi.parseModels(body)
        assertEquals(listOf("glm-5.3", "kimi-k3"), m.map { it.id })
        assertTrue(m.first { it.id == "kimi-k3" }.vision)
        assertFalse(m.first { it.id == "glm-5.3" }.vision)
    }

    @Test fun headersCarrySessionAndOwnUserAgent() {
        val r = OpenCodeGoApi.headers(Request.Builder().url("https://x.test/"), "oc_sk_1").get().build()
        assertEquals("Bearer oc_sk_1", r.header("Authorization"))
        assertTrue(r.header("x-opencode-session")!!.startsWith("ses_"))
        assertTrue(r.header("User-Agent")!!.startsWith("papermind/"))
    }
}
