#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$repo_root"

version_code="${ANAALARM_INTERNAL_VERSION_CODE:-100}"
version_suffix="${ANAALARM_INTERNAL_VERSION_SUFFIX:-ci}"
device_smoke="${ANAALARM_INTERNAL_DEVICE_SMOKE:-0}"

scratch="$(mktemp -d "${RUNNER_TEMP:-/tmp}/anaalarm-installable-test.XXXXXX")"
cleanup() {
  if [[ "$device_smoke" == "1" ]]; then
    adb uninstall com.anaalarm.internal >/dev/null 2>&1 || true
  fi
  rm -rf -- "$scratch"
}
trap cleanup EXIT

key_store="$scratch/ci-only-internal-signing.p12"
key_alias="anaalarm-internal"
key_password="$(openssl rand -hex 18)"

keytool -genkeypair -noprompt \
  -keystore "$key_store" -storetype PKCS12 \
  -storepass "$key_password" -keypass "$key_password" \
  -alias "$key_alias" -keyalg RSA -keysize 3072 -validity 1 \
  -dname "CN=AnaAlarm Installable Test,OU=Ephemeral,O=CI,L=None,ST=None,C=US" >/dev/null

cert_digest="$(keytool -exportcert \
  -keystore "$key_store" -storepass "$key_password" -alias "$key_alias" 2>/dev/null \
  | sha256sum | awk '{print $1}')"
printf '%s\n' "$cert_digest" > "$scratch/certificate.sha256"

export ANAALARM_INTERNAL_KEYSTORE_PATH="$key_store"
export ANAALARM_INTERNAL_KEY_ALIAS="$key_alias"
export ANAALARM_INTERNAL_STORE_PASSWORD="$key_password"
export ANAALARM_INTERNAL_KEY_PASSWORD="$key_password"
export ANAALARM_INTERNAL_CERT_SHA256="$cert_digest"
export ANAALARM_INTERNAL_CERT_PIN_FILE="$scratch/certificate.sha256"
export ANAALARM_INTERNAL_VERSION_CODE="$version_code"
export ANAALARM_INTERNAL_VERSION_SUFFIX="$version_suffix"
export ANAALARM_INSTALLABLE_ARTIFACT_DIR="$scratch/output"

bash scripts/ci/sign-installable-apk.sh

artifact_dir="$ANAALARM_INSTALLABLE_ARTIFACT_DIR"
artifact="$(find "$artifact_dir" -maxdepth 1 -type f \
  -name "AnaAlarm-internal-${version_suffix}-*.apk" -print -quit)"
test -n "$artifact"
test -s "$artifact"
test -s "$artifact.sha256"
test -s "$artifact_dir/build-info.txt"
(
  cd "$artifact_dir"
  sha256sum -c "$(basename "$artifact").sha256"
)
grep -Fxq 'application_id=com.anaalarm.internal' "$artifact_dir/build-info.txt"
grep -Fxq 'application_label=AnaAlarm Internal' "$artifact_dir/build-info.txt"
grep -Fxq "version_code=$version_code" "$artifact_dir/build-info.txt"
grep -Fxq 'minified=true' "$artifact_dir/build-info.txt"

if [[ "$device_smoke" == "1" ]]; then
  smoke_log="${ARTIFACT_DIR:-$scratch}/internal-install-smoke.log"
  mkdir -p "$(dirname "$smoke_log")"
  adb uninstall com.anaalarm.internal >/dev/null 2>&1 || true
  adb install "$artifact" | tee "$smoke_log"
  adb shell am force-stop com.anaalarm.internal
  launch_output="$(adb shell am start -W \
    -n com.anaalarm.internal/com.anaalarm.MainActivity 2>&1 | tr -d '\r')"
  printf '%s\n' "$launch_output" | tee -a "$smoke_log"
  if ! grep -Fq 'Status: ok' <<<"$launch_output"; then
    echo "Internal MainActivity did not cold-launch successfully." >&2
    exit 1
  fi
  for _ in {1..20}; do
    if adb shell pidof com.anaalarm.internal | grep -Eq '[0-9]'; then
      printf 'Internal app process stayed alive after launch.\n' | tee -a "$smoke_log"
      break
    fi
    sleep 0.25
  done
  if ! adb shell pidof com.anaalarm.internal | grep -Eq '[0-9]'; then
    echo "Internal app process was not alive after launch." >&2
    exit 1
  fi
fi

printf 'Installable APK rehearsal passed with an ephemeral key.\n'
