# Notatka agenta UI (`notes/ui.md`)

## Co zrobione
1. **Ekran kamery (`CameraScreen.kt`)**:
   - Sprawdzanie i obsługa uprawnienia `CAMERA` z ekranem wyjaśniającym (rationale) oraz możliwością przejścia do ustawień aplikacji w przypadku trwałej odmowy.
   - Pełnoekranowy podgląd `AndroidView(PreviewView)` zintegrowany z `LifecycleCameraController` (`bindToLifecycle`).
   - Obsługa tap-to-focus, pinch-to-zoom, przełącznik latarki (`enableTorch`).
   - Podpięcie do `OcrPipeline.bind(controller, executor)` i `unbind` przy pauzie/opuszczeniu ekranu.
   - Flaga `FLAG_KEEP_SCREEN_ON` podczas aktywnego skanowania.
   - Płynne zamrożenie klatki (`previewView.bitmap`) w trybie pauzy.

2. **Nakładka AR (`AnswerOverlay.kt`, `BubblePlacement.kt`, `AnswerText.kt`)**:
   - Rysowanie na `Canvas` we współrzędnych pikseli `PreviewView`.
   - Pytania zamknięte: zielony zaokrąglony prostokąt (`OverlayColors.Correct`) oraz zielone kółko z ptaszkiem ✓ przy poprawnej opcji.
   - Pytania OPEN / MATH / FORM_FIELD: dymek z krótką odpowiedzią i półprzezroczystym tłem (`OverlayColors.BubbleBackground`), pozycjonowany przez czystą klasę `BubblePlacement` (zabezpieczenie przed wyjściem poza ekran oraz przed zasłanianiem przez dolny panel).
   - Stan `Pending`: pulsujący obrys wokół pytania.
   - Stan `Failed`: czerwona ikonka wykrzyknika.
   - Opcjonalne obrysy linii OCR (`showLineOutlines`).

3. **Śledzenie i dopasowanie obiektów (`OverlayTracker.kt`, `TextLocator.kt`, `TextSimilarity.kt`)**:
   - Czysta logika dopasowania pozycji pytań i opcji do najnowszych klatek `OcrFrame`.
   - Normalizacja tekstu (NFD, usuwanie znaków diakrytycznych, małe litery, obsługa 'ł').
   - Obliczanie podobieństwa oparte na współczynniku Dice'a bigramów.
   - Wyszukiwanie jednoliniowe oraz wieloliniowe (łączenie sąsiednich linii tekstu do 4 linii).
   - Wygładzanie ruchu (liniowa interpolacja przy małych przesunięciach, natychmiastowy przeskok przy dużych ruchach).
   - Wygaszanie zaginionych elementów po `holdMs` (400 ms) w czasie `fadeMs` (400 ms).

4. **Panel dolny (`AnswerPanel.kt`)**:
   - `BottomSheetScaffold` ze stałą wysokością zwiniętą (peek height 132 dp).
   - Lista pytań: numer w kółku, skrót treści pytania, pogrubione poprawne opcje, uzasadnienie (gdy włączone `showExplanations`), silnik oraz procentowa pewność odpowiedzi.
   - Kliknięcie pytania na liście podświetla ramkę na nakładce AR.

5. **Elementy sterujące**:
   - Pasek górny: chip statusu (`SCANNING`, `ANALYZING`, `DONE`, `FROZEN`) z nazwą silnika i spinnerem, przycisk latarki, przycisk obrysów OCR, przycisk pauzy/wznowienia, przycisk czyszczenia, przycisk ustawień.

6. **Ekran ustawień (`SettingsScreen.kt`)**:
   - Wybór silnika AI (`SingleChoiceSegmentedButtonRow`: Gemini Nano, OpenCode, Chmura).
   - Przełącznik fallbacku.
   - Konfiguracja OpenCode (URL, login, hasło w polu chronionym z możliwością podglądu, model z podpowiedzią).
   - Konfiguracja Cloud (wybór providera: Anthropic, Gemini, OpenAI, klucz API w polu chronionym, model).
   - Zachowanie (język pl/en, uzasadnienia, slider stabilizacji 200..2000 ms ze skokiem 100 ms).
   - Przycisk „Testuj połączenie” weryfikujący dostępność wybranego silnika przez `AnswerEngine.isAvailable()`.
   - Nawigacja wstecz z obsługą systemowego `BackHandler`.

7. **Zarządzanie stanem i DI (`MainViewModel.kt`, `PaperMindApp.kt`, `UiState.kt`)**:
   - `MainViewModel` reaguje na strumienie `OcrPipeline.pages` i `OcrPipeline.frames`, przekazuje strony do `AnswerService.submit`, agreguje stan w `StateFlow<UiState>`.
   - Ręczne DI w `PaperMindApp.kt` z zestawem fake'ów (`FakeOcrPipeline`, `FakeAnswerService`, `InMemorySettingsRepository`, `FakeEngine`).
   - Miejsca na wpięcie docelowych implementacji oznaczone komentarzami `TODO(integracja)`.

8. **Wygląd i ikony**:
   - Material 3, ciemny motyw, dynamic color na Android 12+ z paletą awaryjną (`PaperMindTheme`).
   - Wektorowe ikony adaptacyjne (`ic_launcher`, `ic_launcher_round`, `ic_launcher_foreground`, `ic_launcher_monochrome`).

9. **Testy jednostkowe JVM (37 testów, wszystkie zielone)**:
   - `TextSimilarityTest`: normalizacja, obsługa polskich znaków, współczynnik Dice'a.
   - `TextLocatorTest`: dopasowanie jednoliniowe, wieloliniowe, dopasowanie z uwzględnieniem bliskości boxa, odrzucenie poniżej progu.
   - `OverlayTrackerTest`: śledzenie pytań i opcji, wygładzanie przesunięć, wygaszanie po zniknięciu z kadru, reset.
   - `BubblePlacementTest`: pozycjonowanie pod/nad boxem, ograniczenia marginesów, uwzględnienie wcięcia panelu dolnego.
   - `AnswerTextTest`: formatowanie odpowiedzi zamkniętych i otwartych, normalizacja etykiet opcji, skracanie treści.
   - `SettingsSnapTest`: zaokrąglanie i ograniczanie wartości slidera stabilności.
   - `MainViewModelTest`: pełny cykl życia stanu (skanowanie -> analiza -> gotowe), zamrożenie, czyszczenie, zaznaczanie pytań, zmiana ustawień, test połączenia.

## Prośby o zmianę kontraktu
- **Brak uwag/próśb**: Wszystkie wspólne kontrakty (`model/Models.kt`, `ocr/Contracts.kt`, `ai/Contracts.kt`, `settings/AppSettings.kt`) okazały się spójne, kompletne i w 100% wystarczające do realizacji wymagań UI.

## Co otwarte / integracja
- Po zakończeniu prac przez agentów OCR i AI wystarczy w `PaperMindApp.kt` odkomentować właściwe instancje:
  - `DataStoreSettingsRepository(context)`
  - `MlKitOcrPipeline(context, settingsRepository.settings)`
  - Silniki `GeminiNanoEngine`, `OpenCodeEngine`, `CloudEngine`
  - `AnswerServiceImpl(engines.values.toList(), settingsRepository, appScope)`

## Ograniczenia
- W trybie bez kamery/fizycznego urządzenia symulacja opiera się na `FakeOcrPipeline` generującym 5 przykładowych pytań i lekkie drżenie symulujące ruch ręki.
