#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$repo_root"

api_level="${1:?usage: run-instrumented-tests.sh <api-level>}"
if [[ ! "$api_level" =~ ^[0-9]+$ ]]; then
  echo "API level must be numeric, got: $api_level" >&2
  exit 2
fi
artifact_dir="${ARTIFACT_DIR:-$repo_root/artifacts/device-api-$api_level}"
mkdir -p "$artifact_dir"
instrument_log="$artifact_dir/instrumentation.log"
required_log="$artifact_dir/required-alarm-suites.log"
preflight_log="$artifact_dir/capability-preflight.log"

app_id="com.anaalarm"
test_id="com.anaalarm.test"
runner="androidx.test.runner.AndroidJUnitRunner"
debug_apk="app/build/outputs/apk/debug/app-debug.apk"
test_apk="app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"

# The four safety-critical classes currently declare exactly 34 tests and must skip none on either
# API 26 or API 36. Keeping this deterministic prevents an @Before assumption or accidental ignore
# from turning the whole required suite into a green no-op.
required_classes="com.anaalarm.alarm.AlarmSchedulerInstrumentedTest,com.anaalarm.alarm.BootReceiverInstrumentedTest,com.anaalarm.alarm.AlarmFiringInstrumentedTest,com.anaalarm.alarm.NotificationsInstrumentedTest"
required_test_count=34
required_skip_count=0
aggregate_test_count=191
aggregate_skip_count=0

set_required_appop() {
  local operation="$1"
  local set_output
  local get_output

  if ! set_output="$(adb shell appops set "$app_id" "$operation" allow 2>&1)"; then
    printf '%s\n' "$set_output" >> "$preflight_log"
    echo "Unable to grant required app-op $operation." >&2
    return 1
  fi
  if ! get_output="$(adb shell appops get "$app_id" "$operation" 2>&1 | tr -d '\r')"; then
    printf '%s\n' "$get_output" >> "$preflight_log"
    echo "Unable to read back required app-op $operation." >&2
    return 1
  fi
  printf '%s: %s\n' "$operation" "$get_output" >> "$preflight_log"
  if ! grep -Eq "(^|[[:space:]])${operation}:[[:space:]]+allow([;[:space:]]|$)" \
      <<<"$get_output"; then
    echo "Required app-op $operation was not confirmed in allow mode: $get_output" >&2
    return 1
  fi
}

configure_test_capabilities() {
  # A missing microphone grant or recognizer must fail visibly; the aggregate suite permits no
  # ignored/assumption tests on the Google APIs CI images.
  adb shell pm grant "$app_id" android.permission.RECORD_AUDIO
  if (( api_level >= 33 )); then
    adb shell pm grant "$app_id" android.permission.POST_NOTIFICATIONS
  fi
  if (( api_level >= 31 )); then
    set_required_appop SCHEDULE_EXACT_ALARM
  fi
  if (( api_level >= 34 )); then
    set_required_appop USE_FULL_SCREEN_INTENT
  fi
}

clear_package_data() {
  local package_name="$1"
  local clear_output
  if ! clear_output="$(adb shell pm clear "$package_name" 2>&1 | tr -d '\r')"; then
    printf 'pm clear %s: %s\n' "$package_name" "$clear_output" >> "$preflight_log"
    echo "Could not clear $package_name between instrumented suites." >&2
    return 1
  fi
  printf 'pm clear %s: %s\n' "$package_name" "$clear_output" >> "$preflight_log"
  if [[ "$clear_output" != "Success" ]]; then
    echo "Unexpected pm clear result for $package_name: $clear_output" >&2
    return 1
  fi
}

reset_between_suites() {
  # The required phase exercises real Activities, services, alarms, Room, and device-protected
  # state. A second instrumentation process is not a clean-install boundary, so erase both
  # packages explicitly and restore every required capability before the aggregate phase.
  adb shell am force-stop "$app_id"
  clear_package_data "$app_id"
  clear_package_data "$test_id"
  configure_test_capabilities
  adb shell input keyevent KEYCODE_WAKEUP >/dev/null
  adb shell wm dismiss-keyguard >/dev/null
}

run_instrumentation() {
  local label="$1"
  local filter="$2"
  local log_file="$3"
  local expected_count="$4"
  local expected_skips="$5"
  local command=(adb shell am instrument -w -r -e debug false)
  if [[ -n "$filter" ]]; then
    command+=(-e class "$filter")
  fi
  command+=("$test_id/$runner")

  local instrument_status
  set +e
  "${command[@]}" 2>&1 | tr -d '\r' | tee "$log_file"
  instrument_status=${PIPESTATUS[0]}
  set -e

  if (( instrument_status != 0 )); then
    echo "$label: adb instrumentation exited with status $instrument_status." >&2
    return "$instrument_status"
  fi

  local verdict
  verdict="$(grep -E '^OK \([1-9][0-9]* tests?\)$' "$log_file" | tail -n 1 || true)"
  local actual_count
  if [[ "$verdict" =~ ^OK[[:space:]]\(([1-9][0-9]*)[[:space:]]tests?\)$ ]]; then
    actual_count="${BASH_REMATCH[1]}"
  else
    echo "$label did not report a successful, non-zero OK(test count) verdict." >&2
    return 1
  fi
  if (( expected_count >= 0 && actual_count != expected_count )); then
    echo "$label ran $actual_count tests; expected exactly $expected_count." >&2
    return 1
  fi

  if (( expected_skips >= 0 )); then
    local actual_skips
    actual_skips="$(grep -Ec '^INSTRUMENTATION_STATUS_CODE: -(3|4)[[:space:]]*$' \
      "$log_file" || true)"
    if (( actual_skips != expected_skips )); then
      echo "$label reported $actual_skips ignored/assumption tests; expected $expected_skips." >&2
      return 1
    fi
  fi

  echo "$label passed: $verdict."
}

bash ./gradlew assembleDebug assembleDebugAndroidTest \
  --dependency-verification=strict --no-parallel --stacktrace

adb wait-for-device
adb install -r -t "$debug_apk"
adb install -r -t "$test_apk"

device_api="$(adb shell getprop ro.build.version.sdk | tr -d '\r')"
if [[ "$device_api" != "$api_level" ]]; then
  echo "Emulator API mismatch: workflow requested $api_level, device reports $device_api." >&2
  exit 1
fi
printf 'device_api=%s\n' "$device_api" > "$preflight_log"

# Runtime permissions and alarm special-access are mandatory and read back where app-ops apply.
configure_test_capabilities

adb shell settings put global window_animation_scale 0
adb shell settings put global transition_animation_scale 0
adb shell settings put global animator_duration_scale 0
adb shell input keyevent KEYCODE_WAKEUP >/dev/null 2>&1 || true
adb shell wm dismiss-keyguard >/dev/null 2>&1 || true

run_instrumentation \
  "Required alarm/boot/firing suite" \
  "$required_classes" \
  "$required_log" \
  "$required_test_count" \
  "$required_skip_count"

# Do not let the first phase's durable app data, tasks, services, or permission mutations influence
# the aggregate phase. The complete CI image suite is also fail-closed on count and assumptions.
reset_between_suites
run_instrumentation \
  "Aggregate instrumented suite" \
  "" \
  "$instrument_log" \
  "$aggregate_test_count" \
  "$aggregate_skip_count"

echo "Instrumented tests passed on API $api_level."
