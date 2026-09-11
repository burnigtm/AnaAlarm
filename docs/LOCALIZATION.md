# Localization

AnaAlarm ships with **English** (default) and **Brazilian Portuguese** (pt-BR). This document explains the four localization layers and how to add a new language.

## 1. The four layers

Language affects more than strings in this app — a "language" is a full voice profile:

```
┌─────────────────────────────────────────────────────────────┐
│ 1. UI strings       res/values/ (en)  +  res/values-pt-rBR/ │
│ 2. TTS voice        TtsManager.setLanguage("pt"|"en")       │
│ 3. Speech input     SpeechRecognizer EXTRA_LANGUAGE         │
│ 4. AI language      PromptBuilder instruction to the model  │
└─────────────────────────────────────────────────────────────┘
```

| Layer | Mechanism | Language key |
|---|---|---|
| UI strings | Android resource qualifiers (`values-pt-rBR`) | `pt-BR` (Android picks automatically) |
| TTS output | `TextToSpeech.setLanguage(Locale("pt","BR"))` / `Locale.US` | `pt` → pt-BR voice, else en-US |
| Speech recognition | `RecognizerIntent.EXTRA_LANGUAGE = "pt-BR"` / `"en-US"` | `pt` → pt-BR, else en-US |
| AI replies | `PromptBuilder` inserts *"Always reply in Portuguese (Brazil) / English"* | `pt` → PT prompt, else EN |

The single source of truth is the `language` preference in `SettingsStore` (`"en"` or `"pt"`), set in the Settings screen.

## 2. Settings screen language picker

The Settings screen shows two `FilterChip`s:

- **English** (`language = "en"`)
- **Português** (`language = "pt"`)

Saving persists the choice and immediately reconfigures the shared TTS manager (`app.ttsManager.setLanguage(language)`). The next wake-up session reads it again, so a mid-session change is not required.

## 3. How strings are organized

- `app/src/main/res/values/strings.xml` — English (default)
- `app/src/main/res/values-pt-rBR/strings.xml` — Brazilian Portuguese

Every UI text goes through `context.getString(R.string.…)` or `stringResource(R.string.…)` in Compose,
including notification channel titles. Numeric formats like `"%02d:%02d"` are locale-safe via
`Locale.getDefault()`.

On **Android 13+ (API 33+)**, saving (or importing) a language also calls `AppLocales.apply`, which
sets the per-app `LocaleManager` locale so the matching `values*` folder is used for the UI.
`AnaAlarmApp` reapplies the stored language via `AppLocales.applyIfUnset` on process start.
Below API 33 the UI continues to follow the device locale; TTS / recognition / AI still follow
the in-app `language` setting on every version.

## 4. Non-string localization points

Not everything lives in `strings.xml`; these are handled in code because they depend on the runtime language setting:

| What | Where | How |
|---|---|---|
| TTS language | `TtsManager.setLanguage()` | `Locale("pt","BR")` or `Locale.US` |
| Speech recognizer language | `SessionController` | `languageTag = "pt-BR"` or `"en-US"` |
| AI reply language | `PromptBuilder.buildInstructions()` | instruction in the system prompt |
| Day-of-week labels | `HomeScreen`/`AlarmEditScreen` | `DayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault())` |
| Date/time in prompt | `PromptBuilder` | `DateTimeFormatter.ofPattern("EEEE, MMMM d, yyyy")` (device locale) |
| Stop phrases | `SessionController.stopPhrases` | Bilingual list (en + pt) |

## 5. Adding a new language (e.g. Spanish)

1. **Create the strings file** — copy `values/strings.xml` to `values-es/strings.xml` and translate every entry. Keep the same `name` attributes and format specifiers.
2. **Add the language option** — in `SettingsScreen.kt` add a `FilterChip` for `"es"`.
3. **Map the language key** — in three places:
   - `TtsManager.setLanguage()`: `"es" -> Locale("es", "ES")`
   - `SessionController.start()`: `languageTag = "es-ES"`
   - `PromptBuilder.buildInstructions()`: map `"es" -> "Spanish"`
4. **Translate the stop phrases** in `SessionController` (e.g. "para", "ya estoy despierta", "basta").
5. **Optional** — verify TTS voice availability: `TextToSpeech.setLanguage()` returns `LANG_MISSING_DATA`/`LANG_NOT_SUPPORTED` if the voice isn't installed; the engine falls back to the default voice.

## 6. Translation checklist (pt-BR included)

The pt-BR file is a complete translation of every key in `strings.xml`. When you add a key to the English file, add the same key to `values-pt-rBR/` — otherwise Android falls back to English for that key (no crash, but mixed languages).

Quick check command:

```powershell
# compare keys between the two files (PowerShell)
$en  = Select-String -Path app\src\main\res\values\strings.xml     -Pattern 'name="(\w+)"' -AllMatches | ForEach-Object { $_.Matches } | ForEach-Object { $_.Groups[1].Value }
$pt  = Select-String -Path app\src\main\res\values-pt-rBR\strings.xml -Pattern 'name="(\w+)"' -AllMatches | ForEach-Object { $_.Matches } | ForEach-Object { $_.Groups[1].Value }
Compare-Object $en $pt
```

## 7. Testing language switching

The Settings language chip does **not** re-localize the Compose UI. UI strings follow the
**device locale** (`values/` vs `values-pt-rBR/`). The chip only changes TTS, speech
recognition, and the language Ana replies in. That is the product contract; do not write tests
that expect Home/Settings chrome to switch when the chip is saved.

1. Settings → choose **Português** → Save.
2. Confirm the UI language is unchanged unless the device locale is already pt-BR.
3. Tap **Test wake-up session** — Ana must greet you in Portuguese, and TTS must speak pt-BR (a Google pt-BR voice must be installed on the device).
4. Answer in Portuguese; the AI must stay in Portuguese (prompt enforcement + your input language).
5. Say "acordei" as a complete utterance — the session must end. Ordinary sentences such as "vou para o trabalho" must not.
6. Repeat for English.
