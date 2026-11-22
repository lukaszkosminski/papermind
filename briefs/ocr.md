# Agent A — rola `ocr` — worktree: /home/lk/projekty/papermind-wt/ocr

Pakiet `pl.lk.papermind.ocr` (+ testy w `app/src/test/java/pl/lk/papermind/ocr/`).

1. `MlKitOcrPipeline(context: Context, settings: Flow<AppSettings>) : OcrPipeline` — CameraX `MlKitAnalyzer` z
   `ImageAnalysis.COORDINATE_SYSTEM_VIEW_REFERENCED` + `TextRecognition` (`TextRecognizerOptions.DEFAULT_OPTIONS`, łaciński — polskie znaki OK).
   `bind(controller, executor)` → `controller.setImageAnalysisAnalyzer(...)`. Mapuj `Text.TextBlock/Line` na `OcrLine` z `Box` w pikselach widoku.
   `frames`: StateFlow/SharedFlow z najnowszą klatką (conflate). Wydajność: `STRATEGY_KEEP_ONLY_LATEST`, rozdzielczość analizy
   wystarczająca dla małego druku (ResolutionSelector na controllerze), throttling jeśli trzeba.
2. `FrameStabilizer` — emituje `ParsedPage`, gdy przez `stabilityMs` (z ustawień) tekst klatek jest podobny
   (Jaccard tokenów / znormalizowany Levenshtein > ~0.9) ORAZ różni się istotnie od ostatnio wyemitowanej strony
   (nie spamować AI tą samą kartką). Ruch/rozmycie = brak emisji. Czas wstrzykiwany (testowalność).
3. `RegexQuestionParser : QuestionParser` — czysta logika JVM. Grupowanie linii w pytania wg układu (Y, wcięcia) i wzorców:
   numeracja `1.`, `1)`, `Pytanie 1`, `Zad. 1`, `Zadanie 1:`; opcje `A.`, `A)`, `a)`, `(a)`, `□`, `○`, `☐`, także kilka opcji w jednej
   linii `A. x  B. y`. Typy: SINGLE_CHOICE, MULTI_CHOICE („wielokrotnego wyboru”, „zaznacz wszystkie poprawne”, „select all that apply”),
   FORM_FIELD (`Etykieta: ____`, `......`, puste pole po dwukropku), MATH (dużo cyfr/operatorów `= + − × ÷ ^ √`, „oblicz”, „rozwiąż”, „wyznacz”),
   OPEN (reszta). Box pytania = suma boxów jego linii; box opcji = box linii (dla kilku opcji w linii — proporcjonalne cięcie wg pozycji znaków).
   Stabilne `id`: skrócony SHA-1 znormalizowanego tekstu (lowercase, bez spacji i interpunkcji) pytania + etykiet opcji — odporny na drobne błędy OCR.
   Tekst bez numeracji też ma działać.
4. Solidne testy parsera i stabilizatora z realistycznymi próbkami (PL i EN, testy medyczne, formularze, matematyka,
   błędy OCR typu `B,` zamiast `B.`, `8)` zamiast `B)`).

Konstruktor zgodny z TEAM.md: `MlKitOcrPipeline(context, settingsFlow)`.
