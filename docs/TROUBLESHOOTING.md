# Troubleshooting

Common problems, their causes, and fixes.

## Build problems

### `Unable to strip the following libraries, packaging them as they are: libandroidx.graphics.path.so`
Harmless warning from the NDK strip step on Windows. The APK still builds.

### `Task :app:compileDebugKotlin FAILED` with `Unresolved reference`
Almost always a dependency-version mismatch after editing `build.gradle.kts`. Fixes, in order:
1. `.\gradlew.bat clean`
2. Delete `app/build`, `build`, and `~\.gradle\caches\...` for the affected modules if it persists.
3. Re-run `.\gradlew.bat assembleDebug`.

### Build fails on `downloads.gradle.org` / timeouts
First build downloads ~1 GB of dependencies. Run once with a stable connection; afterwards everything is cached. If a download stalls:
```powershell
.\gradlew.bat --stop
.\gradlew.bat assembleDebug --refresh-dependencies
```

### `SDK location not found`
`local.properties` must contain a valid `sdk.dir`. This file is machine-specific:
```
sdk.dir=C\:\\Users\\<you>\\AppData\\Local\\Android\\Sdk
```
(You can also set the `ANDROID_HOME` environment variable instead.)

## Installation problems

### `INSTALL_FAILED_UPDATE_INCOMPATIBLE`
The signature of the installed app differs (e.g. you installed a different build before):
```powershell
adb uninstall com.anaalarm
adb install app\build\outputs\apk\debug\app-debug.apk
```

### `INSTALL_FAILED_INSUFFICIENT_STORAGE`
Free up space on the device, or use `adb install -r -d` (allow downgrade) if it's a versioning issue.

## The alarm doesn't ring

1. **Exact-alarm permission denied (Android 12+).** Open the app → if the warning card shows, tap **Grant permission**. Verify with:
   ```powershell
   adb shell cmd appops get com.anaalarm SCHEDULE_EXACT_ALARM
   ```
   Should report `allow`.
2. **Notification permission denied (Android 13+).** The full-screen intent only fires through a notification. Re-grant in app settings.
3. **Battery optimizations / OEM killers.** Some manufacturers (Xiaomi, Huawei, Samsung) aggressively kill background apps. Add AnaAlarm to the device's "no restrictions" / autostart whitelist.
4. **Alarm doesn't repeat as expected.** An alarm with no repeat days is one-shot and becomes
   disabled after delivery. Repeating alarms follow the selected day bits. Snooze is a separate
   one-off exact alarm and does not change the repeat schedule.
5. **Verify it's scheduled:**
   ```powershell
   adb shell dumpsys alarm | Select-String -Pattern anaalarm
   ```

## The wake-up screen doesn't appear over the lock screen

- `USE_FULL_SCREEN_INTENT` may be disabled by the OEM. Check Settings → Apps → AnaAlarm → Alarms & reminders → "Full screen". If unavailable, the notification appears as a heads-up instead.
- Some phones require "Draw over other apps" or "Display over other apps" permission for full-screen intents.

## The AI doesn't answer / errors on screen

| Error | Cause | Fix |
|---|---|---|
| "Set your DeepSeek API key…" | No key in Settings | Add your key from platform.deepseek.com |
| "Could not reach the AI…" | Network, wrong key, rate limit | Check connection; verify key validity and quota at platform.deepseek.com; try again |
| Stuck on "Thinking…" | Slow network/model | The whole logical call ends after 20 seconds. Check connectivity and retry; only a quick transport disconnect is retried once |

### Verify the API key manually
```powershell
curl.exe -s https://api.deepseek.com/responses `
  -H "Content-Type: application/json" `
  -H "Authorization: Bearer sk-..." `
  -d '{\"model\":\"deepseek-v4-flash\",\"instructions\":\"Say hi\",\"input\":\"Hello\"}'
```
If this returns `401`, the key is invalid; `429` means rate-limited.

## Voice issues

### TTS speaks the wrong language or nothing
- The device needs the matching voice: Settings → Accessibility → Text-to-speech output (or Google TTS settings) → install the **English (US)** and **Português (Brasil)** voices.
- AnaAlarm sets TTS language from the app language (Settings → language). Check it's not "English" while expecting pt-BR.

### Speech recognition doesn't hear me
- **Google app / Google speech services** must be installed and the mic permission granted.
- Background noise: silence triggers two retries, then Ana asks you to repeat.
- If recognition is completely unavailable (no Google services), the wake-up screen automatically shows a **text input fallback**.

### Mic works in other apps but not AnaAlarm
- Re-check `RECORD_AUDIO` in Settings → Apps → AnaAlarm → Permissions.
- Some devices route mic differently when the screen is locked; unlock once during the session to verify.

## Memory / follow-up doesn't work ("she doesn't remember")

- The AI only sees the **last 20 messages** of the current session plus the **most recent previous daily log**.
- A session must end through one of the exit paths for the daily log to be saved (Stop button, stop phrase, time cap, or error path — all call `ConversationEngine.endSession()`).
- If the app process is killed before finalization, the bounded daily log may not be written. Raw rows
  are retained for recovery but startup-pruned after seven days.
- Check the database:
  ```powershell
  adb shell run-as com.anaalarm ls databases/
  ```

## Localization issues

- Mixed languages in UI = a key missing from `values-pt-rBR/strings.xml` (Android falls back to English). See `docs/LOCALIZATION.md` §6 for a key-parity check.
- AI switches language mid-conversation = rare model behavior; the prompt enforces the language, but the model may still mirror your input. Saying the app-language reply keeps it on track.

## Misc

### App is slow to open on first launch after install
First launch initializes Room, DataStore, and TTS. During an alarm session, TTS readiness and the
first model request run concurrently; local sound/vibration starts before either finishes.

### Battery drain
The wake-up session keeps the screen on by design. Outside sessions, the app has no background work except alarms (exact alarms are wake-up alarms by definition).

### Speech recognition switches to text unexpectedly
Re-check microphone permission and the installed recognition service. Permission, audio, network,
server, and unsupported-language failures switch promptly to typed input instead of leaving the
session listening forever.
