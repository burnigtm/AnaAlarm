#!/usr/bin/env bash
# Physical release-gate evidence capture for issue #6.
# Twin of scripts/device-validation.ps1; see docs/RELEASE_GATE_VALIDATION.md.
#
# Usage:
#   scripts/device-validation.sh [--scenario A|B|C] [--duration 240]
#                                [--screen-record] [serial ...]
#
# Per attached (or explicitly listed) device this writes
# artifacts/device-validation-<serial>/ with device info, a clean logcat
# window around the operator-driven alarm run, optional screen recording,
# and a pre-filled results checklist.
set -euo pipefail

scenario="A"
duration=240
screen_record=0
serials=()

while [[ $# -gt 0 ]]; do
  case "$1" in
    --scenario) scenario="${2:?scenario required}"; shift 2 ;;
    --duration) duration="${2:?duration required}"; shift 2 ;;
    --screen-record) screen_record=1; shift ;;
    --help|-h)
      grep -E '^#( Usage|\s+scripts| Per attached)' "${BASH_SOURCE[0]}" | sed 's/^# \?//'
      exit 0 ;;
    *) serials+=("$1"); shift ;;
  esac
done

adb_bin="${ANAALARM_ADB:-$(command -v adb || true)}"
if [[ -z "$adb_bin" ]]; then
  for candidate in \
      "$HOME/Android/Sdk/platform-tools/adb" \
      "${LOCALAPPDATA:-}/Android/Sdk/platform-tools/adb.exe"; do
    if [[ -n "$candidate" && -x "$candidate" ]]; then adb_bin="$candidate"; break; fi
  done
fi
[[ -n "$adb_bin" ]] || { echo "adb not found. Install platform-tools or set ANAALARM_ADB." >&2; exit 1; }

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
out_root="$repo_root/artifacts"

if [[ ${#serials[@]} -eq 0 ]]; then
  mapfile -t serials < <("$adb_bin" devices | tail -n +2 | awk '$2 == "device" {print $1}')
fi
if [[ ${#serials[@]} -eq 0 ]]; then
  echo "No attached devices found. Connect hardware (USB debugging on) or pass serials." >&2
  exit 1
fi

marker() { # marker <logfile> <fixed-string>
  if [[ -s "$1" ]] && grep -Fq "$2" "$1"; then echo FOUND; else echo "NOT FOUND"; fi
}

for serial in "${serials[@]}"; do
  echo
  echo "=== Device $serial ==="
  out_dir="$out_root/device-validation-$serial"
  mkdir -p "$out_dir"

  sdk="$("$adb_bin" -s "$serial" shell getprop ro.build.version.sdk | tr -d '[:space:]')"
  release="$("$adb_bin" -s "$serial" shell getprop ro.build.version.release | tr -d '[:space:]')"
  model="$("$adb_bin" -s "$serial" shell getprop ro.product.model | tr -d '\r\n')"
  brand="$("$adb_bin" -s "$serial" shell getprop ro.product.brand | tr -d '\r\n')"
  patch="$("$adb_bin" -s "$serial" shell getprop ro.build.version.security_patch | tr -d '[:space:]')"
  {
    printf 'serial=%s\nbrand=%s\nmodel=%s\n' "$serial" "$brand" "$model"
    printf 'api_level=%s\nandroid_release=%s\nsecurity_patch=%s\n' "$sdk" "$release" "$patch"
    printf 'scenario=%s\ncaptured_at=%s\n' "$scenario" "$(date -Iseconds)"
  } > "$out_dir/device-info.txt"
  echo "API $sdk · $brand $model"

  if [[ "$scenario" == "C" ]]; then
    cp "$repo_root/docs/RELEASE_GATE_VALIDATION.md" "$out_dir/"
    echo "Scenario C is Play Console paperwork - see the copied runbook §3."
    continue
  fi

  "$adb_bin" -s "$serial" shell dumpsys power |
      grep -F 'mWakefulness' > "$out_dir/preflight-screen-state.txt" || true
  "$adb_bin" -s "$serial" shell dumpsys window |
      grep -i 'keyguard' > "$out_dir/preflight-keyguard.txt" || true

  "$adb_bin" -s "$serial" logcat -c

  remote_recording="/sdcard/anaalarm-validation-$scenario.mp4"
  recorder_pid=""
  if [[ "$screen_record" == "1" ]]; then
    "$adb_bin" -s "$serial" shell "screenrecord --time-limit $duration $remote_recording &" \
      || echo "screenrecord unavailable on this device; continuing without it." >&2
  fi

  start_ms=$(date +%s%3N)
  case "$scenario" in
    A)
      echo
      echo "[A1] Airplane mode ON, API key removed. Schedule a ONE-SHOT alarm 2-3 minutes" >&2
      echo "     ahead in the app, then press POWER (screen off)." >&2
      read -rp "[A2] After the alarm DELIVERS (sound/vibration audible), press Enter"
      echo "[A3] Do NOT touch the device: confirm sound+vibration persist >=60 seconds." >&2
      read -rp "[A4] Once confirmed, wake the device, press Snooze (or Stop), then press Enter"
      ;;
    B)
      echo
      echo "[B1] PIN lock verified working. Schedule a ONE-SHOT alarm 2-3 minutes ahead," >&2
      echo "     then press POWER." >&2
      read -rp "[B2] When the wake-up screen shows OVER THE KEYGUARD, press Enter (screenshot follows)"
      "$adb_bin" -s "$serial" exec-out screencap -p \
        > "$out_dir/scenario-B-wake-screen.png"
      echo "[B3] Answer one turn (voice or typed), press Stop, verify the session closes." >&2
      read -rp "[B4] When closed, press Enter"
      ;;
    *) echo "Unknown scenario '$scenario'" >&2; exit 2 ;;
  esac

  while (( $(date +%s%3N) - start_ms < duration * 1000 )); do sleep 0.5; done
  "$adb_bin" -s "$serial" logcat -d -v threadtime \
    > "$out_dir/scenario-$scenario-logcat.txt"

  if [[ "$screen_record" == "1" ]]; then
    sleep 2
    "$adb_bin" -s "$serial" pull "$remote_recording" \
      "$out_dir/scenario-$scenario-screen.mp4" >/dev/null ||
      echo "Screen recording could not be pulled." >&2
    "$adb_bin" -s "$serial" shell rm -f "$remote_recording" || true
  fi

  {
    echo "# Validation checklist - scenario $scenario - $serial"
    echo
    echo "Device: $brand $model, API $sdk  |  Captured: $(date -Iseconds)"
    echo "Procedure: docs/RELEASE_GATE_VALIDATION.md section for this scenario."
    echo
    echo "## Automated logcat markers"
    for entry in \
      "AlarmReceiver fired|AlarmReceiver fired alarmId=" \
      "alarm_to_first_audio latency recorded|metric=alarm_to_first_audio" \
      "AlarmService foreground start|startForeground" \
      "WakeUpActivity displayed over keyguard|Displayed com.anaalarm/.ui.wakeup.WakeUpActivity"; do
      name="${entry%%|*}"; pattern="${entry#*|}"
      state="$(marker "$out_dir/scenario-$scenario-logcat.txt" "$pattern")"
      echo "- [$([ "$state" == "FOUND" ] && echo x || echo ' ')] $name : $state"
    done
    echo
    echo "## Operator observations (fill in)"
    echo "- [ ] Sound and vibration persisted >= 60 seconds before any interaction (Scenario A)"
    echo "- [ ] Wake-up screen appeared over the keyguard without user input (Scenario B)"
    echo "- [ ] Explicit Stop/Snooze silenced everything within ~2 seconds"
    echo "- [ ] Screenshots/recording attached to issue #6"
  } > "$out_dir/results-checklist.md"

  echo "Bundle written: $out_dir"
done
