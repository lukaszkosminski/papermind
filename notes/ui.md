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

## Runda poprawek

Wykonano wszystkie punkty przypisane do roli `ui` z `briefs/fixes.md` na podstawie uwag z `notes/review.md`:

1. **#6 (Cykl życia)**: W `CameraScreen.kt` zsynchronizowano wiązanie analizy kamery z cyklem życia (`ON_START` / `ON_STOP`). `LifecycleEventEffect` ustawia flagę `isStarted`, co gwarantuje odpięcie analizy (`unbindCamera`) przy przejściu aplikacji w tło i ponowne podpięcie przy powrocie, zapobiegając wyciekom i podwójnemu wiązaniu analizatora.
2. **#7 (CameraX controller)**: W `CameraScreen.kt` skonfigurowano jednorazowo `LifecycleCameraController` wewnątrz `remember`:
   - `imageAnalysisBackpressureStrategy = ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST`
   - `imageAnalysisResolutionSelector = ANALYSIS_RESOLUTION` (1080p 16:9 z zachowaniem proporcji; oznaczono TODO do docelowej synchronizacji z rozdzielczością OcrPipeline).
3. **#9 (Frozen bitmap across rotation)**: W `UiState.kt` dodano pole `val frozenBitmap: Bitmap?`. `MainViewModel.toggleFreeze(bitmap)` zapisuje klatkę podglądu z `previewView?.bitmap` w stanie `UiState`, a `clear()` ją zeruje. Dzięki temu rotacja ekranu i rekompozycja nie powodują utraty zamrożonego obrazu.
4. **#11 (Network Security Config)**: Usunięto globalne `android:usesCleartextTraffic="true"` z `AndroidManifest.xml` i dodano dedykowaną konfigurację `res/xml/network_security_config.xml`:
   - Zablokowano nieszyfrowany ruch (`cleartextTrafficPermitted="false"`) dla zewnętrznych domen AI: `anthropic.com`, `googleapis.com`, `openai.com`.
   - Zezwolono na cleartext dla `localhost`, `127.0.0.1`, `10.0.2.2`, `*.ts.net`.
   - Zezwolono w `base-config` na połączenia nieszyfrowane z prywatnymi adresami IP serwera OpenCode w sieci lokalnej (LAN), z uwagi na brak obsługi masek CIDR w Android Network Security Config.
5. **#12 (TopControls na 360 dp)**:
   - `StatusChip` otrzymał `Modifier.weight(1f, fill = false)` oraz `maxLines = 1` z `TextOverflow.Ellipsis`.
   - Rzadziej używane akcje (obrysy tekstu OCR oraz czyszczenie odpowiedzi) przeniesiono do menu overflow (`DropdownMenu` pod ikoną `MoreVert`). Na belce pozostały przyciski: latarka, pauza/wznowienie, ustawienia i menu więcej.
6. **#13 (Cache TextLayoutResult dymków)**: W `AnswerOverlay.kt` dodano `layoutCache = remember { HashMap<String, TextLayoutResult>() }` kluczowany po szerokości i tekście. Tekst mierzony jest z bazowym kolorem, a pulsowanie i przezroczystości aplikowane są jako parametr `alpha` w `drawText`. Wyeliminowano re-measure tekstu przy każdej ze 120 klatek na sekundę.
7. **#16 (Zabezpieczenie górnej krawędzi)**: W `BubblePlacement.kt` dodano parametr `safeTop: Float = 0f`. `AnswerOverlay` pobiera wysokość paska statusu przez `WindowInsets.statusBars` i przekazuje jako `topInsetPx`. Dymki odpowiedzi oraz ikona błędu w `AnswerOverlay` są ograniczane, aby nie wchodziły pod pasek stanu ani wycięcie aparatu.
8. **#10 (trim w ustawieniach)**: W `SettingsScreen.kt` dodano `.trim()` przy zapisie `openCodeUrl`, `openCodeUser`, `openCodePassword`, `openCodeModel`, `cloudApiKey`, `cloudModel`, co eliminuje przypadkowe białe znaki wklejone z kluczem lub adresem URL.

### Testy regresyjne:
- `BubblePlacementTest.place_respectsSafeTopInset`: weryfikacja, że dymek przy górnej krawędzi jest pozycjonowany poniżej `safeTop + margin`.
- `MainViewModelTest.freeze_managesFrozenBitmap_andClearsOnClear`: weryfikacja przechowywania bitmapy w `UiState.frozenBitmap` podczas wstrzymania oraz jej czyszczenia przy wznowieniu i `clear()`.
- `SettingsSnapTest.textInputs_trimmedProperly`: weryfikacja trimowania wartości tekstowych.
- Pełny zestaw testów jednostkowych projektu: zielony (`./gradlew assembleDebug testDebugUnitTest` zakończony sukcesem).

## Krzyżówki

Wdrożono pełną obsługę rozwiązywania krzyżówek dla roli `ui`:

1. **Nakładka AR i siatka (`AnswerOverlay.kt`, `CrosswordLetters.kt`, `GridTracker.kt`)**:
   - Gdy na stronie wykryto siatkę (`page.grid != null`), nakładka rysuje litery w komórkach: wyśrodkowane, rozmiar ~60% wysokości komórki, kontrastowy granatowy kolor z białym rozmyciem/cieniem (zapewniającym czytelność na papierze i druku gazetowym), z pomijaniem komórek zablokowanych (`blocked`).
   - Implementacja `crosswordLetters(...)` mapująca litery wyrazów z odpowiedzi do komórek siatki; w przypadku skrzyżowań wyrazów z różnymi literami wygrywa litera z odpowiedzi o wyższym `confidence` (oznaczono TODO do zamiany na implementację z pakietu `ai` po merge).
   - Śledzenie ruchu kamery: `GridTracker` śledzi przesunięcie pozycji siatki w kolejnych klatkach `OcrFrame` na podstawie dopasowania linii z numerkami komórek wewnątrz siatki, stosuje liniowe wygładzanie przesunięć i przelicza pozycje komórek/slotów (lub opiera się na `grid.box` ze stabilizowanej strony).
   - Sloty w stanie `Pending` otrzymują przerywany obrys pulsacyjny (`PathEffect.dashPathEffect`).
   - Wybrane hasło podświetla powiązany slot w siatce (wypełnienie i ramka w kolorze `OverlayColors.Selected`).

2. **Dymki przy hasłach bez siatki w kadrze (`AnswerText.kt`, `AnswerOverlay.kt`)**:
   - Gdy siatka nie jest w kadrze (`page.grid == null`), a wykryto treść haseł na kartce, odpowiedzi `CROSSWORD_CLUE` renderowane są w dymkach nakładki z formatem: wyraz WIELKIMI literami + poprawna odmiana liczby liter, np. `"BAT (3 litery)"`, `"PARYŻ (5 liter)"`.
   - Funkcja `AnswerText.letterCountSuffix(n)` obsługuje reguły języka polskiego (1 litera, 2-4 litery, 5 liter).

3. **Panel dolny — sekcja „Krzyżówka” (`AnswerPanel.kt`)**:
   - Pytania typu `QuestionType.CROSSWORD_CLUE` są grupowane w sekcję „Krzyżówka” z podziałem na nagłówki „Poziomo” i „Pionowo”.
   - Każdy wiersz hasła prezentuje numer w kółku, skróconą treść hasła, a po otrzymaniu odpowiedzi — wyraz złożony fontem `FontFamily.Monospace` z odstępami między literami (np. `B  A  T`), liczbę liter i metadane silnika.
   - Tapnięcie na wiersz hasła (`onSelect`) zaznacza je w stanie i natychmiast podświetla odpowiadający mu slot w siatce na nakładce AR.

4. **Przycisk/tryb „Krzyżówka” (`CameraScreen.kt`, `MainViewModel.kt`, `res/values/strings.xml`)**:
   - Dodano przycisk trybu krzyżówki w `TopControls` z ikoną `GridOn` / `GridOff`.
   - Włączenie trybu zwiększa czas stabilizacji x2 (`settings.stabilityMs * 2`), dając użytkownikowi więcej czasu na stabilne utrzymanie kadru nad dużą stroną z krzyżówką, a po wyłączeniu przywraca pierwotną wartość.
   - Wyświetla animowaną wskazówkę w UI: „Zmieść siatkę i hasła w kadrze; trzymaj nieruchomo”.

5. **Fake'i (`FakeOcrPipeline.kt`, `FakeAnswerService.kt`)**:
   - `FakeOcrPipeline` generuje kompletną, w 100% poprawną semantycznie siatkę 5x5 ze skrzyżowaniami słów (BAT, LOKAL, TAK / BALET, TEKST, LALEK), polami czarnymi, numerowanymi komórkami oraz hasłami POZIOMO i PIONOWO, dołączając `grid` do emitowanego `ParsedPage`.
   - `FakeAnswerService` symuluje natychmiastowe poprawne odpowiedzi dla przykładowych haseł krzyżówki.

6. **Testy jednostkowe JVM**:
   - `CrosswordLettersTest`: mapowanie liter na komórki siatki, rozstrzyganie konfliktów na skrzyżowaniach na korzyść wyższego confidence, ignorowanie stanów pending/failed, obsługa odpowiedzi krótszych niż slot.
   - `GridTrackerTest`: poprawne przeliczanie przesunięcia siatki na podstawie klatek z numerkami, wygładzanie, fallback przy braku dopasowań, resetowanie stanu.
   - `AnswerTextTest`: odmiana liczby liter (`letterCountSuffix`) oraz formatowanie podsumowania dla haseł krzyżówki.
   - `MainViewModelTest`: test podwajania i przywracania `stabilityMs` w trybie krzyżówki, test integracji siatki i liter w `UiState` przy emisji strony z krzyżówką.
