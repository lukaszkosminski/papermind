# Agent A — `ocr` — notatka

## Co zrobione

Pakiet `pl.lk.papermind.ocr` (implementacja kompletna, build + testy JVM zielone):

| Plik | Rola |
|---|---|
| `MlKitOcrPipeline.kt` | `OcrPipeline` na CameraX `MlKitAnalyzer` z `COORDINATE_SYSTEM_VIEW_REFERENCED` + ML Kit `TextRecognition` (`TextRecognizerOptions.DEFAULT_OPTIONS`, łaciński → PL OK). `STRATEGY_KEEP_ONLY_LATEST`, `ResolutionSelector` 1920×1080 (`FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER`, 16:9), throttling 120 ms, `frames` = `StateFlow<OcrFrame>` (konflatowany), `pages` = `SharedFlow` (replay=1). `Text.TextBlock/Text.Line` → `OcrLine` z `Box` w pikselach widoku (pewność z `Line.confidence`). |
| `FrameStabilizer.kt` | Emituje `ParsedPage` po `stabilityMs` (z `AppSettings`, zmienialne w locie) stabilnego tekstu; podobieństwo = średnia Jaccarda tokenów i znormalizowanego Levenshteina (próg 0.90), reset okna przy ruchu (< 0.55) i przy rozmyciu (niska pewność OCR), dedup ostatnio wysłanej strony po id + podobieństwie 0.985, cooldown 600 ms. Czas i parser wstrzykiwane (testy na zegarze atrapym). |
| `RegexQuestionParser.kt` | Segmentacja wg numeracji (`1.`, `1)`, `1:`, `(1)`, `Pytanie 1`, `Pyt. 2`, `Zad. 3`, `Zadanie 4:`, `Question 5`, `Zadanie nr 6`), odstępów akapitowych i słów pytających (bez numeracji też działa). Opcje: `A.`, `A)`, `a)`, `(a)`, `□/○/☐`, kilka w jednej linii, dwie kolumny opcji, tolerancja `B,` i `8)`→`B)`. Typy: SINGLE_CHOICE, MULTI_CHOICE (hinty PL/EN, także z linii-instrukcji nad pytaniem), FORM_FIELD (pary `Etykieta: ____`, `......`, etykieta z pustym polem, sklejanie etykiety z wypełniaczem z kolejnej linii), MATH, OPEN. Box pytania = suma linii, box opcji = proporcjonalne cięcie linii. |
| `QuestionPatterns.kt`, `LineLayout.kt`, `QuestionId.kt`, `TextNormalizer.kt` | Wzorce, łączenie wierszy/kolumn, stabilne id (skrócony SHA-1: znormalizowany tekst pytania bez numeru + etykiety opcji, z solą dla duplikatów na stronie), normalizacja i metryki podobieństwa. |
| Testy (`src/test/.../ocr/`) | 63 testy JVM: 30 parsera (PL/EN, medyczne, formularze, matematyka, błędy OCR, dwie kolumny, zawijane opcje, id), 10 stabilizatora, 14 wzorców, 9 normalizatora. |

Zmiany w plikach współdzielonych (konieczne): `app/build.gradle.kts` + `gradle/libs.versions.toml` — dodana
zależność `androidx.camera:camera-mlkit-vision` (1.7.0). `gradle.properties` nietknięty.

## Dla agenta C (UI) — integracja

- Konstruktor: `MlKitOcrPipeline(context, settingsFlow)` (zgodny z TEAM.md). Dodatkowo `close()` — **wywołać w `onDestroy`**
  (zamyka `TextRecognizer` i scope). `bind()` **z wątku głównego** (wymóg `CameraController`), `executor` = wątek tła,
  np. `Executors.newSingleThreadExecutor()`.
- Kolejność: `controller.setImageAnalysisBackpressureStrategy`/resolution ustawia pipeline w `bind`, potem
  `controller.bindToLifecycle(lifecycleOwner)`. `unbind(controller)` można wywołać przy wyjściu z ekranu.
- `frames` — najnowsza klatka (można rysować ramki tekstu), `pages` — tylko ustabilizowane strony (wejście do AI).
  Po zmianie ustawienia `stabilityMs` pipeline stosuje je automatycznie.
- Box opcji obejmuje etykietę (`B. Trzustka`) — dobra do zielonej ramki; box pytania obejmuje pień + opcje.

## Dla agenta B (AI)

- `DetectedQuestion.text` **nie zawiera** prefiksu numeracji — numer jest w `DetectedQuestion.number`.
- `QuestionType.MULTI_CHOICE` = wielokrotny wybór (hint mógł przyjść z linii-instrukcji nad pytaniem; nie ma go w `text`).
- `DetectedQuestion.id` jest stabilne między klatkami (lowercase, bez spacji/interpunkcji, bez numeru pytania),
  więc cache po `id` działa; przy duplikatach identycznej treści na jednej stronie id dostaje sól.
- Pytania FORM_FIELD: jedno pytanie = jedno pole (etykieta z dwukropkiem), odpowiedź `Answer.text` = wartość do wpisania.

## Ograniczenia / otwarte

- `8)` jako `B)` akceptowane tylko w środku listy opcji (przy pierwszej opcji cyfra jest traktowana jako numer pytania) —
  świadomy kompromis, inaczej pytanie „8)” po opcji „A” byłoby brane za opcję.
- Dwie nienumerowane odpowiedzi otwarte bez odstępu akapitowego zostaną sklejone w jedno pytanie (brak numeracji =
  brak jednoznacznego sygnału granicy). Numerowane zawsze rozdzielane.
- Hint „zaznacz wszystkie…” dotyczy tylko pytania, które następuje bezpośrednio po instrukcji (nie propagujemy zakresu
  „pytania 1–5”).
- Cyfry rzymskie jako numeracja pytań i etykiety opcji spoza A–H nie są wspierane.
- Rozmycie wykrywane pewnością OCR + podobieństwem tekstu (bez analizy ostrości obrazu) — wystarczające, bo rozmyty
  tekst zwykle „pływa” między klatkami.
- Brak testów na urządzeniu (CameraX/ML Kit są Androidowe); logika czysta jest pokryta testami JVM. Zależność
  `camera-mlkit-vision` i ML Kit text-recognition (model bundlowany) zweryfikowane pod kątem API 1.7.0 / 16.0.1.

## Prośby o zmianę kontraktu

1. `OcrPipeline` — dodać `fun close()` (lub interfejs `Disposable`), żeby UI mogło zwalniać `TextRecognizer` przez kontrakt,
   a nie przez rzutowanie na `MlKitOcrPipeline`. Obejście: publiczne `close()` w implementacji.
2. `OcrPipeline` — rozważyć `fun reset()` (wymuszenie ponownej emisji tej samej strony po wyczyszczeniu odpowiedzi).
   Obejście: `FrameStabilizer.reset()` istnieje, ale nie jest wystawione w kontrakcie.
3. `OcrPipeline.frames` — gdyby UI chciało synchronicznie sięgać po ostatnią klatkę, wygodniejsze byłoby
   `StateFlow<OcrFrame>` zamiast `Flow<OcrFrame>` (implementacja i tak zwraca `StateFlow`).

---

## Runda poprawek (po `notes/review.md` + `briefs/fixes.md`)

### #7 (WAŻNY) — konfiguracja `ImageAnalysis` poza `bind()`

- `MlKitOcrPipeline.ANALYSIS_RESOLUTION` jest teraz **publiczną stałą** (companion object), a obok niej dodana
  `MlKitOcrPipeline.configureController(controller)` — ustawia `STRATEGY_KEEP_ONLY_LATEST` i rozdzielczość analizy
  1920×1080 (`FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER`).
- `bind()` podpina **wyłącznie analizator** (`setImageAnalysisAnalyzer`) — żadnej rekonfiguracji use case'a, więc
  pauza/wznowienie ekranu nie restartuje już sesji kamery. Powtórne `bind()` z tym samym kontrolerem jest no-op.
- **Wymagany krok po stronie UI (agent C):** wywołać `MlKitOcrPipeline.configureController(controller)` **raz**,
  z wątku głównego, przy tworzeniu kontrolera w `remember {}` (przed `bindToLifecycle`). Bez tego CameraX użyje
  domyślnej, małej rozdzielczości analizy — OCR małego druku będzie wyraźnie gorszy. `bind()`/`unbind()`/`close()`
  trzeba wołać z wątku głównego (wymóg `CameraController`; `close()` z tła przekierowuje czyszczenie analizatora).

### #8 (WAŻNY) — wydajność `similarity()`

- `TextNormalizer` robi teraz **jedno** foldowanie i jedno przejście na tekst (sklejony tekst + tokeny razem).
- Szybkie odrzucenie po Jaccardzie (`j < 0.50` → Levenshtein w ogóle nie jest liczony).
- Levenshtein: wspólny prefiks/sufiks odcinany przed macierzą, pasmo Ukkonena z **adaptacyjnym poszerzaniem**
  (32 → 128 → 512 …) i przerwaniem, gdy cały wiersz przekracza limit — dla typowej, prawie identycznej klatki
  liczy się tylko wąskie pasmo.
- Nowe `similarityAtLeast(a, b, threshold)`: dokładna decyzja progowa, pasmo dobierane do progu
  (0.985 → ~1,5% długości tekstu). `FrameStabilizer` używa jej dla progów 0.55 / 0.90 / 0.985.
- Pomiar na JVM (test `similarity dziala na dlugiej stronie szybko`): strona ~1467 znaków, mediana
  `similarity()` ≈ 0,7 ms (limit < 2 ms), `similarityAtLeast(…, 0.985)` ≈ 0,2 ms. Wcześniej: pełna macierz
  1500×1500 (≈2,25 mln komórek) kilka razy na sekundę.

### #17 (DROBNY) — id pytania odporne na szum OCR

- `TextNormalizer.ocrCanonical`: diakrytyki/interpunkcja/spacje usunięte, mylone znaki ujednolicone:
  `0→o`, `1→l`, `i→l`, `5→s`, `8→b`, `rn→m`.
- `QuestionId.of(stem, optionCount, salt)`: SHA-1 z kanonicznej treści pytania (bez numeru i bez opcji)
  + liczba opcji; wynik to 16 znaków hex (format bez zmian).
- **Zmiana semantyki (zgodnie z briefem):** treść i etykiety opcji nie wchodzą już do id — liczy się tylko pień
  i **liczba** opcji. Pytania o identycznym pniu i tej samej liczbie opcji mają to samo id; dla dedupu stron
  i cache AI to pożądane (odporność na pomyłki w opcjach), a kolizja dwóch różnych pytań o identycznym pniu jest
  znikoma. Gdyby w przyszłości trzeba było rozróżniać opcje, warto dodać do id „odcisk” opcji osobno.
- Testy: `QuestionIdTest` (8 przypadków: 0/o, 1/l/i, 5/s, 8/b, rn/m, diakrytyki, liczba opcji, sól, format).

### `reset()` / `close()` — poprawność wątków

- `reset()` ustawia `AtomicBoolean`; reset stabilizatora wykonuje **jedyny** wątek przetwarzający klatki
  (`getAndSet(false)`), więc nie ma wyścigu ze stanem `FrameStabilizer`.
- `close()` jest idempotentne (`compareAndSet`): czyści analizator, zamyka kanał, `TextRecognizer` i scope;
  kolejne `bind()` po `close()` jest ignorowane (log ostrzeżenia).
- Strumień ustawień ma `.catch {}` — błąd DataStore nie zabija pętli OCR (zostaje domyślne `stabilityMs`).

### Testy po rundzie poprawek

`assembleDebug` + `testDebugUnitTest` zielone. Testy OCR: 77 (parser 32, stabilizator 11, id 8, wzorce 14,
normalizator 12 — w tym test wydajności i zgodności `similarityAtLeast` z `similarity`).

---

## Krzyżówki (v1)

### Co zrobione

**`CrosswordClueParser`** (nowy, czysta logika JVM, wywoływany z `RegexQuestionParser.parse` *przed* segmentacją pytań):
- nagłówki `Poziomo` / `Pionowo` / `Across` / `Down` (dwukropek, kropka, wersaliki, opcjonalne `(5)`), także z pierwszym
  hasłem w tej samej linii (`Poziomo: 1. Kot (3)`),
- hasła `1. treść (5)`, `1) treść`, `12 treść [7]`, `3. treść 5 liter`, kilka haseł w jednej linii
  (osobne `Box` przez proporcjonalne cięcie linii),
- zawinięte hasło (kolejna linia bez numeru, zaczynająca się małą literą) jest doklejane do poprzedniego razem z długością,
- wynik: `DetectedQuestion(type = CROSSWORD_CLUE, number = numer, clue = CrosswordClue(number, direction, length))`,
  `text` bez numeru i znacznika długości; linie spoza listy wracają do zwykłej segmentacji,
- stabilne id: `QuestionId.forClue` = kierunek + numer + kanoniczna treść (odporne na 0/o, 1/l, 5/s, 8/b, rn/m,
  diakrytyki, interpunkcję),
- krzyżówka panoramiczna (hasła w polach, bez nagłówka) nie jest rozpoznawana, ale parser się nie wywraca.

**`CrosswordGridDetector`** (nowy, czysta logika JVM na luminancji Y):
- downscale do ~640 px dłuższego boku, binaryzacja Otsu + lokalna średnia (integral image),
- estymacja lekkiego obrotu ±5°: rzut ciemnych pikseli na oś „w poprzek linii", metryka = suma 3 najwyższych
  binów rzutu (bin ~3 px) + refine co 0.1°; przy znikomej poprawie względem 0° zostaje 0°,
- obrót maski do układu linii poziomych/pionowych, detekcja linii jako najdłuższych ciągów ciemnych pikseli
  (tolerancja 2 px przerwy), walidacja regularności okresu (dopasowanie do sieci) i kwadratowości komórek,
- `blocked` = udział ciemnych pikseli w komórce (inset 16%) ≥ 0.5,
- numeracja: standardowa (komórka startuje slot poziomy/pionowy długości ≥ 2) + **numery z OCR** (same cyfry,
  mała wysokość, lewy górny róg komórki); numery OCR wygrywają, gdy zgodność ze standardem ≥ 60%,
  z deduplikacją numerów,
- odrzucanie fałszywych siatek/tabel: min. 5×5, wymagane czarne pola **lub** wiarygodna numeracja, brak
  całkowicie zaczernionego wiersza/kolumny, komórki kwadratowe (±40%), regularny okres,
- sloty ACROSS/DOWN; boxy w pikselach obrazu wejściowego, `CrosswordGrid.remapBoxes { }` przenosi je do widoku.

**`MlKitOcrPipeline`**:
- `FrameAnalyzer` co **500 ms** (nie każdą klatkę) kopiuje płaszczyznę Y *przed* przekazaniem klatki do ML Kit
  oraz macierz obraz→widok; macierz odtwarza obliczenia `MlKitAnalyzer` (sensor→buffer ∘ rotacja ML Kit,
  odwrócone, złożone z sensor→view z `updateTransform`), więc boxy są w pikselach PreviewView,
- snapshoty dopasowywane po timestampie klatki (bufor 3, TTL 2 s), detekcja w wątku przetwarzania
  (`Dispatchers.Default`), nie na UI,
- siatka jest trzymana między klatkami i zdejmowana dopiero, gdy tekst zmieni się wyraźnie (inna strona),
- `ParsedPage.grid` + `snapshotJpeg` tylko dla stron z siatką lub hasłami; JPEG ~80%, skala szarości,
  dłuższy bok ≤ 1600 px, orientacja wg `rotationDegrees`, kodowanie poza wątkiem UI (przy emisji strony),
- numery OCR do detektora: boxy z widoku mapowane do obrazu przez odwrotność macierzy obraz→widok.

**`FrameStabilizer`**:
- `onFrame(frame, grid = null)`; strony z siatką lub listą haseł mają **automatycznie podwójne** okno stabilności
  (UI nie musi nic włączać),
- dedup uwzględnia podpis siatki (wymiary + wzór pól + numeracja + sloty): ta sama krzyżówka nie jest wysyłana
  ponownie, ale inna siatka przy identycznym tekście haseł — tak,
- strona z samą siatką (bez odczytanych haseł) też jest emitowana.

### Dla UI (agent C)

- `ParsedPage.grid` — boxy komórek w pikselach widoku; `GridCell.blocked` do pomijania, `number` w komórce startowej,
  `CrosswordSlot.cells` w kolejności liter; slot łączy się z hasłem przez `(number, direction)`.
- Hasła to `DetectedQuestion.type == CROSSWORD_CLUE`; `clue.number`, `clue.direction`, `clue.length`;
  `text` = treść hasła (bez numeru i „(5)").
- `snapshotJpeg` bywa dołączony **tylko** dla stron krzyżówkowych.
- Tryb „Krzyżówka" nie musi niczego włączać w OCR — stabilizator sam wydłuża okno ×2 dla stron z siatką
  lub nagłówkiem listy haseł.

### Dla AI (agent B)

- `CrosswordClue.pattern` jest `null` (OCR nie zna jeszcze skrzyżowań) — solver powinien zbudować
  `"_".repeat(length)` i uzupełnić litery z siatki.
- `ParsedPage.grid` + `snapshotJpeg` są dostępne dla multimodalności.
- Numery haseł = numery slotów (para `(number, direction)`), więc odpowiedzi da się wstawić w komórki.

### Ograniczenia / otwarte

- Perspektywa nie jest korygowana (tylko obrót ±5°); silne zniekształcenie perspektywiczne → brak detekcji.
- Krzyżówki panoramiczne (hasła w polach) poza v1.
- Lista haseł wymaga nagłówka kierunku; bez nagłówka hasła trafiają do zwykłego parsera pytań.
- Numery OCR są ufane przy zgodności ≥ 60% ze standardową numeracją; przy niższej używana jest standardowa.
- Wykrywanie siatki odbywa się najwyżej co 500 ms i tylko dla klatek, dla których jest snapshot
  (przy dużym obciążeniu część klatek jest pomijana — z założenia).
- JPEG powstaje przy emisji strony, więc może być do ~0,5 s starszy od klatki.
- Testy detektora są syntetyczne (JVM); brak testów na urządzeniu.

### Testy po dodaniu krzyżówek

`assembleDebug` + `testDebugUnitTest` zielone. Testy OCR: 107 (12 parsera haseł, 15 detektora siatki,
14 stabilizatora, 32 parsera pytań, 14 wzorców, 8 id, 12 normalizatora).
