# PaperMind

Aplikacja Android (testowana pod Samsung Galaxy S25 Ultra), która w czasie rzeczywistym czyta kartkę przez kamerę
i pokazuje podpowiedzi odpowiedzi jako nakładkę AR + panel z uzasadnieniami.

```
CameraX ──► ML Kit Text Recognition (on-device OCR) ──► stabilizator klatek ──► parser pytań
                                                                                   │
            nakładka AR + panel (Compose) ◄── AnswerService (cache, fallback) ◄────┘
                                                   │
                         ┌─────────────────────────┼──────────────────────────┐
                   Gemini Nano (on-device,   OpenCode server na PC     API w chmurze
                   ML Kit GenAI Prompt API)  (`opencode serve`)        (Anthropic / Gemini / OpenAI)
```

Obsługiwane typy: jednokrotny/wielokrotny wybór, pytania otwarte, pola formularzy, zadania matematyczne, **krzyżówki** (wykrywanie siatki, hasła Poziomo/Pionowo, litery wpisywane w kratki na nakładce, solver skrzyżowań).

## Pobierz APK

**[⬇ PaperMind-0.1.3-debug.apk](https://github.com/lukaszkosminski/papermind/releases/download/v0.1.3/PaperMind-0.1.3-debug.apk)** — wszystkie wersje: [Releases](https://github.com/lukaszkosminski/papermind/releases)

## Build

```bash
source ~/Android/env.sh            # JDK 21 + Android SDK (compileSdk 37)
./gradlew assembleDebug testDebugUnitTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Silniki AI

| Silnik | Wymagania |
|---|---|
| Gemini Nano | Urządzenie z AICore (S25 Ultra ✔). Model pobiera się przy pierwszym użyciu. Offline. |
| OpenCode | Na PC: `OPENCODE_SERVER_PASSWORD=... opencode serve --hostname 0.0.0.0 --port 4096`; w aplikacji URL `http://<IP-PC>:4096` (LAN / Tailscale). |
| Chmura | Klucz API w ustawieniach (szyfrowany Android Keystore). |

Gdy wybrany silnik jest niedostępny, aplikacja (opcjonalnie) przechodzi na kolejny.

## Struktura

- `model/` — wspólne typy, `ocr/` — kamera, OCR, parser, `ai/` — silniki i serwis odpowiedzi,
  `settings/` — DataStore, `ui/` — Compose (kamera, nakładka, panel, ustawienia).
- `TEAM.md`, `briefs/`, `notes/` — podział pracy i notatki agentów, którzy budowali projekt.

> Narzędzie do nauki i samosprawdzania. Nie używaj go tam, gdzie regulamin (np. egzaminu) zabrania pomocy.
