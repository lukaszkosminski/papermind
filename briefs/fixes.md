# Runda poprawek po review (`notes/review.md`)

Zasady jak w `briefs/COMMON.md` (własny worktree, tylko swoje pakiety, build+testy zielone, commit `fix(<rola>): ...`).
Do każdej poprawki logiki dodaj test regresyjny. Na końcu dopisz do `notes/<rola>.md` sekcję „Runda poprawek”.

## ai (pi / Claude Sonnet) — worktree /home/lk/projekty/papermind-wt/ai
- #1 KRYTYCZNY: `publish()` — zapisuj udaną odpowiedź do cache niezależnie od generacji; UI aktualizuj, gdy id jest w bieżącej stronie.
  Nie anuluj zapytania w locie, jeśli nowa strona zawiera te same pytania (drgania ręki generują nowe strony).
- #2 KRYTYCZNY: brak pętli ponowień — `Failed` pamiętane per id z backoffem (np. 10 s → 20 s → 60 s), reset przy `clear()` i zmianie ustawień.
- #3 KRYTYCZNY: Keystore/DataStore poza main (`withContext(Dispatchers.IO)` w update i mapowaniu odczytu).
- #4 KRYTYCZNY: `AnswerJsonParser` — dopasowanie fallbackowe: id → numer pytania (`"1"`, `1`, `"q1"`) → kolejność, gdy liczba odpowiedzi = liczbie pytań.
  Rozważ w prompcie krótkie aliasy `Q1..Qn` zamiast hashy i mapowanie z powrotem.
- #5 KRYTYCZNY: per-engine timeout w AnswerServiceImpl (`withTimeout`, domyślnie Nano 20 s, OpenCode 45 s, Cloud 30 s), potem fallback.
- #10: `trim()` kluczy/haseł/URL przy użyciu w silnikach; łap wszystkie wyjątki (nie tylko IOException) → `Failed` z czytelnym komunikatem.
- #14: `CloudEngine.isAvailable()` — lekkie zapytanie (lista modeli) z timeoutem 5 s.
- #15: jeden współdzielony `OkHttpClient` (parametr konstruktora już jest — dodaj `Http.sharedClient`).
- #17 (część AI): rozmyty cache — jeśli nowe pytanie nie ma trafienia po id, poszukaj w cache pytania o podobnej treści
  (np. Dice bigramów ≥ 0.85 + te same etykiety opcji) i użyj jego odpowiedzi. Trzymaj w cache także tekst pytania.
- Sprawdź aktualne domyślne modele Gemini/OpenAI (dziś listopad 2026) i zaktualizuj, jeśli `gemini-2.5-flash` / `gpt-5-mini` są przestarzałe.

## ocr (OpenCode / DeepSeek) — worktree /home/lk/projekty/papermind-wt/ocr
- #7 (część OCR): `bind()` ustawia TYLKO analyzer. Wystaw publiczną stałą `MlKitOcrPipeline.ANALYSIS_RESOLUTION` (+ ewentualnie funkcję
  `configureController(controller)`), żeby UI skonfigurowało resolution/backpressure raz, przy tworzeniu kontrolera.
- #8: wydajność `similarity()` — szybkie odrzucenie na Jaccardzie, Levenshtein ograniczony pasmem z wczesnym przerwaniem; test wydajności
  (strona ~1500 znaków < 2 ms na JVM).
- #17 (część OCR): `QuestionId` bardziej odporny na szum OCR — normalizuj mylone znaki (0/o, 1/l/i, 5/s, 8/b, rn/m), usuwaj diakrytyki,
  licz hash z treści pytania (bez opcji) + liczby opcji; test: dwa warianty z typowymi pomyłkami OCR → to samo id.
- `reset()` i `close()` są już w kontrakcie (dodane przez kierownika) — sprawdź, czy implementacja jest poprawna wątkowo.

## ui (agy / Gemini) — worktree /home/lk/projekty/papermind-wt/ui
- #6: cykl życia — `ocrPipeline.close()` nie w onCleared ViewModelu (pipeline jest singletonem aplikacji), ale `unbind` przy
  ON_STOP i ponowny `bind` przy ON_START; sprawdź, że nie ma podwójnego bindowania.
- #7 (część UI): konfiguracja `LifecycleCameraController` (backpressure, `MlKitOcrPipeline.ANALYSIS_RESOLUTION`) raz w `remember {}`.
  Uwaga: stałą dodaje agent ocr — do czasu merge'a zdefiniuj lokalnie identyczny ResolutionSelector (1920x1080, FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER) z TODO.
- #9: zamrożona klatka przeżywa rotację (trzymaj Bitmap w ViewModelu).
- #11: `network_security_config.xml` — cleartext tylko dla localhost, 10.0.0.0/8, 172.16.0.0/12, 192.168.0.0/16 i domeny Tailscale `*.ts.net`
  (uwaga: NSC nie obsługuje zakresów IP — użyj `<base-config cleartextTrafficPermitted="true">` tylko jeśli nie ma innej drogi;
  sprawdź dokumentację i wybierz najbezpieczniejsze działające rozwiązanie, opisz kompromis w notatce).
- #12: TopControls mieści się na 360 dp (chip z weight + ellipsis, rzadkie akcje w menu overflow).
- #13: cache `TextLayoutResult` dymków.
- #16: ikona błędu i dymki nie wychodzą poza górną krawędź/wycięcie (uwzględnij WindowInsets).
- #10 (część UI): `trim()` przy zapisie pól klucza/hasła/URL w ustawieniach.
