package pl.lk.papermind.ocr

/**
 * Wzorce rozpoznawania struktury testu/formularza (PL + EN). Czysta logika JVM, bez zależności od Androida.
 * Tu nie ma logiki grupowania — tylko pojedyncze linie (numeracja, opcje, pola formularza, matematyka).
 */
internal object QuestionPatterns {

    // ---------------------------------------------------------------- numeracja pytań

    /** "1." "1)" "1:" "(1)" — numer w grupie 1, treść w grupie 2. */
    private val PLAIN_NUMBER = Regex("""^(\d{1,3})\s{0,2}(?:[.):])(?!\d)\s*(.*)$""")
    private val PAREN_NUMBER = Regex("""^\(\s*(\d{1,3})\s*\)\s*(.*)$""")

    /**
     * "Pytanie 1", "Pyt. 2.", "Zadanie 3:", "Zad. 4", "Question 5", "Q6", "Problem 7", "Zadanie nr 8".
     * Numer w grupie 1, treść w grupie 2.
     */
    private val WORD_NUMBER = Regex(
        """^(?:pytanie|pyt\.?|zadanie|zad\.?|zad|question|problem|exercise|ex\.?|q)\s*(?:nr\.?|no\.?|#)?\s*(\d{1,3})\s*[.:)\-–—]?\s*(.*)$""",
        RegexOption.IGNORE_CASE,
    )

    /** Maksymalny numer pytania — chroni przed braniem lat/dat za numerację. */
    private const val MAX_QUESTION_NUMBER = 199

    class Numbered(val number: String, val rest: String)

    /** Czy linia zaczyna nowe pytanie; zwraca numer i treść bez prefiksu. */
    fun matchQuestionStart(text: String): Numbered? {
        val t = text.trim()
        if (t.isEmpty()) return null
        for (re in arrayOf(PAREN_NUMBER, WORD_NUMBER, PLAIN_NUMBER)) {
            val m = re.matchEntire(t) ?: continue
            val n = m.groupValues[1].toIntOrNull() ?: continue
            if (n in 1..MAX_QUESTION_NUMBER) return Numbered(n.toString(), m.groupValues[2].trim())
        }
        return null
    }

    fun startsWithQuestionNumber(text: String) = matchQuestionStart(text) != null

    /** Pozycja początku treści pytania względem oryginalnego (przyciętego) tekstu linii. */
    fun questionStemStart(text: String): Int {
        val t = text.trimEnd()
        val n = matchQuestionStart(t) ?: return 0
        return (t.length - n.rest.length).coerceIn(0, t.length)
    }

    // ---------------------------------------------------------------- opcje

    private const val BULLETS = "□☐▢◯○◦●▪■◆◇✓✔▣▤"

    /** "A." "B)" "c)" "D," "E:" "F]" — litera + separator (OCR często myli '.' z ','). */
    val LETTER_OPTION = Regex("""(?<![\p{L}\p{N}])([A-Ha-h])\s*[.)\],:;]\s*""")

    /** "(a)" "(B)" — opcja w nawiasach. */
    val PAREN_OPTION = Regex("""(?<![\p{L}\p{N}])\(\s*([A-Ha-h])\s*\)\s*""")

    /** Znacznik wyboru: □ ○ ▢ … */
    val BOX_OPTION = Regex("""[$BULLETS]\s*""")

    val BOX_MARKERS: Set<Char> = BULLETS.toSet()

    /** Cyfrowy "numer" opcji — bywa, że OCR czyta "B)" jako "8)" (a "A)" jako "4)"). */
    val DIGIT_OPTION = Regex("""(?<![\p{L}\p{N}])([0-9])\s*[.)\],:;]\s*""")

    /** Czy linia zaczyna się znacznikiem opcji (szybki test bez kontekstu). */
    fun startsWithOptionMarker(text: String): Boolean {
        val t = text.trimStart()
        if (t.isEmpty()) return false
        if (t[0] in BULLETS) return true
        if (t[0] == '(') return PAREN_OPTION.find(t)?.range?.first == 0
        val m = LETTER_OPTION.find(t) ?: return false
        return m.range.first == 0
    }

    /** Mapowanie cyfr mylonych przez OCR na litery opcji (kształt znaku). */
    fun digitToLetter(d: Char): Char? = when (d) {
        '4' -> 'A'
        '8' -> 'B'
        '6' -> 'G'
        '0' -> 'O'
        '1' -> 'I'
        '5' -> 'S'
        '2' -> 'Z'
        '7' -> 'T'
        else -> null
    }

    // ---------------------------------------------------------------- typy pytań

    private val MULTI_HINTS = listOf(
        "wielokrotnego wyboru", "wielokrotny wybor", "wielokrotnej odpowiedzi", "zaznacz wszystkie",
        "wybierz wszystkie", "zaznacz prawidlowe odpowiedzi", "wszystkie poprawne", "wszystkie prawidlowe",
        "kilka odpowiedzi", "kilka poprawnych", "wiecej niz jedna", "jedna lub wiecej", "co najmniej dwie",
        "select all", "choose all", "check all", "mark all", "all that apply", "multiple answers",
        "more than one", "multiple correct", "one or more", "multiple choice",
    )
    private val SINGLE_HINTS = listOf(
        "jednokrotnego wyboru", "jednokrotny wybor", "jednokrotnej odpowiedzi", "jedna odpowiedz",
        "jedna poprawna", "tylko jedna", "single choice", "one answer", "only one", "choose one",
        "select one", "mark one",
    )

    fun isMultiHint(text: String): Boolean {
        val f = TextNormalizer.fold(text)
        return MULTI_HINTS.any { it in f }
    }

    fun isSingleHint(text: String): Boolean {
        val f = TextNormalizer.fold(text)
        return SINGLE_HINTS.any { it in f }
    }

    private const val MATH_OPS = "=+−×÷*^√∑∫∏≤≥<>±≠∞%π·/"

    private val MATH_VERBS = setOf(
        "oblicz", "rozwiaz", "wyznacz", "uprosc", "policz", "sprawdz", "udowodnij", "wykaz", "przeksztalc",
        "solve", "calculate", "compute", "simplify", "evaluate", "differentiate", "integrate", "prove",
    )
    private val MATH_PHRASES = listOf(
        "ile wynosi", "ile to jest", "ile jest", "jaka jest wartosc", "how much is", "what is", "what's",
    )

    fun isMath(text: String): Boolean {
        val compact = text.filterNot { it.isWhitespace() }
        if (compact.isEmpty()) return false
        val digits = compact.count { it.isDigit() }
        val letters = compact.count { it.isLetter() }
        val hasOp = compact.any { it in MATH_OPS } || Regex("""\d\s*/\s*\d""").containsMatchIn(compact)
        val folded = TextNormalizer.fold(text)
        val words = Regex("[a-z]+").findAll(folded).map { it.value }.toSet()
        val verb = words.any { it in MATH_VERBS }
        val phrase = MATH_PHRASES.any { it in folded }
        return when {
            // wzór / działanie: operatory + cyfry, mało liter
            hasOp && digits >= 1 && letters.toDouble() / compact.length <= 0.55 -> true
            // polecenie typu "Oblicz …"
            verb && (digits >= 1 || hasOp) -> true
            phrase && hasOp && digits >= 1 -> true
            // samo "2x + 3 = 11" (litera jako zmienna)
            hasOp && digits >= 1 && compact.length <= 40 -> true
            else -> false
        }
    }

    private val QUESTION_WORDS = setOf(
        "wymien", "wymienic", "opisz", "wyjasnij", "podaj", "uzasadnij", "dlaczego", "jak", "jaki", "jaka",
        "jakie", "jakich", "jakim", "co", "czym", "czemu", "ktory", "ktora", "ktore", "ktorzy", "ile", "kiedy",
        "gdzie", "kto", "czy", "porownaj", "zdefiniuj", "scharakteryzuj", "omow", "wskaz", "wybierz", "zaznacz",
        "uzupelnij", "napisz", "okresl", "przedstaw", "dokoncz", "przyporzadkuj", "polacz", "ocen", "czego",
        "skad", "narysuj", "zapisz", "podkresl", "zastanow", "wypisz", "dopisz", "uzupelnij",
        "what", "why", "how", "which", "who", "when", "where", "explain", "describe", "list", "define", "name",
        "state", "give", "compare", "choose", "select", "mark", "complete", "fill", "is", "are", "do", "does",
        "can", "write", "identify", "outline", "discuss", "justify", "match",
    ) + MATH_VERBS

    fun startsWithQuestionWord(text: String): Boolean {
        val first = Regex("\\p{L}+").find(TextNormalizer.fold(text))?.value ?: return false
        return first in QUESTION_WORDS
    }

    private val INSTRUCTION_VERBS = setOf("zaznacz", "wybierz", "wskaz", "select", "choose", "mark", "check")
    private val ANSWER_WORDS = listOf("odpowiedz", "odpowiedzi", "option", "answer", "poprawn")

    /** Linia-polecenie typu "Zaznacz wszystkie poprawne odpowiedzi:" — to nie jest pytanie do odpowiedzi. */
    fun isAnswerInstruction(text: String): Boolean {
        if (isMultiHint(text) || isSingleHint(text)) return true
        val f = TextNormalizer.fold(text)
        val first = Regex("\\p{L}+").find(f)?.value ?: return false
        return first in INSTRUCTION_VERBS && ANSWER_WORDS.any { it in f }
    }

    /** Czy nienumerowany tekst wygląda na pytanie/polecenie (a nie nagłówek strony). */
    fun looksLikeQuestion(text: String): Boolean {
        val t = text.trim()
        if (t.isEmpty()) return false
        if ('?' in t) return true
        if (t.trimEnd().endsWith(":")) return true
        if (isMath(t)) return true
        return startsWithQuestionWord(t)
    }

    // ---------------------------------------------------------------- formularze

    // '-' na końcu klasy znaków, żeby nie utworzył zakresu.
    private const val FILL_CHARS = "_.…–—-"
    private val FILL_RUN = Regex("""[$FILL_CHARS]{2,}""")
    private val BOX_RUN = Regex("""[$BULLETS]{1,}""")

    private const val LABEL = """([^:_…□☐▢▣▤▯?]{2,50}?)"""
    private val FORM_PAIR = Regex("""$LABEL\s*:\s*(?:${FILL_RUN.pattern}|${BOX_RUN.pattern})""")
    private val BARE_FIELD = Regex("""^([^:_.…□☐▢▣▤▯?]{2,40}?)\s*:\s*$""")
    private val LABEL_FILL = Regex("""^([^:_.…□☐▢▣▤▯?]{2,40}?)\s*(?:${FILL_RUN.pattern})$""")
    private val FILLER_ONLY = Regex("""^[\s$FILL_CHARS]{3,}$""")

    class FormPair(val label: String, val start: Int, val end: Int)

    /** Czy linia to wyłącznie wypełniacze ("_____" / "......"). */
    fun isFillerOnly(text: String) = FILLER_ONLY.matches(text.trim()) && text.trim().any { it in FILL_CHARS }

    /**
     * Pary "Etykieta: ____" w jednej linii (może być kilka, np. "Imię: ____ Nazwisko: ____").
     * Zwraca tylko wtedy, gdy pary pokrywają całą linię (poza białymi znakami i przecinkami/średnikami).
     */
    fun matchFormPairs(text: String): List<FormPair> {
        val t = text.trim()
        if (':' !in t) return emptyList()
        val matches = FORM_PAIR.findAll(t).toList()
        if (matches.isEmpty()) return emptyList()
        var pos = 0
        for (m in matches) {
            val gap = t.substring(pos, m.range.first)
            if (gap.any { !it.isWhitespace() && it != ',' && it != ';' }) return emptyList()
            pos = m.range.last + 1
        }
        if (t.substring(pos).any { !it.isWhitespace() && it != ',' && it != ';' && it != '.' }) return emptyList()
        return matches.map {
            FormPair(it.groupValues[1].trim(), it.range.first, it.range.last + 1)
        }.filter { it.label.count(Char::isLetter) >= 2 }
    }

    /** "Imię:" / "Data urodzenia:" — etykieta z dwukropkiem i pustym miejscem na wpis. */
    fun matchBareField(text: String): String? {
        val t = text.trim()
        val m = BARE_FIELD.matchEntire(t) ?: return null
        val label = m.groupValues[1].trim()
        if (label.count(Char::isLetter) < 2) return null
        if (label.split(' ').size > 5) return null
        if (startsWithQuestionWord(label)) return null
        return label
    }

    /** "Podpis ........" (etykieta bez dwukropka). */
    fun matchLabelWithFill(text: String): String? {
        val m = LABEL_FILL.matchEntire(text.trim()) ?: return null
        val label = m.groupValues[1].trim()
        return label.takeIf { it.count(Char::isLetter) >= 2 && label.split(' ').size <= 6 }
    }

    /** Czy linia to etykieta formularza w dowolnej postaci. */
    fun isFormLine(text: String): Boolean =
        matchFormPairs(text).isNotEmpty() || matchBareField(text) != null || matchLabelWithFill(text) != null
}
