# Wspólne zasady dla agentów PaperMind

Budujemy aplikację Android **PaperMind** (Kotlin, Compose) na Samsung S25 Ultra: kamera patrzy na test/formularz,
OCR (ML Kit on-device) wyciąga pytania, AI daje odpowiedzi, UI pokazuje je w czasie rzeczywistym jako nakładkę AR + panel.

- Pracujesz WYŁĄCZNIE w swoim git worktree (ścieżka w Twoim briefie, gałąź `agent/<rola>`). Zrób `cd` do niego na starcie.
- NAJPIERW przeczytaj `TEAM.md` i pliki kontraktów: `app/src/main/java/pl/lk/papermind/model/Models.kt`, `ocr/Contracts.kt`,
  `ai/Contracts.kt`, `settings/AppSettings.kt`. Kontraktów NIE zmieniaj — potrzebne zmiany opisz w `notes/<rola>.md`
  w sekcji „Prośby o zmianę kontraktu” i obejdź lokalnie.
- Edytuj tylko swoje pakiety (tabela w TEAM.md).
- W worktree mogą już leżeć NIEZACOMMITOWANE pliki od poprzedniego agenta (przerwana praca) — przejrzyj je, wykorzystaj lub popraw.
- Build i testy: `source ~/Android/env.sh && ./gradlew assembleDebug testDebugUnitTest`
  (NIE commituj `gradle.properties`; NIE podnoś Compose BOM — compileSdk 37.0).
- API bibliotek sprawdzaj w dokumentacji online (dziś jest listopad 2026, API mogą być nowsze niż Twoja wiedza);
  możesz podejrzeć klasy w `~/.gradle/caches` (unzip/javap).
- Kod produkcyjny + testy jednostkowe JVM dla logiki.
- Koniec: build + testy zielone, `git add <tylko swoje ścieżki>` + `git commit -m "feat(<rola>): ..."`
  (bez `git add -A`, bez rebase/reset), notatka `notes/<rola>.md` (co zrobione / otwarte / ograniczenia).
- Pracuj autonomicznie do końca, nie pytaj o potwierdzenia. Na końcu napisz jedno zdanie: `GOTOWE: <podsumowanie>`.
