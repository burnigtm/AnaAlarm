# AnaAlarm voice companion validation

Completed locally on **10 October 2026**. Conversation remains the default; guided routines are optional. Bilingual local commands, spoken challenges, daily priorities, microphone feedback, buddy reactions, storage migration, and encrypted backup compatibility are implemented.

## Executed checks

| Check | Result |
|---|---|
| Unit tests | 417 passed; zero failures, errors, or skips |
| Full instrumentation | 230 passed on each of APIs 26, 31, and 36; 690 executions, zero skips |
| Final targeted regression | 44 passed on each API; voice, routine, Home, accessibility, and buddy rendering |
| Timestamped voice trace recheck | 20 passed again on APIs 26 and 31; API 36’s 44-test run already uses the final trace harness |
| Installed-engine lifecycle smoke | 15 passed separately on each API; zero skips |
| Minified release smoke | Passed on APIs 26, 31, and 36 |
| Lint and builds | Debug, release, and internal passed; zero lint errors |
| Migration and backup | Room v8→v9, migration chain, old exports, new encrypted round trips, and date replacement passed |

Full-suite runs were followed by targeted regressions after the final date, challenge-guard, and trace changes. Installed-engine smoke covers lifecycle and framework bindings. Deterministic voice tests use explicitly selected debug services, the real speech wrapper, controlled playback, and the local fake AI server.

## Coverage

| Scope | Lines | Branches |
|---|---:|---:|
| Voice, session, command, routine, and buddy logic | 98.11% (2287/2331) | 88.44% (1194/1350) |
| Whole app | 90.37% (6635/7342) | 78.12% (2700/3456) |

**The 90% line / 85% branch gate passed.** Generated Room implementations, resource classes, BuildConfig, and scripted debug engines are excluded. The focused scope includes the complete handwritten voice and avatar packages plus session, command, timing, and routine logic. Whole-app coverage has a separate report.

[Voice coverage report](../artifacts/voice-companion-validation-20261010/coverage/voiceCoverageReport/html/index.html) · [Whole-app report](../artifacts/voice-companion-validation-20261010/coverage/appCoverageReport/html/index.html) · [Unit report](../artifacts/voice-companion-validation-20261010/unit-report/index.html)

## Device evidence

Each instrumentation directory contains discovery and result logs, JUnit XML, coverage, logcat, and timestamped test evidence. Voice traces include transcripts, playback, session state, and reactions; failure screenshots are captured when a test fails. Release directories include signature/descriptor checks, launch logs, process checks, and a screenshot.

| API | Full suite | Final regression | Installed engine | Minified release |
|---|---|---|---|---|
| 26 | [full](../artifacts/device-api-26/20261010-123901/instrumentation.log) | [regression](../artifacts/device-api-26/20261010-130038/instrumentation.log) | [installed engine](../artifacts/device-api-26/20261010-130631/instrumentation.log) | [release](../artifacts/release-api-26/20261010-131114/release-smoke.log) |
| 31 | [full](../artifacts/device-api-31/20261010-123901/instrumentation.log) | [regression](../artifacts/device-api-31/20261010-130509/instrumentation.log) | [installed engine](../artifacts/device-api-31/20261010-131733/instrumentation.log) | [release](../artifacts/release-api-31/20261010-131747/release-smoke.log) |
| 36 | [full](../artifacts/device-api-36/20261010-123901/instrumentation.log) | [regression](../artifacts/device-api-36/20261010-131104/instrumentation.log) | [installed engine](../artifacts/device-api-36/20261010-152719/instrumentation.log) | [release](../artifacts/release-api-36/20261010-152737/release-smoke.log) |

Earlier failed fixture runs are retained for diagnosis. The runs linked above passed. CI now verifies test discovery against the reviewed 230-test inventory, resets state between groups, rejects required skips, uploads evidence, and gates installable builds on coverage. Hosted GitHub Actions was not dispatched during this local validation.

## Scenario coverage

- Commands and normalization: precedence, negation, misleading sentences, both languages, unavailable controls, snooze caps, and challenge enforcement.
- Recognition: readiness, activity, bounded levels, partial/final results, duplicates, stale callbacks, cancellation, permission loss/recovery, missing services, unsupported languages, silence, busy and network/audio errors.
- Session delivery: multi-turn speech in both languages with streaming enabled/disabled, queued playback boundaries, delayed or missing callbacks, replay, interruption, recovery, timeout, Stop/Snooze, overlapping alarms, and single finalization.
- Challenges and routines: shared validators, number words, ambiguous/incorrect answers, leading-zero parsing, typing fallback, completion/skip, empty/already-completed habits, storage failures, recreation/relaunch, priority replacement, midnight, Home refresh, and old/new backups.
- Buddies and accessibility: every species and mood, both themes, reaction transitions and rapid taps, localized descriptions, reduced motion, and large-font controls.
- Existing alarm, scheduling, boot, widget, memory, statistics, storage, and navigation cases remain in the full suite.

## Artifacts and reproducibility

Use [VOICE_COMPANION.md](VOICE_COMPANION.md) for controls and runner commands. The saved machine-readable [summary](../artifacts/voice-companion-validation-20261010/summary.json) includes measurements, evidence paths, and APK hashes. Reports and APKs are generated local artifacts.

| APK | SHA-256 |
|---|---|
| [app-debug.apk](../app/build/outputs/apk/debug/app-debug.apk) | `138e3f7faeefdf7c4071eef2bca63e35eb233513a70a8b6d4153c9b67d64fa05` |
| [app-release-unsigned.apk](../app/build/outputs/apk/release/app-release-unsigned.apk) | `48aa1515d0d3bf742135d15868d126a2f3eed6bf5c3eb5ec6c822577f8f8414a` |

The release APK above is unsigned. Smoke copies use ephemeral emulator-only signing keys; the debug APK is installable for development.

## Validation boundary

Emulator automation verifies software behavior and Android framework integration. **Real microphone accuracy, acoustic echo, speaker quality, and physical-device alarm behavior remain unverified.**
