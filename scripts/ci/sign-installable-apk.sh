#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$repo_root"

keystore_path="${ANAALARM_INTERNAL_KEYSTORE_PATH:?Internal keystore path is required}"
key_alias="${ANAALARM_INTERNAL_KEY_ALIAS:-anaalarm-internal}"
version_code="${ANAALARM_INTERNAL_VERSION_CODE:?Internal version code is required}"
version_suffix="${ANAALARM_INTERNAL_VERSION_SUFFIX:?Internal version suffix is required}"
expected_cert="${ANAALARM_INTERNAL_CERT_SHA256:?Internal certificate SHA-256 is required}"
cert_pin_file="${ANAALARM_INTERNAL_CERT_PIN_FILE:-$repo_root/.github/internal-distribution-certificate.sha256}"
: "${ANAALARM_INTERNAL_STORE_PASSWORD:?Internal store password is required}"
: "${ANAALARM_INTERNAL_KEY_PASSWORD:?Internal key password is required}"

normalize_digest() {
  printf '%s' "$1" | tr -d '[:space:]:' | tr '[:upper:]' '[:lower:]'
}

if [[ ! -s "$cert_pin_file" ]]; then
  echo "Internal certificate pin file does not exist or is empty." >&2
  exit 2
fi
expected_cert="$(normalize_digest "$expected_cert")"
pinned_cert="$(normalize_digest "$(<"$cert_pin_file")")"
if [[ ! "$expected_cert" =~ ^[0-9a-f]{64}$ ]] || [[ "$expected_cert" != "$pinned_cert" ]]; then
  echo "Configured internal certificate does not match the version-controlled pin." >&2
  exit 1
fi

if [[ ! "$version_code" =~ ^[0-9]+$ ]] ||
    (( version_code < 1 || version_code > 2100000000 )); then
  echo "Internal version code must be an integer from 1 to 2100000000." >&2
  exit 2
fi
if [[ ! "$version_suffix" =~ ^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$ ]]; then
  echo "Internal version suffix must contain 1-64 safe filename characters." >&2
  exit 2
fi
if [[ ! -s "$keystore_path" ]]; then
  echo "Internal keystore does not exist or is empty." >&2
  exit 2
fi

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

resolve_build_tool() {
  local name="$1"
  local candidate
  for candidate in \
      "$build_tools/$name" \
      "$build_tools/$name.exe" \
      "$build_tools/$name.bat"; do
    if [[ -f "$candidate" ]]; then
      printf '%s\n' "$candidate"
      return 0
    fi
  done
  echo "Android build tool '$name' was not found under $build_tools." >&2
  return 1
}

zipalign="$(resolve_build_tool zipalign)"
apksigner="$(resolve_build_tool apksigner)"
aapt="$(resolve_build_tool aapt)"

unsigned_apk="${ANAALARM_INTERNAL_UNSIGNED_APK:-app/build/outputs/apk/internal/app-internal-unsigned.apk}"
mapping_file="${ANAALARM_INTERNAL_MAPPING_FILE:-app/build/outputs/mapping/internal/mapping.txt}"
test -s "$unsigned_apk"
test -s "$mapping_file"

scratch="$(mktemp -d "${RUNNER_TEMP:-/tmp}/anaalarm-installable.XXXXXX")"
cleanup() {
  rm -rf -- "$scratch"
}
trap cleanup EXIT

aligned_apk="$scratch/anaalarm-internal-aligned.apk"
signed_apk="$scratch/anaalarm-internal-signed.apk"
"$zipalign" -f 4 "$unsigned_apk" "$aligned_apk"
"$apksigner" sign \
  --ks "$keystore_path" \
  --ks-key-alias "$key_alias" \
  --ks-pass env:ANAALARM_INTERNAL_STORE_PASSWORD \
  --key-pass env:ANAALARM_INTERNAL_KEY_PASSWORD \
  --out "$signed_apk" \
  "$aligned_apk"

verify_output="$("$apksigner" verify --verbose --print-certs "$signed_apk")"
printf '%s\n' "$verify_output"
"$zipalign" -c -P 16 4 "$signed_apk"

reported_certs="$(sed -nE \
  's/^.*Signer(:| #[0-9]+) certificate SHA-256 digest:[[:space:]]*//p' \
  <<<"$verify_output")"
if [[ -z "$reported_certs" ]]; then
  echo "Could not read a signer certificate from apksigner output." >&2
  exit 1
fi
actual_cert=""
while IFS= read -r reported_cert; do
  normalized_cert="$(normalize_digest "$reported_cert")"
  if [[ ! "$normalized_cert" =~ ^[0-9a-f]{64}$ ]]; then
    echo "apksigner reported an invalid certificate SHA-256 digest." >&2
    exit 1
  fi
  if [[ -n "$actual_cert" && "$normalized_cert" != "$actual_cert" ]]; then
    echo "apksigner reported more than one signing certificate." >&2
    exit 1
  fi
  actual_cert="$normalized_cert"
done <<<"$reported_certs"
if [[ "$actual_cert" != "$expected_cert" ]]; then
  echo "Signed APK certificate does not match the configured internal certificate." >&2
  exit 1
fi

badging="$("$aapt" dump badging "$signed_apk")"
package_line="$(sed -n 's/^package: /&/p' <<<"$badging" | head -n 1)"
actual_package="$(sed -n "s/^package: name='\([^']*\)'.*/\1/p" <<<"$package_line")"
actual_version_code="$(sed -n \
  "s/^package: name='[^']*' versionCode='\([^']*\)'.*/\1/p" <<<"$package_line")"
actual_version_name="$(sed -n \
  "s/^package: name='[^']*' versionCode='[^']*' versionName='\([^']*\)'.*/\1/p" \
  <<<"$package_line")"
default_label="$(sed -n "s/^application-label:'\([^']*\)'/\1/p" <<<"$badging" | head -n 1)"

if [[ "$actual_package" != "com.anaalarm.internal" ]]; then
  echo "Unexpected internal application ID: $actual_package" >&2
  exit 1
fi
if [[ "$actual_version_code" != "$version_code" ]]; then
  echo "Unexpected internal version code: $actual_version_code" >&2
  exit 1
fi
if [[ "$actual_version_name" != *"-internal.$version_suffix" ]]; then
  echo "Unexpected internal version name: $actual_version_name" >&2
  exit 1
fi
if [[ "$default_label" != "AnaAlarm Internal" ]]; then
  echo "Unexpected internal application label: $default_label" >&2
  exit 1
fi
if grep -Fq 'application-debuggable' <<<"$badging"; then
  echo "Installable internal APK must not be debuggable." >&2
  exit 1
fi

commit_sha="${GITHUB_SHA:-$(git rev-parse HEAD)}"
short_sha="${commit_sha:0:12}"
artifact_dir="${ANAALARM_INSTALLABLE_ARTIFACT_DIR:-$repo_root/artifacts/installable}"
artifact_name="AnaAlarm-internal-${version_suffix}-${short_sha}.apk"
if [[ -e "$artifact_dir" ]]; then
  echo "Installable artifact directory must not already exist: $artifact_dir" >&2
  exit 1
fi
mkdir -p "$artifact_dir"
cp "$signed_apk" "$artifact_dir/$artifact_name"
(
  cd "$artifact_dir"
  sha256sum "$artifact_name" > "$artifact_name.sha256"
)

{
  printf 'artifact=%s\n' "$artifact_name"
  printf 'application_id=%s\n' "$actual_package"
  printf 'application_label=%s\n' "$default_label"
  printf 'version_code=%s\n' "$actual_version_code"
  printf 'version_name=%s\n' "$actual_version_name"
  printf 'commit_sha=%s\n' "$commit_sha"
  printf 'workflow_run_number=%s\n' "${GITHUB_RUN_NUMBER:-local}"
  printf 'workflow_run_attempt=%s\n' "${GITHUB_RUN_ATTEMPT:-local}"
  printf 'certificate_sha256=%s\n' "$actual_cert"
  printf 'build_type=internal\n'
  printf 'minified=true\n'
} > "$artifact_dir/build-info.txt"

printf 'Verified installable APK: %s\n' "$artifact_dir/$artifact_name"
