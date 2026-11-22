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
