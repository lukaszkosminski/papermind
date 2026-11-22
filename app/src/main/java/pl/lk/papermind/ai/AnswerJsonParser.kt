package pl.lk.papermind.ai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import pl.lk.papermind.model.Answer
import pl.lk.papermind.model.DetectedQuestion
import pl.lk.papermind.model.EngineId
import pl.lk.papermind.model.QuestionType

/**
 * Tolerancyjny parser odpowiedzi modelu: znosi bloki markdown, tekst przed/po JSON,
 * ucięty (niezamknięty) JSON, nieznane id, pewność poza zakresem.
 */
object AnswerJsonParser {
    private val json = Json { isLenient = true; ignoreUnknownKeys = true }
    private val fence = Regex("```[a-zA-Z]*\\s*\\n?(.*?)```", RegexOption.DOT_MATCHES_ALL)

    private val aliasRegex = Regex("^q(?:uestion)?[\\s_.#-]*(\\d+)$", RegexOption.IGNORE_CASE)

    /**
     * Dopasowanie wpisu do pytania (kolejne kroki): prawdziwe id → alias `Q<n>` z promptu → numer pytania →
     * gołe `n` jako indeks → kolejność (gdy liczba wpisów = liczbie pytań).
     */
    fun parse(raw: String, questions: List<DetectedQuestion>, engine: EngineId): List<Answer> {
        val byId = questions.associateBy { it.id.trim().lowercase() }
        val byNumber = HashMap<String, DetectedQuestion>().also { m ->
            questions.forEach { q -> q.number?.let(::numberKey)?.takeIf { it.isNotEmpty() }?.let { m.putIfAbsent(it, q) } }
        }
        val entries = extractEntries(raw)
        val rawIds = entries.map { e ->
            (e.string("id") ?: e.string("questionId") ?: e.string("question_id") ?: e.string("number"))?.trim()
        }
        val direct = rawIds.map { resolve(it, questions, byId, byNumber) }
        // kolejność tylko gdy żaden wpis nie trafił po id/aliasie/numerze (inaczej ryzyko przypisania do złego pytania)
        val byOrder = entries.size == questions.size && direct.all { it == null }
        val seen = HashSet<String>()
        val out = ArrayList<Answer>()
        entries.forEachIndexed { idx, e ->
            val q = (if (byOrder) questions[idx] else direct[idx]) ?: return@forEachIndexed
            if (!seen.add(q.id)) return@forEachIndexed
            out += toAnswer(e, q, engine)
        }
        return out
    }

    private fun numberKey(s: String): String = s.trim().lowercase().filter { it.isLetterOrDigit() }

    private fun resolve(
        rawId: String?,
        questions: List<DetectedQuestion>,
        byId: Map<String, DetectedQuestion>,
        byNumber: Map<String, DetectedQuestion>,
    ): DetectedQuestion? {
        if (!rawId.isNullOrEmpty()) {
            byId[rawId.lowercase()]?.let { return it }
            aliasRegex.find(rawId)?.groupValues?.get(1)?.toIntOrNull()?.let { n ->
                questions.getOrNull(n - 1)?.let { return it }
            }
            byNumber[numberKey(rawId)]?.let { return it }
            rawId.toIntOrNull()?.let { n -> questions.getOrNull(n - 1)?.let { return it } }
        }
        return null
    }

    private fun toAnswer(e: JsonObject, q: DetectedQuestion, engine: EngineId): Answer {
        var text = e.string("text") ?: e.string("answer") ?: e.string("word") ?: ""
        val labels = normalizeLabels(e["options"] ?: e["answer_options"], q)
        val choice = q.type == QuestionType.SINGLE_CHOICE || q.type == QuestionType.MULTI_CHOICE
        val finalLabels = if (q.type == QuestionType.SINGLE_CHOICE) labels.take(1) else labels
        // Model bez opcji, ale z tekstem równym treści opcji / literą — spróbuj dopasować.
        val resolved = if (choice && finalLabels.isEmpty() && text.isNotBlank()) matchByText(text, q) else finalLabels
        if (!choice) text = text.trim()
        return Answer(
            questionId = q.id,
            correctOptionLabels = if (choice) resolved else emptyList(),
            text = text.trim(),
            explanation = e.string("explanation")?.trim()?.takeIf { it.isNotEmpty() },
            confidence = confidence(e["confidence"]),
            engine = engine,
        )
    }

    private fun normalizeLabels(el: JsonElement?, q: DetectedQuestion): List<String> {
        val items: List<String> = when (el) {
            null -> emptyList()
            is JsonArray -> el.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            is JsonPrimitive -> el.contentOrNull?.split(',', ';', '/', ' ')?.filter { it.isNotBlank() } ?: emptyList()
            else -> emptyList()
        }
        val known = q.options.associateBy { it.label.trim().trimEnd('.', ')').uppercase() }
        val result = LinkedHashSet<String>()
        for (item in items) {
            val key = item.trim().trimEnd('.', ')').uppercase()
            val opt = known[key]
                ?: if (known.isEmpty()) null else q.options.firstOrNull { it.text.trim().equals(item.trim(), ignoreCase = true) }
            when {
                opt != null -> result += opt.label
                known.isEmpty() && key.isNotEmpty() -> result += key
            }
        }
        return result.toList()
    }

    private fun matchByText(text: String, q: DetectedQuestion): List<String> {
        val t = text.trim()
        val byText = q.options.firstOrNull { it.text.trim().equals(t, ignoreCase = true) }
        if (byText != null) return listOf(byText.label)
        val lead = Regex("^([A-Za-z])[.)]").find(t)?.groupValues?.get(1)?.uppercase()
        return q.options.firstOrNull { it.label.uppercase() == lead }?.let { listOf(it.label) } ?: emptyList()
    }

    private fun confidence(el: JsonElement?): Float {
        val v = (el as? JsonPrimitive)?.contentOrNull?.trim()?.removeSuffix("%")?.toFloatOrNull() ?: return 0.5f
        val raw = if (v > 1f && v <= 100f && (el as JsonPrimitive).contentOrNull?.contains('%') == true) v / 100f else v
        return if (raw.isNaN()) 0.5f else raw.coerceIn(0f, 1f)
    }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    // --- ekstrakcja JSON ---

    private fun extractEntries(raw: String): List<JsonObject> {
        val candidates = buildList {
            fence.findAll(raw).forEach { add(it.groupValues[1]) }
            add(raw)
        }
        for (c in candidates) {
            val r = entriesFromText(c)
            if (r.isNotEmpty()) return r
        }
        return emptyList()
    }

    private fun entriesFromText(text: String): List<JsonObject> {
        var i = nextStart(text, 0)
        val loose = ArrayList<JsonObject>()
        while (i >= 0) {
            val end = matchingEnd(text, i)
            val el = end?.let { runCatching { json.parseToJsonElement(text.substring(i, it + 1)) }.getOrNull() }
            val answers = el?.let(::answersOf)
            if (answers != null) return answers
            if (el is JsonObject && el.containsKey("id")) {
                loose += el
                i = nextStart(text, end!! + 1)
            } else {
                i = nextStart(text, i + 1)
            }
        }
        return loose
    }

    private fun nextStart(text: String, from: Int): Int =
        if (from >= text.length) -1 else text.indexOfAny(charArrayOf('{', '['), from)

    private fun answersOf(el: JsonElement): List<JsonObject>? = when (el) {
        is JsonObject -> (el["answers"] as? JsonArray)?.filterIsInstance<JsonObject>()
        is JsonArray -> el.filterIsInstance<JsonObject>().takeIf { l -> l.isNotEmpty() && l.all { it.containsKey("id") } }
        else -> null
    }

    /** Indeks domykającego nawiasu dla otwarcia w [start] (świadomy stringów), albo null. */
    private fun matchingEnd(s: String, start: Int): Int? {
        var depth = 0
        var inStr = false
        var esc = false
        for (k in start until s.length) {
            val c = s[k]
            if (inStr) {
                when {
                    esc -> esc = false
                    c == '\\' -> esc = true
                    c == '"' -> inStr = false
                }
                continue
            }
            when (c) {
                '"' -> inStr = true
                '{', '[' -> depth++
                '}', ']' -> { depth--; if (depth == 0) return k }
            }
        }
        return null
    }
}
