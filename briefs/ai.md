# Agent B — rola `ai` — worktree: /home/lk/projekty/papermind-wt/ai

Pakiety `pl.lk.papermind.ai` i `pl.lk.papermind.settings` (poza `AppSettings.kt`) + testy w `app/src/test/java/pl/lk/papermind/ai/`.

1. `DataStoreSettingsRepository(context) : SettingsRepository` — Preferences DataStore; klucz API i hasło szyfrowane
   Android Keystore AES/GCM (nie EncryptedSharedPreferences — deprecated).
2. `PromptBuilder` — jeden prompt na całą `ParsedPage`: system prompt (ekspert, język pytania/ustawień, zwięźle), lista pytań
   (id, typ, treść, opcje) + surowy tekst strony jako kontekst (OCR ma błędy — model ma je korygować).
   Wynik: STRICT JSON `{"answers":[{"id":"...","options":["B"],"text":"krótko, do 12 słów","explanation":"1-2 zdania","confidence":0.0-1.0}]}`.
   MATH: text = wynik, explanation = kluczowe kroki. FORM_FIELD: co wpisać / format.
   `AnswerJsonParser` — tolerancyjny (JSON z bloków markdown, ucinanie śmieci, ignorowanie nieznanych id, clamp confidence).
3. `GeminiNanoEngine(context)` — ML Kit GenAI Prompt API (`com.google.mlkit:genai-prompt:1.0.0-beta5` jest w libs.versions.toml —
   sprawdź aktualne API: developers.google.com/ml-kit/genai oraz javap na AAR w ~/.gradle/caches: `Generation.getClient()`,
   `checkStatus()`, `download()`, `generateContent`, `FeatureStatus`). `isAvailable()` = AVAILABLE (DOWNLOADABLE → wyzwól pobieranie, zwróć false).
   Uwzględnij limit kontekstu Nano (skracaj rawText); słabsze trzymanie JSON — fallback parsowania.
4. `OpenCodeEngine(settings: Flow<AppSettings>)` — klient HTTP `opencode serve` (aktualna dokumentacja: opencode.ai/docs/server —
   `POST /session`, `POST /session/{id}/message` z `parts:[{type:"text",text}]` i opcjonalnym `model:{providerID,modelID}`;
   health do `isAvailable`; basic auth gdy hasło). Lokalnie jest `~/.opencode/bin/opencode` — możesz uruchomić
   `opencode serve --port 4097 --hostname 127.0.0.1` w tle, zweryfikować endpointy curlem, potem zabić TYLKO ten proces.
   Jedna sesja wielokrotnego użytku, timeouty, anulowanie.
5. `CloudEngine(settings: Flow<AppSettings>)` — OkHttp: Anthropic Messages API (domyślnie `claude-haiku-5-5`), Gemini API
   (generateContent, `responseMimeType: application/json`), OpenAI (JSON mode). Sprawdź aktualne endpointy/nagłówki.
6. `AnswerServiceImpl(engines: List<AnswerEngine>, settingsRepository: SettingsRepository, scope: CoroutineScope) : AnswerService` —
   kolejność wg ustawień, fallback przy niedostępności/błędzie, cache `Map<questionId, Answer>` (wysyłaj tylko nowe pytania),
   `Pending` od razu, anulowanie poprzedniego zapytania przy nowej innej stronie, `Failed` z czytelnym komunikatem.
   Strona bez wykrytych pytań, ale z tekstem → cały tekst jako jedno pytanie OPEN z id `page`.
7. Testy: parser JSON, PromptBuilder, AnswerServiceImpl z fake engine (coroutines-test), OpenCode/Cloud z MockWebServer.
