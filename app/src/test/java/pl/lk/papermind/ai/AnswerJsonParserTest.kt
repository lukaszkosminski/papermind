package pl.lk.papermind.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import pl.lk.papermind.model.EngineId
import pl.lk.papermind.model.QuestionType
import org.junit.Test

class AnswerJsonParserTest {
    private val qs = listOf(choiceQ("q1"), openQ("q2"), choiceQ("q3", QuestionType.MULTI_CHOICE))
    private fun parse(raw: String) = AnswerJsonParser.parse(raw, qs, EngineId.CLOUD)

    @Test fun strictJson() {
        val r = parse("""{"answers":[{"id":"q1","options":["B"],"text":"Mitochondrium","explanation":"Elektrownia komórki.","confidence":0.9},
            {"id":"q2","options":[],"text":"Warszawa","explanation":"","confidence":1}]}""")
        assertEquals(2, r.size)
        assertEquals(listOf("B"), r[0].correctOptionLabels)
        assertEquals("Elektrownia komórki.", r[0].explanation)
        assertEquals(0.9f, r[0].confidence, 1e-6f)
        assertEquals("Warszawa", r[1].text)
        assertEquals(null, r[1].explanation)
        assertEquals(EngineId.CLOUD, r[1].engine)
    }

    @Test fun markdownFenceAndNoise() {
        val r = parse("Oto odpowiedź:\n```json\n{\"answers\":[{\"id\":\"q2\",\"text\":\"Warszawa\",\"confidence\":0.8}]}\n```\nMam nadzieję, że pomogłem {x}")
        assertEquals(1, r.size)
        assertEquals("Warszawa", r[0].text)
    }

    @Test fun unknownIdsIgnoredAndDuplicatesDropped() {
        val r = parse("""{"answers":[{"id":"zzz","text":"x"},{"id":"q2","text":"A"},{"id":"q2","text":"B"}]}""")
        assertEquals(listOf("A"), r.map { it.text })
    }

    @Test fun confidenceClamped() {
        val r = parse("""{"answers":[{"id":"q1","options":["A"],"confidence":7},{"id":"q2","text":"x","confidence":-3},{"id":"q3","options":["A"],"confidence":"85%"}]}""")
        assertEquals(1f, r[0].confidence, 0f)
        assertEquals(0f, r[1].confidence, 0f)
        assertEquals(0.85f, r[2].confidence, 1e-6f)
    }

    @Test fun missingConfidenceDefaults() {
        assertEquals(0.5f, parse("""{"answers":[{"id":"q2","text":"x"}]}""")[0].confidence, 0f)
    }

    @Test fun singleChoiceKeepsOneLabel_multiKeepsAll_unknownLabelsDropped() {
        val r = parse("""{"answers":[{"id":"q1","options":["A","C"]},{"id":"q3","options":["a","C","Z"]}]}""")
        assertEquals(listOf("A"), r[0].correctOptionLabels)
        assertEquals(listOf("A", "C"), r[1].correctOptionLabels)
    }

    @Test fun optionsAsStringAndLabelWithParen() {
        val r = parse("""{"answers":[{"id":"q3","options":"A, C"},{"id":"q1","options":["b)"]}]}""")
        assertEquals(listOf("A", "C"), r[0].correctOptionLabels)
        assertEquals(listOf("B"), r[1].correctOptionLabels)
    }

    @Test fun choiceResolvedFromTextWhenOptionsMissing() {
        val r = parse("""{"answers":[{"id":"q1","text":"Mitochondrium"}]}""")
        assertEquals(listOf("B"), r[0].correctOptionLabels)
    }

    @Test fun truncatedJsonKeepsCompleteEntries() {
        val r = parse("""{"answers":[{"id":"q2","text":"Warszawa","confidence":0.9},{"id":"q1","options":["B"],"expl""")
        assertEquals(listOf("q2"), r.map { it.questionId })
    }

    @Test fun bareArrayAndCaseInsensitiveId() {
        val r = parse("""[{"id":"Q2","text":"Warszawa"}]""")
        assertEquals("q2", r[0].questionId)
    }

    @Test fun garbageYieldsEmpty() {
        assertTrue(parse("przepraszam, nie umiem").isEmpty())
        assertTrue(parse("").isEmpty())
        assertTrue(parse("{\"foo\":1}").isEmpty())
    }

    @Test fun nonChoiceHasNoLabels() {
        val r = parse("""{"answers":[{"id":"q2","options":["A"],"text":"x"}]}""")
        assertTrue(r[0].correctOptionLabels.isEmpty())
    }
}
