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
