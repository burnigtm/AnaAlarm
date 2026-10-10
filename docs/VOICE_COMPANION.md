# Voice companion and offline morning routine

Choose **Settings → Wake-up experience → Guided routine** to walk through your unfinished habits
and capture today's priority. Conversation remains the default. The routine uses local prompts,
habit records, and text-to-speech, so it needs no AI key. When recognition or speech output is
unavailable, use the same controls through typing and touch.

Habits are shown in their configured order. **Mark done** saves completion immediately; **Skip**
moves on without recording completion. Already completed habits are omitted on a later launch.
Rotation retains the active session. The final priority appears on Home, where tapping its card
allows editing. Each local date has one priority; saving again replaces it. Priorities and session
mode are included in encrypted backups, and older backups still import.

## Voice controls

| Action | English examples | Brazilian Portuguese examples |
|---|---|---|
| Stop | stop; I'm up | pode parar; acordei |
| Snooze | snooze | soneca; adiar |
| Repeat | repeat; say that again | repita; repita isso |
| Type | switch to typing; let me type | digitar; quero digitar |
| Complete current habit | done; mark done | feito; concluído |
| Skip current habit | skip; next | pular; próximo |
| Cancel open challenge | cancel | cancelar |

New commands match complete utterances. Negated commands remain conversational input. Routine
commands apply only to the current habit. Stop opens the configured challenge, and snooze obeys
the same cap as its button. Commands do not make model requests.

Tap **Your turn** to interrupt speech. The app opens the microphone after playback completes;
streaming replies must reach their terminal event and finish their last queued phrase first.
Replay uses the existing text locally. Audio-focus loss cancels playback and offers typing;
choose Voice or Repeat when ready to continue.

## Spoken challenges and buddy feedback

Choose **Speak answer** in an open challenge, or type as before. Math accepts digits and English
or Portuguese number words through the challenge's range. Memory codes require four digits,
spoken separately if using words: “zero zero one two” keeps the leading zeros. Ambiguous or
incorrect answers leave the challenge open. Recognition remains closed while a code is displayed
for memorization, and it cannot open over an alarm that has not yet yielded to audible speech.

The buddy acknowledges speech, reassures after recognition or storage failures, and celebrates
completed habits and correct answers. Repeated taps restart a single reaction timer. Partial
transcripts and bounded microphone activity provide feedback without submitting a turn.
Reaction descriptions are localized for accessibility. Disabled system animations use static poses.

## Automated verification

The debug APK supplies an explicitly selected recognition service and TTS engine. Tests exercise
real Android recognition callbacks, plus independently controlled playback start/completion,
the real HTTP client against a local AI server, UI state, reactions, and persisted records.
These services and test controls are absent from release builds.

Run the complete reviewed suite on a selected emulator:

```powershell
.\scripts\run-instrumented-tests.ps1 -Serial emulator-5556 -Coverage
```

The runner checks test discovery against `scripts/ci/instrumented-tests.txt`, rejects required
skips, resets app data, and captures results, traces, screenshots on failure, and coverage under
`artifacts/device-api-<API>/<run>/`. Use `-Filter` for a class or `Class#method`, `-SkipBuild` for
existing APKs, and `-KeepData` only when intentionally debugging retained state.

After collecting device coverage, generate reports and enforce the voice/session gate:

```powershell
.\gradlew.bat :app:appCoverageReport :app:verifyVoiceCoverage
```

The minimum is 90% line coverage and 85% branch coverage for handwritten voice, session,
routine, and buddy reaction/rendering logic. Whole-app coverage is reported separately. CI runs APIs 26, 31, and 36, collects their
measurements, and gates installable artifacts on the merged coverage check.

Installed-engine lifecycle smoke tests can be run separately with the filters
`com.anaalarm.voice.SpeechListenerInstrumentedTest,com.anaalarm.voice.TtsManagerInstrumentedTest`.
To sign a minified APK with an ephemeral emulator key and verify its launch on Windows, run
`scripts/smoke-release.ps1 -Serial emulator-5556`. It checks retained export descriptors,
the absence of scripted engines, release flags, and process survival, and keeps its logs in
`artifacts/release-api-<API>/<run>/`.

Emulator tests verify software and framework behavior. They do not establish physical microphone
accuracy, acoustic echo handling, speaker quality, or real-device alarm delivery.
