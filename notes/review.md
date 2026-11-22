# Raport z Code Review — PaperMind (gałąź `master`)

Audyt integracyjny repozytorium `/home/lk/projekty/papermind` skupiony na styku modułów `ocr`, `ai`, `ui` oraz problemach ujawniających się na fizycznym urządzeniu **Samsung Galaxy S25 Ultra** (Android 15/16, ekran 19.5:9 120Hz Dynamic AMOLED, hardware Keystore StrongBox, CameraX, ML Kit on-device AICore).

---

## Podsumowanie problemów wg wagi

- **KRYTYCZNY**: 5
- **WAŻNY**: 7
- **DROBNY**: 5
- **ŁĄCZNIE**: 17

---

## Problemy KRYTYCZNE

### 1. Odrzucanie gotowych odpowiedzi AI i brak zapisu w cache przy drżeniu kamery (Race Condition / Generation Drop)
- **Plik i linia**: [`app/src/main/java/pl/lk/papermind/ai/AnswerServiceImpl.kt:131-135`](file:///home/lk/projekty/papermind/app/src/main/java/pl/lk/papermind/ai/AnswerServiceImpl.kt#L131-L135)
- **Waga**: KRYTYCZNY
- **Opis**: W metodzie `publish(gen, id, st, answer)` warunek odrzucenia przestarzałych odpowiedzi:
  ```kotlin
  if (gen != generation) return
  if (answer != null) cache[id] = answer
  if (id in currentIds) state.value = state.value + (id to st)
  ```
  znajduje się **przed** zapisem do pamięci podręcznej `cache[id] = answer`. Podczas trzymania telefonu w rękach naturalne mikroruchy powodują, że stabilizator klatek co kilkaset milisekund emituje kolejną stronę, co w `submit()` inkrementuje licznik `generation` (`++generation`). Gdy długo trwające zapytanie AI (np. Cloud API trwające 1.5s lub Gemini Nano trwające 2s) zakończy się sukcesem, jego wynik trafia do `publish()` ze starym numerem `gen`. W rezultacie odpowiedź jest bezpowrotnie porzucana — nie trafia do UI ani do `cache`. Przy kolejnej klatce te same pytania są ponownie wysyłane do AI od zera. Na telefonie skutkuje to ciągłym mieleniem zapytań, drenażem baterii, zużywaniem tokenów i brakiem jakichkolwiek odpowiedzi na ekranie.
- **Proponowana poprawka**: Zapisywać poprawną odpowiedź do `cache` niezależnie od generacji strony. Jeśli odpowiedź nadeszła dla pytania znajdującego się w bieżącym `currentIds`, zaktualizować stan w UI:
  ```kotlin
  private fun publish(gen: Int, id: String, st: AnswerState, answer: Answer?) = synchronized(lock) {
      if (answer != null) cache[id] = answer
      if (id in currentIds) {
          state.value = state.value + (id to st)
      }
  }
  ```

---

### 2. Nieskończona pętla ponawiania błędnych zapytań (Infinite Retry Storm na błędach sieci/silnika)
- **Plik i linia**: [`app/src/main/java/pl/lk/papermind/ai/AnswerServiceImpl.kt:67-74`](file:///home/lk/projekty/papermind/app/src/main/java/pl/lk/papermind/ai/AnswerServiceImpl.kt#L67-L74)
- **Waga**: KRYTYCZNY
- **Opis**: Gdy silnik AI zwróci błąd (brak sieci, błąd autoryzacji 401, limit zapytań 429), `publish()` emituje dla pytania stan `AnswerState.Failed(msg)`. Stan błędu nie jest jednak zapisywany do żadnego cache'a (cache trzyma tylko udane `Answer`). W rezultacie przy nadejściu kolejnej klatki ze stabilizatora:
  ```kotlin
  val missing = questions.filter { it.id !in cache }
  ...
  state.value = questions.associate { q ->
      q.id to (cache[q.id]?.let { AnswerState.Ready(it) }
          ?: state.value[q.id]?.takeIf { it is AnswerState.Pending && q.id in inFlight }
          ?: AnswerState.Pending)
  }
  ```
  pytanie z błędem natychmiast wraca ze stanu `Failed` do `Pending`, trafia do listy `toSend` i jest ponownie wysyłane do silnika. Gdy telefon nie ma zasięgu lub klucz API jest błędny, aplikacja wpada w niekontrolowaną, gwałtowną pętlę ciągłego ponawiania zapytań kilka razy na sekundę. Użytkownik widzi migający ułamek sekundy błąd, po czym proces zaczyna się od nowa, drenując baterię i generując setki błędnych żądań HTTP.
- **Proponowana poprawka**: Wprowadzić strukturę `failedAttempts: MutableMap<String, FailedEntry>` z licznikiem prób i znacznikiem czasu (cooldown/exponential backoff, np. minimum 10 sekund przed ponowieniem nieudanego pytania) albo ponawiać błędne pytania wyłącznie po manualnym wywołaniu `clear()` lub zmianie ustawień.

---

### 3. Blokowanie wątku głównego (UI Jank / ANR) przez operacje sprzętowego Android Keystore
- **Plik i linia**: [`app/src/main/java/pl/lk/papermind/settings/DataStoreSettingsRepository.kt:28-42`](file:///home/lk/projekty/papermind/app/src/main/java/pl/lk/papermind/settings/DataStoreSettingsRepository.kt#L28-L42), [`app/src/main/java/pl/lk/papermind/settings/KeystoreCipher.kt:14-47`](file:///home/lk/projekty/papermind/app/src/main/java/pl/lk/papermind/settings/KeystoreCipher.kt#L14-L47)
- **Waga**: KRYTYCZNY
- **Opis**: `KeystoreCipher.encrypt()` i `decrypt()` realizują szyfrowanie AES/GCM kluczem z `AndroidKeyStore`. W architekturze Samsunga (Samsung Knox / StrongBox Keymaster) operacja ta wykonuje synchroniczne wywołania Binder IPC do modułu bezpieczeństwa TEE/SE. W `MainViewModel`:
  ```kotlin
  fun updateSettings(transform: (AppSettings) -> AppSettings) {
      viewModelScope.launch { settingsRepository.update(transform) }
      connection.value = ConnectionTest.Idle
  }
  ```
  Wywołanie następuje bezpośrednio w `viewModelScope.launch`, który działa na `Dispatchers.Main.immediate`. Co gorsza, `SettingsScreen` przy każdej zmianie znaku w polu tekstowym (np. wpisywanie hasła OpenCode lub klucza API) wywołuje `onUpdate`, co powoduje wielokrotne generowanie i szyfrowanie klucza w Keystore synchronicznie na wątku UI. Na S25 Ultra prowadzi to do natychmiastowego zacinania interfejsu (jank), gubienia klatek animacji i ryzyka zablokowania wątku głównego (ANR).
- **Proponowana poprawka**: Zapewnić wykonywanie operacji kryptograficznych w `DataStoreSettingsRepository` na `Dispatchers.IO`:
  ```kotlin
  override suspend fun update(transform: (AppSettings) -> AppSettings) = withContext(Dispatchers.IO) {
      store.edit { prefs -> ... }
  }
  ```
  oraz w transformacji `toSettings()` przenieść deszyfrowanie na wątek roboczy lub debouncować aktualizację pól sekretnych w UI.

---

### 4. Całkowite odrzucanie odpowiedzi modeli AI przez brak fallbacku na numer pytania lub indeks w parserze JSON
- **Plik i linia**: [`app/src/main/java/pl/lk/papermind/ai/AnswerJsonParser.kt:23-32`](file:///home/lk/projekty/papermind/app/src/main/java/pl/lk/papermind/ai/AnswerJsonParser.kt#L23-L32)
- **Waga**: KRYTYCZNY
- **Opis**: Identyfikatory pytań `DetectedQuestion.id` to 16-znakowe skróty szesnastkowe SHA-1 (np. `4a1f8e29bc0193ad`). Prompt przekazuje je w postaci `[id=4a1f8e29bc0193ad | type=SINGLE_CHOICE | no=1]`. Mniejsze modele on-device (jak Gemini Nano) oraz szybkie modele chmurowe (np. Haiku, GPT-5-mini) bardzo często ignorują losowy ciąg heksadecymalny i w wyjściowym JSON wstawiają numer pytania: `"id": "1"`, `"id": 1` lub `"id": "q1"`. `AnswerJsonParser` dopasowuje odpowiedzi wyłącznie przez:
  ```kotlin
  val byId = questions.associateBy { it.id.trim().lowercase() }
  ...
  val q = byId[rawId.trim().lowercase()] ?: continue
  ```
  Jeśli model zwróci `"id": "1"`, lookup zwraca `null` i odpowiedź jest bezpowrotnie odrzucana. Ponieważ żadne pytanie nie zostaje dopasowane (`valid.isEmpty()`), silnik zgłasza błąd `brak użytecznej odpowiedzi`, mimo że model poprawnie odpowiedział na pytania.
- **Proponowana poprawka**: Zaimplementować wieloetapowe dopasowywanie (fallback):
  1. Po identyfikatorze `q.id`,
  2. Po numerze pytania `q.number` (np. `"1"`),
  3. Jeśli model zwrócił tablicę odpowiedzi o rozmiarze równym liczbie pytań w zapytaniu — dopasować po kolejności (indeksie).

---

### 5. Zawieszenie mechanizmu fallbacku silników AI przez brak per-engine timeoutu w AnswerServiceImpl
- **Plik i linia**: [`app/src/main/java/pl/lk/papermind/ai/AnswerServiceImpl.kt:107-113`](file:///home/lk/projekty/papermind/app/src/main/java/pl/lk/papermind/ai/AnswerServiceImpl.kt#L107-L113)
- **Waga**: KRYTYCZNY
- **Opis**: W pętli `for (engine in orderedEngines(settings))` wywołanie `val result = engine.answer(...)` nie jest objęte żadnym limitem czasu. Domyślny klient OkHttp dla `OpenCodeEngine` posiada `callTimeout = 150s` i `readTimeout = 120s`. Jeśli serwer OpenCode na komputerze w sieci lokalnej zawiesi się, nie odpowiada lub generuje odpowiedź bardzo wolno, korutyna w `AnswerServiceImpl` zostaje zablokowana na ponad 2 minuty. W tym czasie mechanizm fallbacku na Gemini Nano lub Chmurę w ogóle się nie uruchamia, a użytkownik widzi stan `ANALYZING` w nieskończoność.
- **Proponowana poprawka**: Ograniczyć czas wykonania zapytania do pojedynczego silnika za pomocą `withTimeoutOrNull` (np. 15–20 sekund na silnik):
  ```kotlin
  val result = withTimeoutOrNull(ENGINE_TIMEOUT_MS) {
      engine.answer(page.copy(questions = remaining.values.toList()))
  } ?: throw EngineException("$name: przekroczono czas oczekiwania na odpowiedź")
  ```

---

## Problemy WAŻNE

### 6. Wyciek pamięci i natywnych zasobów: brak zwalniania ML Kit TextRecognizer i GenerativeModel w cyklu życia
- **Plik i linia**: [`app/src/main/java/pl/lk/papermind/PaperMindApp.kt:21-36`](file:///home/lk/projekty/papermind/app/src/main/java/pl/lk/papermind/PaperMindApp.kt#L21-L36), [`app/src/main/java/pl/lk/papermind/ocr/MlKitOcrPipeline.kt:152-157`](file:///home/lk/projekty/papermind/app/src/main/java/pl/lk/papermind/ocr/MlKitOcrPipeline.kt#L152-L157), [`app/src/main/java/pl/lk/papermind/ai/GeminiNanoEngine.kt:37-41`](file:///home/lk/projekty/papermind/app/src/main/java/pl/lk/papermind/ai/GeminiNanoEngine.kt#L37-L41)
- **Waga**: WAŻNY
- **Opis**: `MlKitOcrPipeline` tworzy natywny `TextRecognizer` (`libmlkit_google_ocr_pipeline.so`), a `GeminiNanoEngine` tworzy klienta `GenerativeModel` (AICore). Obie klasy posiadają metody `close()`. W `PaperMindApp` instancje te tworzone są w `AppContainer` o zasięgu całej aplikacji (`Application`), ale żadna część aplikacji (`MainActivity.onDestroy`, `MainViewModel.onCleared`) nigdy nie wywołuje `ocrPipeline.close()` ani nie zamyka modelu GenAI. Ponadto `downloadScope` w `GeminiNanoEngine` nie jest nigdy anulowany. Powoduje to permanentną alokację natywnych buforów i modeli w pamięci RAM telefonu nawet po zamknięciu ekranu skanera.
- **Proponowana poprawka**: Dodać metodę `clear()` / `close()` do `AppContainer` wywoływaną w odpowiednim momencie cyklu życia lub zamknąć `ocrPipeline` w `MainViewModel.onCleared()`.

---

### 7. Niepoprawna kolejność konfiguracji ImageAnalysis: rekonfiguracja ResolutionSelector po powiązaniu z Lifecycle
- **Plik i linia**: [`app/src/main/java/pl/lk/papermind/ui/camera/CameraScreen.kt:174-184`](file:///home/lk/projekty/papermind/app/src/main/java/pl/lk/papermind/ui/camera/CameraScreen.kt#L174-L184), [`app/src/main/java/pl/lk/papermind/ocr/MlKitOcrPipeline.kt:116-117`](file:///home/lk/projekty/papermind/app/src/main/java/pl/lk/papermind/ocr/MlKitOcrPipeline.kt#L116-L117)
- **Waga**: WAŻNY
- **Opis**: W `CameraScreen.kt` efekt `DisposableEffect(controller, lifecycleOwner)` wywołuje `controller.bindToLifecycle(lifecycleOwner)`. Dopiero późniejszy efekt `DisposableEffect(controller, analyzing)` wywołuje `bindCamera()`, które z kolei wywołuje:
  ```kotlin
  controller.setImageAnalysisBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
  controller.setImageAnalysisResolutionSelector(ANALYSIS_RESOLUTION)
  ```
  Wywołanie `setImageAnalysisResolutionSelector` na `CameraController`, który jest już powiązany z cyklem życia kamery, zmusza CameraX do wywołania wewnętrznego `recreateImageAnalysis` i restartu sesji kamery. Przy każdym wznowieniu z pauzy lub otwarciu/zamknięciu ekranu ustawień następuje widoczne mrugnięcie i zacięcie strumienia wideo.
- **Proponowana poprawka**: Skonfigurować `ResolutionSelector` oraz `BackpressureStrategy` raz, w bloku `remember` kontrolera w `CameraScreen.kt`:
  ```kotlin
  val controller = remember {
      LifecycleCameraController(context).apply {
          setEnabledUseCases(CameraController.IMAGE_ANALYSIS)
          setImageAnalysisBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
          setImageAnalysisResolutionSelector(MlKitOcrPipeline.ANALYSIS_RESOLUTION)
          isTapToFocusEnabled = true
          isPinchToZoomEnabled = true
      }
  }
  ```
  A w `MlKitOcrPipeline.bind()` podpinać wyłącznie analizator (`setImageAnalysisAnalyzer`).

---

### 8. Drenaż baterii i przegrzewanie CPU: obliczanie macierzy Levenshteina $O(N \cdot M)$ co 120 ms
- **Plik i linia**: [`app/src/main/java/pl/lk/papermind/ocr/TextNormalizer.kt:55-72`](file:///home/lk/projekty/papermind/app/src/main/java/pl/lk/papermind/ocr/TextNormalizer.kt#L55-L72), [`app/src/main/java/pl/lk/papermind/ocr/FrameStabilizer.kt:61-90`](file:///home/lk/projekty/papermind/app/src/main/java/pl/lk/papermind/ocr/FrameStabilizer.kt#L61-L90)
- **Waga**: WAŻNY
- **Opis**: Klatki z analizatora OCR trafiają do `FrameStabilizer` co ok. 120 ms (~8 klatek/s). Przy każdej klatce wywoływana jest funkcja `TextNormalizer.similarity()`, która uruchamia algorytm odległości Levenshteina na pełnym tekście strony (`squash(a)` i `squash(b)`). Przy tekście arkusza liczącym 1200 znaków daje to $1200 \times 1200 \approx 1.44$ miliona operacji w pętli oraz alokację dwóch tablic `IntArray(1201)` kilka razy na sekundę na wątku roboczym. Na S25 Ultra prowadzi to do niepotrzebnego throttlingu termicznego i zauważalnego zużycia energii.
- **Proponowana poprawka**: Wprowadzić etap wczesnego odrzucenia (short-circuit):
  1. Jeśli `jaccard(tokens(a), tokens(b))` jest `< 0.50` lub `> 0.95`, nie liczyć pełnego Levenshteina (wynik jest deterministycznie przesądzony).
  2. Jeśli obliczenie Levenshteina jest konieczne, ograniczyć je do pasma diagonalnego o szerokości $K$ znaków (układ wierszowy z wczesnym przerwaniem przy przekroczeniu dopuszczalnego progu różnic).

---

### 9. Utrata zamrożonej klatki (czarny ekran) po rotacji telefonu w trybie pauzy
- **Plik i linia**: [`app/src/main/java/pl/lk/papermind/ui/camera/CameraScreen.kt:169`](file:///home/lk/projekty/papermind/app/src/main/java/pl/lk/papermind/ui/camera/CameraScreen.kt#L169), [`app/src/main/java/pl/lk/papermind/ui/camera/CameraScreen.kt:240`](file:///home/lk/projekty/papermind/app/src/main/java/pl/lk/papermind/ui/camera/CameraScreen.kt#L240)
- **Waga**: WAŻNY
- **Opis**: Bitmapa zamrożonej klatki `frozenFrame` jest trzymana w stanie kompozycji `remember { mutableStateOf<ImageBitmap?>(null) }`. Z kolei stan `frozen` jest przechowywany w `MainViewModel` (który przeżywa zmianę konfiguracji). Gdy użytkownik kliknie Pauzę, a następnie obróci telefon (np. aby lepiej widzieć treść zadania w układzie poziomym na ekranie S25 Ultra):
  - `CameraScreen` jest rekomponowany od nowa,
  - `frozenFrame` powraca do wartości `null`,
  - `analyzing` wynosi `false` (bo `state.frozen == true`), więc podgląd kamery jest odpięty.
  Użytkownik widzi czarny ekran z zawieszonymi w próżni ramkami nakładki OCR.
- **Proponowana poprawka**: Przechowywać zrzut zamrożonego podglądu (`frozenBitmap: Bitmap?`) w `MainViewModel` lub przekazywać go do stanu `UiState`, aby przetrwał rotację ekranu.

---

### 10. Crash aplikacji (`IllegalArgumentException`) przy obecności spacji lub znaków nowej linii w kluczach API
- **Plik i linia**: [`app/src/main/java/pl/lk/papermind/ai/CloudEngine.kt:70-105`](file:///home/lk/projekty/papermind/app/src/main/java/pl/lk/papermind/ai/CloudEngine.kt#L70-L105), [`app/src/main/java/pl/lk/papermind/ui/settings/SettingsScreen.kt:183`](file:///home/lk/projekty/papermind/app/src/main/java/pl/lk/papermind/ui/settings/SettingsScreen.kt#L183)
- **Waga**: WAŻNY
- **Opis**: Użytkownicy kopiujący klucze API ze schowka w telefonie często nieświadomie wklejają spacje wiodące/końcowe lub znak nowej linii `\n`. W `SettingsScreen.kt` wartość ta jest zapisywana do `AppSettings` bez wywołania `.trim()`. Podczas budowania żądania w `CloudEngine.buildRequest`:
  ```kotlin
  .header("x-api-key", s.cloudApiKey)
  ```
  OkHttp waliduje nagłówki i przy napotkaniu znaku niedrukowalnego rzuca `IllegalArgumentException`. Ponieważ w `CloudEngine.answer()` przechwytywany jest wyłącznie `IOException`, wyjątek typu `RuntimeException` nie zostaje złapany i powoduje wysypanie całego procesu przetwarzania pytania.
- **Proponowana poprawka**: Wykonywać `.trim()` na wszystkich polach konfiguracyjnych (klucze API, hasła, adresy URL) przed ich użyciem w nagłówkach HTTP oraz przy zapisie w `SettingsScreen`.

---

### 11. Globalne zezwolenie na nieszyfrowany ruch sieciowy (`usesCleartextTraffic="true"`)
- **Plik i linia**: [`app/src/main/AndroidManifest.xml:14-15`](file:///home/lk/projekty/papermind/app/src/main/AndroidManifest.xml#L14-L15)
- **Waga**: WAŻNY
- **Opis**: W `AndroidManifest.xml` ustawiono `android:usesCleartextTraffic="true"` globalnie dla całej aplikacji, aby umożliwić łączenie z serwerem OpenCode po `http://`. Wyłącza to domyślną ochronę Androida przed przesyłaniem danych otwartym tekstem i stwarza ryzyko przechwycenia kluczy API lub danych skanowanych formularzy w publicznych sieciach Wi-Fi w razie błędnej konfiguracji adresu.
- **Proponowana poprawka**: Zastąpić globalną flagę precyzyjną konfiguracją `network_security_config.xml`, zezwalającą na cleartext wyłącznie dla prywatnych zakresów IP (LAN: `192.168.0.0/16`, `10.0.0.0/8`, `172.16.0.0/12`) oraz `localhost`, a dla całego ruchu chmurowego (Anthropic, OpenAI, Google) bezwzględnie wymusić TLS.

---

### 12. Przepełnienie paska górnego (`TopControls`) na standardowych szerokościach ekranów telefonów
- **Plik i linia**: [`app/src/main/java/pl/lk/papermind/ui/camera/CameraScreen.kt:263-284`](file:///home/lk/projekty/papermind/app/src/main/java/pl/lk/papermind/ui/camera/CameraScreen.kt#L263-L284)
- **Waga**: WAŻNY
- **Opis**: `TopControls` układa w jednym poziomym wierszu (`Row`) z odstępem `Arrangement.SpaceBetween`:
  1. `StatusChip` o szerokości ok. 180–210 dp (tekst np. "Gemini Nano · szukam pytań…"),
  2. Pasek z 5 przyciskami `IconButton` (5 × 48 dp = 240 dp).
  Łączna wymagana szerokość wynosi ok. 450 dp. Przy standardowej szerokości okna w trybie pionowym (360–411 dp) przyciski nie mieszczą się w ekranie i przycisk Ustawień zostaje ucięty lub zepchnięty poza prawą krawędź wyświetlacza.
- **Proponowana poprawka**: W `StatusChip` dodać `Modifier.weight(1f, fill = false)` oraz `maxLines = 1, overflow = TextOverflow.Ellipsis`, a rozmiar przycisków w belce zmniejszyć lub zwinąć rzadziej używane opcje (np. obrysy tekstu) do menu.

---

## Problemy DROBNE

### 13. Nadmierny narzut na Garbage Collector przez przeliczanie layoutu tekstu dymków przy 120Hz
- **Plik i linia**: [`app/src/main/java/pl/lk/papermind/ui/overlay/AnswerOverlay.kt:149-155`](file:///home/lk/projekty/papermind/app/src/main/java/pl/lk/papermind/ui/overlay/AnswerOverlay.kt#L149-L155)
- **Waga**: DROBNY
- **Opis**: Podczas animacji pulsowania (`pulseAlpha`) element `Canvas` nakładki przerysowuje się z częstotliwością odświeżania ekranu (120 FPS na S25 Ultra). Wewnątrz `drawBubble` przy każdym przerysowaniu klatki wywoływana jest metoda `measurer.measure(text = text, ...)`. Tworzy to setki obiektów `TextLayoutResult` na sekundę, wywołując częste cykle czyszczenia pamięci (GC pauses).
- **Proponowana poprawka**: Pamiętać (`remember`) zmierzony `TextLayoutResult` dla każdego pytania dopóki nie zmieni się tekst odpowiedzi lub szerokość widoku.

---

### 14. Pozorna dostępność silnika chmurowego w `CloudEngine.isAvailable()`
- **Plik i linia**: [`app/src/main/java/pl/lk/papermind/ai/CloudEngine.kt:36`](file:///home/lk/projekty/papermind/app/src/main/java/pl/lk/papermind/ai/CloudEngine.kt#L36)
- **Waga**: DROBNY
- **Opis**: Implementacja `isAvailable()` w `CloudEngine` ogranicza się wyłącznie do:
  ```kotlin
  override suspend fun isAvailable(): Boolean = settings.first().cloudApiKey.isNotBlank()
  ```
  Przycisk „Testuj połączenie” w ustawieniach zawsze raportuje sukces ("Dostępny ✓"), nawet jeśli telefon jest w trybie samolotowym, klucz API jest niepoprawny lub na koncie brak limitów.
- **Proponowana poprawka**: Wykonać w `isAvailable()` próbne zapytanie sieciowe (np. pobranie listy modeli lub żądanie `HEAD`) z krótkim timeoutem (np. 3s).

---

### 15. Nadmiarowe pule połączeń HTTP przez tworzenie osobnych instancji OkHttpClient
- **Plik i linia**: [`app/src/main/java/pl/lk/papermind/ai/OpenCodeEngine.kt:41`](file:///home/lk/projekty/papermind/app/src/main/java/pl/lk/papermind/ai/OpenCodeEngine.kt#L41), [`app/src/main/java/pl/lk/papermind/ai/CloudEngine.kt:31`](file:///home/lk/projekty/papermind/app/src/main/java/pl/lk/papermind/ai/CloudEngine.kt#L31)
- **Waga**: DROBNY
- **Opis**: Klasy `OpenCodeEngine` i `CloudEngine` tworzą niezależne instancje `OkHttpClient` za pomocą `defaultClient()`. Zgodnie z wytycznymi OkHttp zaleca się współdzielenie jednej instancji klienta w całej aplikacji, co pozwala na ponowne wykorzystanie puli połączeń TCP/TLS i wspólnego dispatchera wątków.
- **Proponowana poprawka**: Wstrzykiwać pojedynczą instancję `OkHttpClient` z poziomu `AppContainer`.

---

### 16. Rysowanie ikony błędu i dymków poza górną krawędzią ekranu
- **Plik i linia**: [`app/src/main/java/pl/lk/papermind/ui/overlay/AnswerOverlay.kt:139-140`](file:///home/lk/projekty/papermind/app/src/main/java/pl/lk/papermind/ui/overlay/AnswerOverlay.kt#L139-L140)
- **Waga**: DROBNY
- **Opis**: W `drawFailedIcon` środek okręgu liczony jest jako:
  ```kotlin
  val c = Offset(box.right.coerceAtMost(size.width - r - 4 * density), box.top - r * 0.2f)
  ```
  Gdy wykryte pytanie znajduje się przy samej górnej krawędzi kadru (np. `box.top < 10px`), ikona wykrzyknika jest obcinana lub chowa się pod wycięciem aparatu (display cutout) i paskiem stanu.
- **Proponowana poprawka**: Dodać ograniczenie dolne dla osi Y: `c.y = (box.top - r * 0.2f).coerceAtLeast(r + 4 * density)`.

---

### 17. Podatność identyfikatorów pytań na mikroszum OCR
- **Plik i linia**: [`app/src/main/java/pl/lk/papermind/ocr/QuestionId.kt:8-16`](file:///home/lk/projekty/papermind/app/src/main/java/pl/lk/papermind/ocr/QuestionId.kt#L8-L16)
- **Waga**: DROBNY
- **Opis**: Funkcja `QuestionId.of` generuje 8-bajtowy hash SHA-1 ze znormalizowanego tekstu pytania. Ze względu na efekt lawinowy funkcji kryptograficznych, podmiana choćby jednego znaku przez OCR między klatkami (np. odczyt litery "o" jako "0" lub "c") generuje całkowicie inny identyfikator. Pytanie traci wtedy powiązanie z odpowiedzią w pamięci podręcznej i jest traktowane jako nowo wykryte.
- **Proponowana poprawka**: W przyszłych iteracjach rozważyć lokalnie czuły algorytm haszowania (np. MinHash / SimHash) lub opierać ID o znormalizowaną listę najdłuższych słów kluczowych.
