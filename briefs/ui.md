# Agent C — rola `ui` — worktree: /home/lk/projekty/papermind-wt/ui

Pakiet `pl.lk.papermind.ui`, `MainActivity.kt`, `PaperMindApp.kt` (Application, ręczne DI), `res/`, `AndroidManifest.xml`.

Inni agenci równolegle piszą: `MlKitOcrPipeline(context, settingsFlow)`, `AnswerServiceImpl(engines, settingsRepository, scope)`,
`GeminiNanoEngine(context)`, `OpenCodeEngine(settingsFlow)`, `CloudEngine(settingsFlow)`, `DataStoreSettingsRepository(context)`.
Ty używasz FAKE'ÓW w `ui/fake` (FakeOcrPipeline z przykładowymi pytaniami i boxami, FakeAnswerService, InMemorySettingsRepository,
FakeEngine); DI w PaperMindApp tak, by podmiana na prawdziwe była kilkoma liniami (oznacz `TODO(integracja)`).

1. Ekran kamery: uprawnienie CAMERA (rationale), `AndroidView(PreviewView)` + `LifecycleCameraController` (bindToLifecycle),
   pełny ekran, tap-to-focus, latarka, pinch zoom. `OcrPipeline.bind(controller, executor)`.
2. Nakładka (Canvas nad podglądem; współrzędne = piksele PreviewView): opcjonalnie delikatne obrysy linii OCR; pytania zamknięte —
   zielony zaokrąglony prostokąt + ✓ na poprawnej opcji; OPEN/MATH/FORM_FIELD — czytelny dymek z krótką odpowiedzią przy boxie pytania
   (półprzezroczyste tło, zawijanie, nie wychodzi poza ekran); Pending = pulsujący obrys; Failed = czerwona ikonka.
   Boxy są z ostatniej stabilnej strony, a kamera mogła się ruszyć → dopasuj do najnowszej `OcrFrame`: dla każdego pytania/opcji
   znajdź linię o najbardziej podobnym tekście i użyj jej boxa (śledzenie); wygaszaj, gdy zniknie z kadru.
   Logikę dopasowania wydziel do czystej klasy z testami.
3. Panel: `BottomSheetScaffold` z peek height — lista pytań: numer, skrót treści, odpowiedź (opcje pogrubione), uzasadnienie
   (gdy showExplanations), silnik i pewność; tap = podświetlenie na nakładce.
4. Przyciski: pauza/zamrożenie (zatrzymuje analizę, nakładka zostaje), wyczyść, ustawienia, wskaźnik aktywnego silnika/statusu.
5. Ekran ustawień (Compose, prosta nawigacja bez dodatkowych bibliotek): silnik, fallback, URL/login/hasło/model OpenCode,
   provider/klucz (pole hasła)/model chmury, język, uzasadnienia, stabilityMs (slider), „Testuj połączenie” (`AnswerEngine.isAvailable()`).
6. `MainViewModel` (z fabryką): `pages → answerService.submit`, łączy frames + answers + selected w `UiState` (StateFlow), freeze.
   Testy jednostkowe ViewModelu z fake'ami.
7. Material 3, ciemny motyw, dynamic color, czytelność na S25U; `FLAG_KEEP_SCREEN_ON` podczas skanowania. Adaptive icon (wektor).
