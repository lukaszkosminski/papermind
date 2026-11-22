# Notatka: agent `ai`

## Zrobione
- `settings/`: `DataStoreSettingsRepository(context)` (Preferences DataStore), `KeystoreCipher` (AES/GCM, Android Keystore; szyfrowane: klucz API chmury i hasło OpenCode; przy utracie klucza odczyt zwraca "").
- `ai/PromptBuilder` (+ tryb `compact`, limit `maxRawChars`), `ai/AnswerJsonParser` (tolerancyjny: fence markdown, śmieci, ucięty JSON, nieznane id, clamp confidence, dopasowanie etykiet).
- `GeminiNanoEngine(context, settings: Flow<AppSettings>? = null)` — ML Kit GenAI Prompt API (`Generation.getClient()`, `checkStatus`, `download`, `generateContent`); DOWNLOADABLE → start pobierania + `false`; budżet rawText wg `getTokenLimit()`.
- `OpenCodeEngine(settings)` — jedna sesja wielokrotnego użytku (odnawiana co 20 użyć, przy zmianie ustawień, błędzie i anulowaniu), basic auth gdy hasło.
- `CloudEngine(settings)` — Anthropic (domyślnie `claude-haiku-5-5`), Gemini (`gemini-2.5-flash`, `responseMimeType: application/json`), OpenAI (`gpt-5-mini`, JSON mode).
- `AnswerServiceImpl(engines, settingsRepository, scope)` — kolejność wg ustawień + fallback, cache po id pytania (wysyłane tylko nowe), `Pending` od razu, anulowanie przy innej stronie, `Failed` z komunikatem, strona bez pytań → pytanie OPEN `page`.
- Testy JVM: parser, prompt, serwis (fake engine), OpenCode i Cloud (MockWebServer) — 51 testów zielone.

## Ważne odkrycie: opencode v2 ma INNE API HTTP
Lokalny `opencode v2.3.2` NIE ma już `POST /session` (405). Zweryfikowane curlem:
`GET /api/info`, `POST /api/session` (`{title, model:{id,providerID}}` → `{data:{id}}`), `POST /api/session/{id}/prompt` (`{text}`, asynchroniczne),
`POST /api/experimental/session/{id}/wait` (204 gdy idle), `GET /api/session/{id}/message?order=desc&limit=10` (element `type:"assistant"`, `content[].type=="text"`).
Hasło: env `OPENCODE_SERVER_PASSWORD` (+ `OPENCODE_SERVER_USERNAME`), basic auth. `OpenCodeEngine` wykrywa wariant (`/api/info` JSON → V2, inaczej `/global/health` → klasyczne
`POST /session` + `POST /session/{id}/message`) — oba pokryte testami; V2 przetestowany ręcznie na żywym serwerze (dane z curla), sam klient Kotlin tylko na MockWebServer.
Endpoint `/api/experimental/...` może się zmienić.

## Otwarte / ograniczenia
- Gemini Nano nie przetestowany na urządzeniu (brak sprzętu w środowisku); parsowanie JSON ma fallback, ale słabszy model może zwracać niepełne odpowiedzi → serwis przekazuje brakujące pytania kolejnemu silnikowi.
- `DataStoreSettingsRepository`/`KeystoreCipher` bez testów JVM (wymagają Androida/Keystore).
- Domyślne modele Gemini/OpenAI (`gemini-2.5-flash`, `gpt-5-mini`) nie zostały zweryfikowane online — użytkownik może je zmienić w `cloudModel`.
- `AnswerService.answers` zawiera stany pytań tylko bieżącej strony (cache Ready trzyma wszystkie wcześniejsze).
- Nieudane (`Failed`) pytania są ponawiane przy kolejnym `submit` tej samej strony.

## Prośby o zmianę kontraktu
- Brak (konstruktor `GeminiNanoEngine` ma opcjonalny 2. parametr `settings` dla języka; `GeminiNanoEngine(context)` działa).

## Runda poprawek (review)
- **#1** `AnswerServiceImpl`: wynik zapytania zawsze trafia do cache (generacje zastąpione `epoch` — zmienia się tylko przy `clear()`); do UI idzie, gdy id jest na bieżącej stronie. Zapytanie w locie jest anulowane tylko, gdy nowa strona nie ma żadnego jego pytania; pytania już w locie nie są wysyłane ponownie.
- **#2** Backoff `Failed` per id: 10 s → 20 s → 60 s (`RETRY_BACKOFF_MS`); w oknie backoffu stan zostaje `Failed` (nie miga na `Pending`). Reset przy `clear()` i zmianie ustawień (kolektor `settings` w `init`, więc `scope` serwisu musi być zwykłym scope aplikacji; w testach NIE `backgroundScope` — `advanceUntilIdle` pomija zadania w tle). Konstruktor ma opcjonalne `timeouts` i `clock` (testy).
- **#3** `DataStoreSettingsRepository`: `update` w `withContext(io)`, `settings` z `flowOn(io)` (io = `Dispatchers.IO`, wstrzykiwalne); `KeystoreCipher` cache'uje `SecretKey`. Test JVM z in-memory DataStore i nagrywającym codekiem (wątek, brak jawnych sekretów).
- **#4** Prompt używa aliasów `Q1..Qn` zamiast hashy. Parser: id → alias `Q<n>` → numer pytania (`"1"`, `"Zad. 1"`, `"2)"`) → gołe `n` jako indeks → kolejność (tylko gdy liczba wpisów = liczbie pytań **i** żaden wpis nie trafił wcześniej — inaczej ryzyko złego przypisania).
- **#5** Limit czasu na silnik w serwisie: Nano 20 s, OpenCode 45 s, Cloud 30 s (+ 8 s na `isAvailable`), potem fallback; komunikat „przekroczono czas oczekiwania”.
- **#10** `trim()` klucza/modelu/URL/loginu/hasła w silnikach; wszystkie wyjątki (też `IllegalArgumentException` z OkHttp) → `EngineException` z czytelnym komunikatem. Trim przy zapisie w UI — po stronie agenta ui.
- **#14** `CloudEngine.isAvailable()`: GET listy modeli (Anthropic `/v1/models`, Gemini `/v1beta/models`, OpenAI `/v1/models`), timeout 5 s; 2xx lub 429 = dostępny; sukces cache'owany 60 s.
- **#15** `Http.sharedClient`; `defaultClient()` obu silników to `sharedClient.newBuilder()` (wspólna pula połączeń i dispatcher) — bez zmian w `PaperMindApp`.
- **#17 (AI)** Rozmyty cache: brak trafienia po id → szukanie wpisu o tych samych etykietach opcji, tej samej sekwencji cyfr i Dice(bigramy) ≥ 0.85; w cache trzymany tekst pytania. Warunek cyfr chroni przed zlaniem „12+7” z „12+8”.
- **Modele domyślne** (sprawdzone online, listopad 2026): `gpt-5-mini` jest **deprecated** → `gpt-6-luna` (najtańszy/efektywny); `gemini-2.5-flash` → `gemini-3.8-flash` (stabilny Flash; `gemini-4-*` to nowe, droższe modele). Anthropic `claude-haiku-5-5` aktualny.
- Ograniczenie: fuzzy cache nie pomaga zapytaniu już w locie (zmiana id przez szum OCR anuluje je i wysyła nowe); szum OCR bardziej odpornie rozwiązuje agent ocr (`QuestionId`).
