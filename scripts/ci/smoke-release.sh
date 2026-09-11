#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$repo_root"

api_level="${1:?usage: smoke-release.sh <api-level>}"
artifact_dir="${ARTIFACT_DIR:-$repo_root/artifacts/device-api-$api_level}"
mkdir -p "$artifact_dir"
smoke_log="$artifact_dir/release-smoke.log"

sdk_root="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
if [[ -z "$sdk_root" ]]; then
  echo "ANDROID_SDK_ROOT or ANDROID_HOME must point to the Android SDK." >&2
  exit 1
fi
build_tools_root="$sdk_root/build-tools"
build_tools_version="$(find "$build_tools_root" -mindepth 1 -maxdepth 1 -type d \
  -printf '%f\n' | sort -V | tail -n 1)"
if [[ -z "$build_tools_version" ]]; then
  echo "No Android build-tools installation found under $build_tools_root." >&2
  exit 1
fi
build_tools="$build_tools_root/$build_tools_version"

scratch="$(mktemp -d "${RUNNER_TEMP:-/tmp}/anaalarm-release-smoke.XXXXXX")"
cleanup() {
  rm -rf -- "$scratch"
}
trap cleanup EXIT

unsigned_apk="app/build/outputs/apk/release/app-release-unsigned.apk"
mapping_file="app/build/outputs/mapping/release/mapping.txt"
aligned_apk="$scratch/anaalarm-release-aligned.apk"
signed_apk="$scratch/anaalarm-release-ci-signed.apk"
key_store="$scratch/ci-only-signing.p12"
key_alias="anaalarm-ci"
key_password="$(openssl rand -hex 18)"

bash ./gradlew assembleRelease \
  --dependency-verification=strict --no-parallel --stacktrace
test -s "$unsigned_apk"
test -s "$mapping_file" # R8 mapping proves this is the minified release variant.

bash "$repo_root/scripts/ci/verify-minify-data-export.sh" "$mapping_file" "$unsigned_apk" \
  | tee -a "$smoke_log"

keytool -genkeypair -noprompt \
  -keystore "$key_store" -storetype PKCS12 \
  -storepass "$key_password" -keypass "$key_password" \
  -alias "$key_alias" -keyalg RSA -keysize 3072 -validity 1 \
  -dname "CN=AnaAlarm CI,OU=Ephemeral,O=CI,L=None,ST=None,C=US" >/dev/null
"$build_tools/zipalign" -f 4 "$unsigned_apk" "$aligned_apk"
"$build_tools/apksigner" sign \
  --ks "$key_store" --ks-key-alias "$key_alias" \
  --ks-pass "pass:$key_password" --key-pass "pass:$key_password" \
  --out "$signed_apk" "$aligned_apk"
"$build_tools/apksigner" verify --verbose --print-certs "$signed_apk" > "$smoke_log"

# The debug app has a different signature, so remove it before installing the ephemeral release.
adb uninstall com.anaalarm.test >/dev/null 2>&1 || true
adb uninstall com.anaalarm >/dev/null 2>&1 || true
adb install "$signed_apk" | tee -a "$smoke_log"
adb shell am force-stop com.anaalarm
launch_output="$(adb shell am start -W -n com.anaalarm/.MainActivity 2>&1 | tr -d '\r')"
printf '%s\n' "$launch_output" | tee -a "$smoke_log"

if ! grep -Fq "Status: ok" <<<"$launch_output"; then
  echo "Minified release activity did not launch successfully." >&2
  exit 1
fi

for _ in {1..20}; do
  if adb shell pidof com.anaalarm >/dev/null 2>&1; then
    echo "Ephemerally signed minified release installed and launched on API $api_level." \
      | tee -a "$smoke_log"
    exit 0
  fi
  sleep 0.5
done

echo "Release process was not alive after launch." >&2
exit 1
