# Funkcja: rozwiązywanie krzyżówek

Kontrakt rozszerzony przez kierownika (`model/Models.kt`): `QuestionType.CROSSWORD_CLUE`, `DetectedQuestion.clue: CrosswordClue?`,
`Direction`, `GridCell`, `CrosswordSlot`, `CrosswordGrid`, `ParsedPage.grid`, `ParsedPage.snapshotJpeg`.
Odpowiedź na hasło: `Answer.text` = wyraz WIELKIMI literami (polskie znaki dozwolone: Ą Ć Ę Ł Ń Ó Ś Ź Ż).
Zakres v1: klasyczna krzyżówka z siatką (białe/czarne pola, numerki w polach) + lista haseł „Poziomo/Pionowo” (lub „Across/Down”).
Krzyżówki panoramiczne (hasła w polach siatki) — poza zakresem v1, ale parser nie może się na nich wywracać.
Zasady jak w `briefs/COMMON.md`. Commit `feat(<rola>): krzyżówki ...`. Dopisz sekcję „Krzyżówki” w `notes/<rola>.md`.

## ocr (OpenCode / DeepSeek) — worktree /home/lk/projekty/papermind-wt/ocr
1. Parser haseł (w `RegexQuestionParser` lub osobny `CrosswordClueParser` wywoływany z niego): nagłówki `Poziomo`, `Pionowo`,
   `Poziomo:`, `POZIOMO`, `Across`, `Down`; hasła `1. treść (5)`, `1) treść`, `12 treść [7]`, `5 liter`; kilka haseł w jednej linii.
   Każde hasło → `DetectedQuestion(type = CROSSWORD_CLUE, clue = CrosswordClue(number, direction, length))`, id stabilne (number+direction+treść).
2. `CrosswordGridDetector` — czysta logika JVM na tablicy luminancji (`ByteArray` Y, width, height, rowStride):
   binaryzacja adaptacyjna → wykrycie regularnej siatki (projekcje poziome/pionowe linii, okres komórki, tolerancja perspektywy/
   lekkiego obrotu ±5°) → macierz komórek, `blocked` gdy komórka w większości ciemna → numeracja: preferuj numery z OCR
   (linie OCR o małej wysokości leżące w lewym górnym rogu komórki), w razie braku — standardowa numeracja krzyżówki
   (komórka zaczyna slot poziomy/pionowy długości ≥ 2) → `slots`. Odrzucaj fałszywe siatki (tabele w testach!): min. 5×5,
   obecność czarnych pól lub numerków, regularność okresu.
   Testy na syntetycznych obrazach (generuj siatkę w teście: białe/czarne pola, linie, szum, lekki obrót).
3. Integracja w `MlKitOcrPipeline`: analyzer ma dostęp do `ImageProxy` — co N klatek (np. co 500 ms, nie każdą!) uruchom
   detektor na płaszczyźnie Y; współrzędne pikseli obrazu → piksele widoku przez macierz z `updateTransform` (sensor→view)
   z uwzględnieniem rotationDegrees. Gdy strona ma siatkę lub hasła — `ParsedPage.grid` + `snapshotJpeg` (JPEG ~80%, dłuższy bok ≤ 1600 px;
   konwersja YUV→JPEG poza wątkiem UI). Stabilizator: ta sama krzyżówka = ta sama strona (nie spamuj AI).
   Uzgodnienie numerów: hasła z listy bez siatki też emituj (siatka może być poza kadrem).

## ai (pi / Claude Sonnet) — worktree /home/lk/projekty/papermind-wt/ai
1. `PromptBuilder`: sekcja krzyżówki — hasła z numerem, kierunkiem, długością i wzorcem (`_A__E`); wymagaj wyrazu dokładnie
   tej długości, WIELKIE litery, polskie znaki, bez spacji; język krzyżówki = język haseł. Jeśli jest siatka — opisz skrzyżowania
   (np. „3 poziomo litera 2 = 1 pionowo litera 4”).
2. Multimodalność: gdy `snapshotJpeg != null` — Cloud (Anthropic image block base64, Gemini inline_data, OpenAI image_url data URI)
   i OpenCode (sprawdź, czy API message przyjmuje part typu file/image — jeśli tak, użyj) wysyłają zdjęcie; Gemini Nano — sprawdź
   w API genai-prompt, czy obsługuje obraz na tym urządzeniu (S25U ma Nano multimodalne); jeśli nie — tylko tekst.
3. `CrosswordSolver` (czysta logika JVM): po odpowiedzi modelu wstaw wyrazy w sloty siatki, wykryj konflikty na skrzyżowaniach
   i złą długość → druga runda zapytania tylko o konfliktowe hasła z wzorcem znanych liter (max 2 rundy), wybór wariantu
   o wyższym confidence. Bez siatki — tylko walidacja długości. Testy z małą krzyżówką 5×5.
4. AnswerServiceImpl: hasła krzyżówki wysyłaj zbiorczo (całą krzyżówkę naraz, nie po jednym), timeout dla krzyżówki dłuższy (×2).
   Udostępnij litery per komórka dla UI: dodaj w pakiecie ai publiczną funkcję
   `fun crosswordLetters(grid: CrosswordGrid, questions: List<DetectedQuestion>, answers: Map<String, AnswerState>): Map<GridCell, Char>`
   (konflikt → litera z odpowiedzi o wyższym confidence).

## ui (agy / Gemini) — worktree /home/lk/projekty/papermind-wt/ui
1. Nakładka: gdy `page.grid != null` — rysuj litery w komórkach (wyśrodkowane, rozmiar ~60% komórki, kontrastowy kolor z cieniem,
   pomijaj komórki blocked), z `crosswordLetters(...)` (funkcja z pakietu ai — do czasu merge'a zrób lokalną kopię w ui z TODO).
   Śledzenie: siatka przesuwa się z kamerą — przelicz komórki przez przesunięcie/skalę boxa siatki względem bieżącej klatki
   (dopasuj po numerkach/liniach OCR wewnątrz siatki albo po `grid.box` z najnowszej strony). Pending = przerywane obrysy slotów.
2. Hasła na liście (bez siatki w kadrze) — dymek z wyrazem przy haśle + liczba liter.
3. Panel: sekcja „Krzyżówka” — Poziomo / Pionowo, numer, hasło skrócone, odpowiedź monospace z podziałem na litery.
   Tap na haśle → podświetlenie slotu w siatce.
4. Przycisk/tryb „Krzyżówka” (ikona siatki) — nic nie wymusza w OCR, ale zwiększa czas stabilizacji ×2 i pokazuje wskazówkę
   „Zmieść siatkę i hasła w kadrze; trzymaj nieruchomo”.
5. Fake'i: FakeOcrPipeline z przykładową krzyżówką 5×5 i hasłami (do podglądu / testów).
