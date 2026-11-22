package pl.lk.papermind.ocr

import pl.lk.papermind.model.Box
import pl.lk.papermind.model.CrosswordClue
import pl.lk.papermind.model.DetectedQuestion
import pl.lk.papermind.model.Direction
import pl.lk.papermind.model.QuestionType

/**
 * Parser listy haseł krzyżówki (czysta logika JVM, bez Androida).
 *
 * Rozpoznaje nagłówki `Poziomo` / `Pionowo` / `Across` / `Down` (z dwukropkiem, kropką, bez nich,
 * także wersalikami) oraz hasła w formatach: `1. treść (5)`, `1) treść`, `12 treść [7]`,
 * `3. treść 5 liter`, kilka haseł w jednej linii. Zawinięte hasło (kolejna linia zaczynająca się
 * małą literą, bez numeru) jest doklejane do poprzedniego.
 *
 * Linie spoza listy haseł wracają w [Extracted.leftover] do zwykłej segmentacji pytań.
 */
internal object CrosswordClueParser {

    class Extracted(val clues: List<DetectedQuestion>, val leftover: List<LogicalLine>)

    private val HEADER = Regex(
        """^\s*(poziomo|pionowo|across|down)\b\s*(?:\(\s*\d{1,2}\s*\))?[\s:.\-–—]*""",
        RegexOption.IGNORE_CASE,
    )

    private val CROSSWORD_WORD = Regex("""\b(poziomo|pionowo|across|down)\b""", RegexOption.IGNORE_CASE)

    /** Numer hasła w środku linii: "… 2. Pies", "… 3) Kot" (wymaga separatora i litery po nim). */
    private val MID_NUMBER = Regex("""(?:^|(?<=\s))(\d{1,3})\s*[.)]\s*(?=[\p{L}"„'(])""")

    /** Numer hasła na początku linii bez separatora: "12 treść [7]". */
    private val START_NUMBER_NO_SEP = Regex("""^(\d{1,3})\s+(?=[\p{L}"„'(])""")

    /** Długość hasła: "(5)", "[7]", "(5 liter)", "5 liter". */
    private val LENGTH = Regex(
        """\(\s*(\d{1,2})\s*(?:liter\w*|letters?|ltr\.?)?\s*\)""" +
            """|\[\s*(\d{1,2})\s*\]""" +
            """|(?:^|[\s,;–—-])(\d{1,2})\s*(?:liter\w*|letters?)\b""",
        RegexOption.IGNORE_CASE,
    )

    /** Szybki test dla stabilizatora: czy tekst wygląda na listę haseł krzyżówki. */
    fun looksLikeCrossword(text: String): Boolean = CROSSWORD_WORD.containsMatchIn(text)

    fun extract(lines: List<LogicalLine>, medianHeight: Float): Extracted {
        val leftover = ArrayList<LogicalLine>()
        val drafts = ArrayList<Draft>()
        var direction: Direction? = null
        var last: Draft? = null
        var prev: LogicalLine? = null

        for (line in lines) {
            val raw = line.text.trim()
            if (raw.isEmpty()) {
                prev = line
                continue
            }
            val gap = if (prev == null) Float.MAX_VALUE else line.box.top - prev.box.bottom

            val header = HEADER.find(raw)
            if (header != null && isRealHeader(raw, header.range.last + 1)) {
                direction = directionOf(header.groupValues[1])
                last = null
                val restStart = header.range.last + 1
                val rest = raw.substring(restStart).trim()
                if (rest.isNotEmpty()) {
                    val parsed = parseClues(raw, line, restStart, direction)
                    if (parsed.isNotEmpty()) {
                        drafts += parsed
                        last = parsed.last()
                    }
                }
                prev = line
                continue
            }

            val dir = direction
            if (dir == null) {
                leftover += line
                prev = line
                continue
            }

            val parsed = parseClues(raw, line, 0, dir)
            if (parsed.isNotEmpty()) {
                drafts += parsed
                last = parsed.last()
                prev = line
                continue
            }

            // Zawinięte hasło: kolejna linia bez numeru, blisko poprzedniego hasła.
            val continuation = last
            if (continuation != null && gap <= 1.3f * medianHeight &&
                raw.first().isLowerCase() && raw.length <= 80
            ) {
                val (text, length) = stripLength(raw)
                if (text.isNotBlank()) {
                    continuation.text = (continuation.text + " " + text).trim()
                    continuation.box = continuation.box.union(line.box)
                    if (length != null) continuation.length = length
                    prev = line
                    continue
                }
            }

            // Koniec listy haseł — linia wraca do zwykłego parsera pytań.
            direction = null
            last = null
            leftover += line
            prev = line
        }

        return Extracted(finalize(drafts), leftover)
    }

    // ------------------------------------------------------------------ wnętrze

    private fun isRealHeader(raw: String, restStart: Int): Boolean {
        val rest = raw.substring(restStart.coerceAtMost(raw.length)).trim()
        // "Poziomo i pionowo…" to nie nagłówek listy — tylko nagłówek lub nagłówek + pierwsze hasło.
        return rest.isEmpty() || rest.first().isDigit()
    }

    private fun directionOf(word: String): Direction = when (word.lowercase()) {
        "pionowo", "down" -> Direction.DOWN
        else -> Direction.ACROSS
    }

    private class Draft(
        val number: Int,
        val direction: Direction,
        var text: String,
        var box: Box,
        var length: Int?,
    ) {
        fun toQuestion(): DetectedQuestion {
            val clean = TextNormalizer.normalizeSpace(text)
            return DetectedQuestion(
                id = QuestionId.forClue(direction, number, clean),
                number = number.toString(),
                type = QuestionType.CROSSWORD_CLUE,
                text = clean,
                options = emptyList(),
                box = box,
                clue = CrosswordClue(number = number, direction = direction, length = length),
            )
        }
    }

    private fun finalize(drafts: List<Draft>): List<DetectedQuestion> {
        val out = ArrayList<DetectedQuestion>(drafts.size)
        val seen = HashMap<String, Int>()
        for (d in drafts) {
            val q = d.toQuestion()
            val count = seen.getOrDefault(q.id, 0)
            seen[q.id] = count + 1
            if (count == 0) {
                out += q
            } else {
                // identyczne hasło powtórzone na stronie — sól, żeby nie zlało się w cache
                out += q.copy(id = QuestionId.forClue(d.direction, d.number, d.text, salt = count))
            }
        }
        return out
    }

    private fun parseClues(lineText: String, line: LogicalLine, from: Int, direction: Direction): List<Draft> {
        if (from >= lineText.length) return emptyList()
        val segment = lineText.substring(from)
        val starts = clueStarts(segment)
        if (starts.isEmpty()) return emptyList()

        val drafts = ArrayList<Draft>(starts.size)
        for (i in starts.indices) {
            val start = starts[i]
            val end = if (i + 1 < starts.size) starts[i + 1] else segment.length
            val piece = segment.substring(start, end).trim()
            val numberLen = piece.takeWhile { it.isDigit() }.length
            if (numberLen == 0) continue
            val number = piece.substring(0, numberLen).toIntOrNull() ?: continue
            if (number !in 1..199) continue
            val rest = piece.substring(numberLen).trimStart(' ', '.', ')', ':', '-', '–', '—')
            val (text, length) = stripLength(rest)
            val clueText = text.trim(' ', ',', ';', ':', '-', '–', '—', '"', '„', '”', '\'')
            if (clueText.isEmpty()) continue
            drafts += Draft(
                number = number,
                direction = direction,
                text = clueText,
                box = line.boxOf(from + start, from + end),
                length = length,
            )
        }
        return drafts
    }

    /** Pozycje początków haseł w [segment] (indeksy liczb). */
    private fun clueStarts(segment: String): List<Int> {
        val starts = sortedSetOf<Int>()
        val mid = MID_NUMBER.findAll(segment).map { it.range.first }.toList()
        val startNoSep = START_NUMBER_NO_SEP.find(segment)
        if (startNoSep != null && (mid.isEmpty() || mid.first() > 0)) starts += 0
        starts += mid
        return starts.toList()
    }

    /** Wycina znacznik długości (ostatni sensowny) i zwraca tekst bez niego. */
    private fun stripLength(text: String): Pair<String, Int?> {
        var value: Int? = null
        var from = -1
        var to = -1
        for (m in LENGTH.findAll(text)) {
            val raw = m.groupValues[1].ifEmpty { m.groupValues[2] }.ifEmpty { m.groupValues[3] }
            val n = raw.toIntOrNull() ?: continue
            if (n !in 2..30) continue
            value = n
            from = m.range.first
            to = m.range.last + 1
        }
        if (value == null) return text to null
        val cleaned = (text.substring(0, from) + " " + text.substring(to))
            .trim()
            .trim(' ', ',', ';', '-', '–', '—', ':')
        return cleaned to value
    }
}
