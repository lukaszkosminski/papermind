# PaperMind — podział pracy

Aplikacja Android (S25 Ultra): kamera → OCR (ML Kit, on-device) → wykrycie pytań/opcji →
AI (Gemini Nano on-device / serwer OpenCode na PC / API chmurowe) → nakładka AR + panel z odpowiedziami.

Build: `source ~/Android/env.sh && ./gradlew assembleDebug testDebugUnitTest`
**compileSdk = 37.0 — nie podnoś Compose BOM ponad 2026.09.00** (nowsze wymagają SDK 37.1, którego nie ma).

## Wspólne kontrakty (TYLKO do odczytu — zmiany zgłaszaj kierownikowi)
- `model/Models.kt` — typy domenowe, współrzędne Box = piksele PreviewView
- `ocr/Contracts.kt` — OcrPipeline, QuestionParser
- `ai/Contracts.kt` — AnswerEngine, AnswerService
- `settings/AppSettings.kt` — AppSettings, SettingsRepository

## Własność plików (nie edytuj cudzych pakietów)
| Agent | Pakiet / pliki | Zakres |
|---|---|---|
| A `ocr` | `pl/lk/papermind/ocr/**`, testy w `src/test/.../ocr/` | CameraX MlKitAnalyzer (VIEW_REFERENCED), ML Kit Text Recognition (łaciński, PL), parser pytań (numeracja 1./1)/Zad. 1, opcje A./a)/A), □/○, pola formularza "Imię: ____", wykrywanie MATH), stabilizator klatek (debounce stabilityMs, podobieństwo tekstu), stabilne id pytań |
| B `ai` | `pl/lk/papermind/ai/**`, `pl/lk/papermind/settings/**` (poza AppSettings.kt), testy `src/test/.../ai/` | GeminiNanoEngine (ML Kit GenAI Prompt API `com.google.mlkit:genai-prompt`), OpenCodeEngine (HTTP API `opencode serve`: sesja + prompt, basic auth), CloudEngine (Anthropic/Gemini/OpenAI przez OkHttp), wspólny PromptBuilder + parser odpowiedzi JSON (tolerancyjny), AnswerServiceImpl (cache, fallback, dedup), DataStoreSettingsRepository |
| C `ui` | `pl/lk/papermind/ui/**`, `MainActivity.kt`, `PaperMindApp.kt` (ręczne DI), res/ , AndroidManifest | Compose: uprawnienia kamery, PreviewView + LifecycleCameraController, nakładka Canvas (zielona ramka na poprawnej opcji, dymek z krótką odpowiedzią przy pytaniu otwartym/polu), rozwijany bottom sheet (lista pytań, odpowiedź, uzasadnienie, silnik, pewność), przycisk pauzy/zamrożenia klatki, ekran ustawień (wszystkie pola AppSettings, test połączenia), MainViewModel łączący OcrPipeline→AnswerService |

Do czasu dostarczenia implementacji przez innych agentów używaj fake'ów we własnym pakiecie.
Integracja w `PaperMindApp.kt` (agent C) — konstruktory: `MlKitOcrPipeline(context, settingsFlow)`,
`AnswerServiceImpl(engines, settingsRepository, scope)`, `DataStoreSettingsRepository(context)`.

## Zasady
- Po skończeniu: build + testy muszą przechodzić. Commit tylko swoich plików (`git add <swoje ścieżki>`), wiadomość `feat(ocr|ai|ui): ...`.
- Nie uruchamiaj `git add -A`, nie rób rebase/reset.
- Na koniec dopisz krótką notkę do `notes/<agent>.md`: co zrobione, co otwarte, znane ograniczenia.
