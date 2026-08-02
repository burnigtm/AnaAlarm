#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
api_level="${1:?usage: run-device-ci.sh <api-level>}"
export ARTIFACT_DIR="$repo_root/artifacts/device-api-$api_level"
mkdir -p "$ARTIFACT_DIR"

capture_diagnostics() {
  adb logcat -d -v threadtime > "$ARTIFACT_DIR/logcat.txt" 2>&1 || true
  adb shell dumpsys activity activities > "$ARTIFACT_DIR/activities.txt" 2>&1 || true
}
trap capture_diagnostics EXIT

bash "$repo_root/scripts/ci/run-instrumented-tests.sh" "$api_level"

# One release smoke is enough; API 36 also exercises the current target-SDK behavior.
if [[ "$api_level" == "36" ]]; then
  bash "$repo_root/scripts/ci/smoke-release.sh" "$api_level"
else
  printf 'Release smoke is intentionally run on API 36.\n' \
    > "$ARTIFACT_DIR/release-smoke-skipped.txt"
fi
