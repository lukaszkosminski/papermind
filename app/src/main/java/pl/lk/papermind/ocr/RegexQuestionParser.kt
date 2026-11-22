package pl.lk.papermind.ocr

import pl.lk.papermind.model.AnswerOption
import pl.lk.papermind.model.Box
import pl.lk.papermind.model.DetectedQuestion
import pl.lk.papermind.model.OcrFrame
import pl.lk.papermind.model.OcrLine
import pl.lk.papermind.model.QuestionType

/**
 * Parser pytań oparty na układzie linii OCR i wyrażeniach regularnych. Czysta logika JVM.
 *
 * Wejście: linie OCR z boxami w pikselach widoku. Wyjście: pytania z opcjami i stabilnym id.
 *
 * Kroki:
 * 1. [LineLayout] scala fragmenty w wiersze logiczne (kolumny opcji w jednym wierszu),
 * 2. linie-wypełniacze ("____" pod etykietą) są doklejane do etykiety,
 * 3. segmentacja na pytania: numeracja (`1.`, `1)`, `Pytanie 1`, `Zad. 1`), akapity (odstęp pionowy),
 *    a przy braku numeracji — linie wyglądające na pytanie/polecenie,
 * 4. rozpoznanie opcji (`A.`, `A)`, `a)`, `(a)`, `□`, `○`, także kilka opcji w jednej linii
 *    oraz dwie kolumny opcji; tolerancja błędów OCR (`B,` zamiast `B.`, `8)` zamiast `B)`),
 * 5. klasyfikacja typu (SINGLE/MULTI/FORM_FIELD/MATH/OPEN) i stabilne id.
 */
class RegexQuestionParser : QuestionParser {

    override fun parse(frame: OcrFrame): List<DetectedQuestion> {
        val h = LineLayout.medianHeight(frame.lines)
        val logical = mergeFillerLines(LineLayout.build(frame.lines), h)
        if (logical.isEmpty()) return emptyList()
        val builders = segment(logical, h)
        return buildUnique(builders)
    }

    // ------------------------------------------------------------------ scalanie wypełniaczy

    /** "Imię:" + "______" (osobna linia OCR) → "Imię: ______", żeby wykryć pole formularza. */
    private fun mergeFillerLines(lines: List<LogicalLine>, h: Float): List<LogicalLine> {
        if (lines.size < 2) return lines
        val out = ArrayList<LogicalLine>(lines.size)
        for (line in lines) {
            val prev = out.lastOrNull()
            if (prev != null && QuestionPatterns.isFillerOnly(line.text)) {
                val prevText = prev.text.trim()
                val closeEnough = line.box.top - prev.box.bottom <= 1.6f * h
                val looksLikeLabel = prevText.endsWith(":") ||
                    QuestionPatterns.matchLabelWithFill(prevText) != null ||
                    (prevText.count { it.isLetter() } >= 2 && prevText.split(' ').size <= 6 && !prevText.contains('?'))
                if (closeEnough && looksLikeLabel) {
                    out[out.size - 1] = prev.mergeWith(line)
                    continue
                }
            }
            out += line
        }
        return out
    }

    // ------------------------------------------------------------------ segmentacja

    private fun segment(lines: List<LogicalLine>, h: Float): List<Builder> {
        val out = ArrayList<Builder>()
        var cur: Builder? = null
        var prev: LogicalLine? = null
        var pendingHint: String? = null

        fun finish() {
            cur?.let { out += it }
            cur = null
        }

        for ((idx, line) in lines.withIndex()) {
            val text = line.text.trim()
            if (text.isEmpty()) continue
            val newPara = prev == null || line.box.top - prev.box.bottom > 1.35f * h
            val numbered = QuestionPatterns.matchQuestionStart(text)
            val stemStart = if (numbered != null) QuestionPatterns.questionStemStart(text) else 0
            val expected = cur?.nextLabel ?: 'A'
            val options = splitOptions(text, line.pieceStarts, expected)
            val optionOnly = options.isNotEmpty() && options.first().markerStart == 0

            val forms = if (numbered == null) QuestionPatterns.matchFormPairs(text) else emptyList()
            val bareField = if (numbered == null && !optionOnly) QuestionPatterns.matchBareField(text) else null

            when {
                // ---- sama linia opcji: "A. Warszawa" / "□ Warszawa"
                optionOnly -> {
                    val b = cur
                    if (b != null) {
                        b.consume(text, line, 0)
                    }
                    // osierocone opcje bez pytania (np. kadr ucięty nad opcjami) pomijamy
                }

                // ---- nowe pytanie numerowane
                numbered != null -> {
                    finish()
                    val b = Builder(numbered.number, line.box)
                    b.hint = pendingHint
                    pendingHint = null
                    b.consume(text, line, stemStart)
                    cur = b
                }

                // ---- pole formularza (bez numeracji)
                forms.isNotEmpty() || bareField != null -> {
                    val b = cur
                    if (b != null && b.number != null && b.options.isEmpty() && b.lineCount <= 2 && !newPara) {
                        // "Zad. 3. Wpisz dane:" + "Imię: ____" → jedno pytanie FORM_FIELD
                        b.formField = true
                        b.addStem(text, line.box)
                        b.lineCount++
                    } else {
                        finish()
                        if (forms.isNotEmpty()) {
                            for (p in forms) {
                                val pairBox = line.boxOf(p.start, p.end)
                                val f = Builder(null, pairBox)
                                f.formField = true
                                f.addStem(p.label + ":", pairBox)
                                f.lineCount = 1
                                out += f
                            }
                        } else if (bareField != null) {
                            val f = Builder(null, line.box)
                            f.formField = true
                            f.addStem("$bareField:", line.box)
                            f.lineCount = 1
                            out += f
                        }
                    }
                }

                // ---- brak bieżącego pytania: nagłówek / instrukcja / początek nienumerowanego pytania
                cur == null -> {
                    if (QuestionPatterns.isAnswerInstruction(text)) {
                        pendingHint = if (pendingHint == null) text else pendingHint + " " + text
                    } else if (startsUnnumberedQuestion(text, idx, lines)) {
                        val b = Builder(null, line.box)
                        b.hint = pendingHint
                        pendingHint = null
                        b.consume(text, line, 0)
                        cur = b
                    }
                    // inaczej: nagłówek/stopka strony — ignorujemy
                }

                // ---- kontynuacja bieżącego pytania
                else -> {
                    val b = cur!!
                    val qLike = QuestionPatterns.looksLikeQuestion(text)
                    val nextStartsOption = idx + 1 < lines.size &&
                        QuestionPatterns.startsWithOptionMarker(lines[idx + 1].text)
                    if (newPara && (qLike || nextStartsOption) && !QuestionPatterns.isAnswerInstruction(text)) {
                        finish()
                        val nb = Builder(null, line.box)
                        nb.hint = pendingHint
                        pendingHint = null
                        nb.consume(text, line, 0)
                        cur = nb
                    } else {
                        b.consume(text, line, 0)
                    }
                }
            }
            prev = line
        }
        finish()
        return out
    }

    private fun startsUnnumberedQuestion(text: String, idx: Int, lines: List<LogicalLine>): Boolean {
        if (QuestionPatterns.looksLikeQuestion(text)) return true
        return idx + 1 < lines.size && QuestionPatterns.startsWithOptionMarker(lines[idx + 1].text)
    }

    // ------------------------------------------------------------------ opcje

    private class Candidate(
        val start: Int,
        val end: Int,
        val letter: Char,
        val fromDigit: Boolean,
        val bullet: Boolean,
        val atPieceStart: Boolean,
    )

    /** Opcja w obrębie jednej linii logicznej. [markerStart] — początek etykiety/znacznika (dla boxa). */
    private class OptionSpan(
        val label: Char,
        val text: String,
        val markerStart: Int,
        val textEnd: Int,
    )

    /**
     * Wycina opcje z linii. [expected] — oczekiwana pierwsza etykieta (kolejna po już znalezionych opcjach).
     * [minStart] — ignoruj znaczniki przed tą pozycją (np. prefiks numeru pytania).
     */
    private fun splitOptions(
        text: String,
        pieceStarts: Set<Int>,
        expected: Char,
        minStart: Int = 0,
    ): List<OptionSpan> {
        val cands = ArrayList<Candidate>()
        for (m in QuestionPatterns.PAREN_OPTION.findAll(text)) {
            if (m.range.first < minStart) continue
            cands += Candidate(m.range.first, m.range.last + 1, m.groupValues[1][0].uppercaseChar(), false, false, m.range.first in pieceStarts)
        }
        for (m in QuestionPatterns.LETTER_OPTION.findAll(text)) {
            if (m.range.first < minStart) continue
            cands += Candidate(m.range.first, m.range.last + 1, m.groupValues[1][0].uppercaseChar(), false, false, m.range.first in pieceStarts)
        }
        for (m in QuestionPatterns.BOX_OPTION.findAll(text)) {
            if (m.range.first < minStart) continue
            cands += Candidate(m.range.first, m.range.last + 1, 'A', false, true, m.range.first in pieceStarts)
        }
        for (m in QuestionPatterns.DIGIT_OPTION.findAll(text)) {
            if (m.range.first < minStart) continue
            // cyfrowa etykieta to prawie zawsze pytanie ("4)", "8)") — dopuszczamy ją jako opcję
            // tylko wtedy, gdy jesteśmy w środku listy opcji (np. OCR czyta "8)" zamiast "B)").
            if (expected == 'A') continue
            val mapped = QuestionPatterns.digitToLetter(m.groupValues[1][0]) ?: continue
            if (mapped !in 'A'..'H') continue
            cands += Candidate(m.range.first, m.range.last + 1, mapped, true, false, m.range.first in pieceStarts)
        }
        if (cands.isEmpty()) return emptyList()
        cands.sortBy { it.start }
        // usuń nakładające się kandydatury, np. "(a)" dopasowane także jako "a)"
        val merged = ArrayList<Candidate>(cands.size)
        for (c in cands) {
            val last = merged.lastOrNull()
            if (last != null && c.start < last.end) continue
            // "□ A. Warszawa" — checkbox + etykieta to jedna opcja (etykieta z litery, box od checkboxa)
            if (last != null && last.bullet && !c.bullet && c.start <= last.end + 2) {
                merged[merged.size - 1] =
                    Candidate(last.start, c.end, c.letter, false, false, last.atPieceStart)
                continue
            }
            merged += c
        }

        val spans = ArrayList<OptionSpan>()
        var lastLabel: Char? = null
        var openStart = -1
        var openMarker = -1
        var openLabel: Char? = null

        for (c in merged) {
            val prevLabel = lastLabel
            val first = prevLabel == null
            val label = if (c.bullet) {
                when {
                    prevLabel == null -> expected
                    prevLabel >= 'H' -> 'H'
                    else -> prevLabel + 1
                }
            } else {
                c.letter
            }
            val accept = when {
                c.bullet -> prevLabel == null || prevLabel < 'H'
                c.fromDigit -> label == expected || (first && c.start == 0)
                first -> (c.start == 0 && label in 'A'..'H') ||
                    (label == expected && (c.atPieceStart || hasNextConsecutive(merged, c)))
                else -> label == prevLabel + 1 ||
                    (c.atPieceStart && label > prevLabel)
            }
            if (!accept) continue

            val open = openLabel
            if (open != null) {
                spans += spanOf(text, openMarker, openStart, c.start, open)
            }
            openLabel = label
            openMarker = c.start
            openStart = c.end
            lastLabel = label
        }
        val lastOpen = openLabel
        if (lastOpen != null) {
            spans += spanOf(text, openMarker, openStart, text.length, lastOpen)
        }
        return spans
    }

    private fun hasNextConsecutive(cands: List<Candidate>, c: Candidate): Boolean {
        val next = c.letter + 1
        for (o in cands) {
            if (o.start <= c.start) continue
            if (o.bullet || o.fromDigit) continue
            return o.letter == next
        }
        return false
    }

    private fun spanOf(text: String, markerStart: Int, from: Int, to: Int, label: Char): OptionSpan {
        var a = from.coerceAtLeast(0)
        var b = to.coerceAtMost(text.length)
        while (a < b && text[a].isWhitespace()) a++
        while (b > a && text[b - 1].isWhitespace()) b--
        // opcja mogła mieć dodatkowy znacznik po etykiecie: "A. □ Warszawa"
        while (a < b && text[a] in QuestionPatterns.BOX_MARKERS) {
            a++
            while (a < b && text[a].isWhitespace()) a++
        }
        return OptionSpan(label, text.substring(a, b), markerStart, b)
    }

    // ------------------------------------------------------------------ budowa pytań

    private class MutableOption(val label: String, var text: String, var box: Box)

    private inner class Builder(val number: String?, var box: Box) {
        private val stem = StringBuilder()
        val options = ArrayList<MutableOption>()
        var hint: String? = null
        var formField = false
        var lineCount = 0

        /** Kolejna oczekiwana etykieta opcji (A..H). */
        val nextLabel: Char
            get() {
                val last = options.mapNotNull { it.label.firstOrNull() }.maxOrNull() ?: return 'A'
                return if (last >= 'H') 'H' else last + 1
            }

        fun addStem(text: String, box: Box) {
            if (text.isBlank()) return
            if (stem.isNotEmpty()) stem.append(' ')
            stem.append(text)
            this.box = this.box.union(box)
        }

        private fun addContinuation(text: String, box: Box) {
            if (text.isBlank()) return
            if (options.isEmpty()) {
                addStem(text, box)
            } else {
                val last = options.last()
                last.text = if (last.text.isEmpty()) text else last.text + " " + text
                last.box = last.box.union(box)
                this.box = this.box.union(box)
            }
        }

        fun addOption(label: Char, text: String, box: Box) {
            val key = label.toString()
            val existing = options.firstOrNull { it.label == key }
            if (existing != null) {
                if (text.isNotBlank()) existing.text = if (existing.text.isEmpty()) text else existing.text + " " + text
                existing.box = existing.box.union(box)
            } else {
                options += MutableOption(key, text, box)
            }
            this.box = this.box.union(box)
        }

        /** Dodaje treść linii: część przed pierwszą opcją → pień, dalej opcje. */
        fun consume(text: String, line: LogicalLine, from: Int) {
            box = box.union(line.box)
            lineCount++
            val start = from.coerceIn(0, text.length)
            val spans = splitOptions(text, line.pieceStarts, nextLabel, start)
            if (spans.isEmpty()) {
                addContinuation(text.substring(start).trim(), line.boxOf(start, text.length))
                return
            }
            val firstStart = spans.first().markerStart
            if (firstStart > start) {
                val stemText = text.substring(start, firstStart).trim()
                addStem(stemText, line.boxOf(start, firstStart))
            }
            for (s in spans) {
                addOption(s.label, s.text, line.boxOf(s.markerStart, s.textEnd))
            }
        }

        fun toQuestion(salt: Int): DetectedQuestion? {
            val text = stem.toString().trim()
            if (text.isEmpty()) return null
            val hints = hint
            val fullTextForHints = if (hints.isNullOrBlank()) text else "$hints $text"
            val answerOptions = options.map { AnswerOption(it.label, it.text.trim(), it.box) }
                .sortedBy { it.label }
            val type = when {
                answerOptions.size >= 2 ->
                    if (QuestionPatterns.isMultiHint(fullTextForHints)) QuestionType.MULTI_CHOICE else QuestionType.SINGLE_CHOICE
                formField || QuestionPatterns.matchFormPairs(text).isNotEmpty() ||
                    QuestionPatterns.matchBareField(text) != null -> QuestionType.FORM_FIELD
                QuestionPatterns.isMath(text) -> QuestionType.MATH
                else -> QuestionType.OPEN
            }
            val id = QuestionId.of(text, answerOptions.map { it.label }, answerOptions.map { it.text }, salt)
            return DetectedQuestion(id, number, type, text, answerOptions, box)
        }
    }

    /** Buduje pytania i nadaje unikalne id (powtórzone treści dostają sól). */
    private fun buildUnique(builders: List<Builder>): List<DetectedQuestion> {
        val out = ArrayList<DetectedQuestion>(builders.size)
        val seen = HashMap<String, Int>()
        for (b in builders) {
            val q = b.toQuestion(0) ?: continue
            val count = seen.getOrDefault(q.id, 0)
            seen[q.id] = count + 1
            out += if (count == 0) q else (b.toQuestion(count) ?: q)
        }
        return out
    }
}
