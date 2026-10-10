# Sentinel device diagnostics

Open **Settings → Sentinel → Open device diagnostics** for a quiet check-in on AnaAlarm.
Sentinel does not ring an alarm, initialize voice engines, record the microphone, or call an API.
The screen refreshes when you return from Android settings; **Refresh** checks again manually.

The cards check exact alarms, notifications and both notification channels, full-screen access,
battery/background restrictions, microphone permission, installed voice services, whether a
conversation key is configured, enabled alarm count, and the system's next alarm. Voice-service
availability does not prove that playback or recognition works. A next alarm belonging to another
app is labeled unavailable; Android does not expose AnaAlarm's later registrations through that API.
These checks cannot guarantee that a manufacturer will let a future alarm fire.

**Ready**, **Needs attention**, and **Unavailable** describe each check. Where possible,
**Open settings** takes you to the relevant setting, with app details as a fallback. A missing key
affects conversations only; local alarm sound still works. On Android 11 and later, Sentinel also
shows the number of recent crash/ANR exits supplied by Android. Older versions label this unavailable.

## Local event history and sharing

Up to 200 events remain in app-private storage for seven days. They contain only timestamps,
predefined event/outcome codes and optional elapsed milliseconds. Scheduling/playback failures and
the existing alarm, recognition, model and TTS timing boundaries feed this history. Every latency
dimension is discarded. There are no keys, transcripts, personal settings, raw exception messages,
stack traces, unrestricted logcat, analytics uploads, or device identifiers in event records.
The screen shows the newest 20 events; reports include the entire retained history. Expired
events are removed on app startup or when history is read or recorded, without a background worker.

Recording is asynchronous and best effort: a full queue or storage failure drops data rather than
blocking alarm/voice callbacks. Credential storage is initialized only after first unlock; Sentinel
does not add disk access to the direct-boot alarm path. Very recent events can be lost on process death.

**Share diagnostic report** opens Android's Sharesheet with a JSON attachment. The report includes
schema version 1, app/package/version identity, Android API level, manufacturer/model, current
health results, system crash/ANR reason codes and local events. A recipient sees only what you
choose to share. The non-exported FileProvider exposes only the report cache directory and grants
temporary read access. Cached reports expire on the next export after 24 hours.

**Clear local event history** clears the local store and cached reports. Android's system exit
history is controlled by Android and is not erased by this action.

The Settings download action opens the latest signed **AnaAlarm Internal** APK in your browser.
It is a separate package from the production app. See [installation instructions](CI_AND_INSTALLABLE_BUILDS.md).
