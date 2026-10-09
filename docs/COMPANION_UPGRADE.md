# Companion controls and editable memory

The wake screen offers **Your turn** while Ana speaks or generates a reply. It cancels pending
generation and speech before opening the selected input mode. **Voice / Type** can be changed
without restarting the conversation; voice remains unavailable when permission or recognition
is missing. **Repeat** replays the last visible reply locally. **Retry conversation** retries a
failed greeting or accepted turn without inserting that user message a second time.

The buddy reflects actual streaming playback, responds to taps, and respects Android's animator
duration setting, including changes made while the app is open. Typed answers and editor drafts
survive activity recreation. Stop and Snooze remain outside the scrolling conversation content.

## Memory

**Settings → What Ana remembers** lists automatically extracted facts and goals. Entries are
marked as inferred until edited. Forgetting an entry retains a local suppression marker to keep
an identical automatic suggestion from returning. Turning memory off prevents extraction and
omits saved memories from subsequent model requests. Daily recaps remain readable local records;
they are no longer injected as uneditable personal memory into future conversations.

After a conversation with user responses, memory extraction makes one optional model request
with a 12-second deadline and a 512-token output allowance. It requires confident candidates
and an exact supporting quote from the user's statements. Extraction never overwrites an edited
entry. Malformed or offline responses preserve the local recap and do not create memories.
This extra request is included in token accounting. Memory settings and entries are included in
the existing device-local encrypted export; old exports continue to load without those fields.

## Alarm behavior

One-shot alarms retain their snooze budget until the wake sequence is dismissed. Pending snoozes
are mirrored in device-protected storage and re-armed after reboot; snooze delivery clears the
pending timestamp without resetting the budget. Spoken and typed stop phrases follow the same
challenge policy as Stop. Android's next-alarm shortcut opens the alarm editor.

Room migration 7 → 8 adds the companion memory table without changing existing alarm or log rows.
The direct-boot mirror reads older v1/v2 snapshots and writes v3 with the pending snooze timestamp
and retirement acknowledgement. A pre-unlock dismissal keeps its tombstone until Room has
disabled the one-shot, so reboot cannot resurrect it. A later user re-enable remains authoritative.

## Verification

Run `testDebugUnitTest`, `assembleDebug`, and `assembleDebugAndroidTest`, then the device suite.
New tests cover turn cancellation, replay, recovery, source-grounded reflection, memory controls,
draft recreation, alarm detail routing, and durable snooze state. On Windows systems where Java
reports a loopback pipe error, use a short existing directory for `jdk.net.unixdomain.tmpdir`.

A reserved Add alarm bar keeps Home actions clear of the alarm list. The wake conversation uses
its own viewport above Stop/Snooze, including with the keyboard or large text. Stream reads and
cancellation cleanup run on IO; text callbacks return to the caller context, and short final
phrases are flushed before listening resumes.

The local validation used the installed OpenJDK 21 runtime and these temporary host settings:

```powershell
$env:JAVA_HOME = Join-Path $env:USERPROFILE '.jdks\openjdk-21'
$env:JAVA_TOOL_OPTIONS = '-Djavax.net.ssl.trustStoreType=Windows-ROOT -Djdk.net.unixdomain.tmpdir=D:\AnaAlarm\build'
.\gradlew.bat testDebugUnitTest assembleDebug assembleDebugAndroidTest
```

Validated on 2026-10-09 after integration with current `main`: all **295 JVM tests** passed.
Debug, Android-test, and minified release APKs built successfully; `lintDebug` passed.
The release/lint verification used strict dependency verification, and inspection confirmed
that the minified APK retained the export serializers and format marker.
On a fresh Android 15 / API 35 emulator at 320 × 640 dp, **81 targeted device tests** passed,
including the memory toggle's screen-reader label, buddy challenges, export round-trip,
and the existing system-Back regression from `main`.
These runs cover migration, snooze retirement/unlock recovery, source-grounded memory,
streaming on the main caller, conversation failure recovery, navigation and draft recreation,
and English/Portuguese large-text portrait/landscape layouts with animations disabled.
The AI server was simulated; live microphone/TTS audio and a manual TalkBack walkthrough
remain physical-device validation tasks.

The final device log is `build/pr-integration-device-tests.log`. Test discovery confirmed
204 aggregate tests; CI preserves exact-count, zero-skip gates at 37 required alarm tests and
204 aggregate tests on API 26/36. Those CI images were not run locally. Earlier local logs
retain the failures fixed during development. The installable debug build is
`app/build/outputs/apk/debug/app-debug.apk`.
