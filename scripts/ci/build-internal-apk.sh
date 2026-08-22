#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$repo_root"

version_code="${ANAALARM_INTERNAL_VERSION_CODE:?Internal version code is required}"
version_suffix="${ANAALARM_INTERNAL_VERSION_SUFFIX:?Internal version suffix is required}"

if [[ ! "$version_code" =~ ^[0-9]+$ ]] ||
    (( version_code < 1 || version_code > 2100000000 )); then
  echo "Internal version code must be an integer from 1 to 2100000000." >&2
  exit 2
fi
if [[ ! "$version_suffix" =~ ^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$ ]]; then
  echo "Internal version suffix must contain 1-64 safe filename characters." >&2
  exit 2
fi

bash ./gradlew assembleInternal \
  --dependency-verification=strict --no-parallel --stacktrace

unsigned_apk="app/build/outputs/apk/internal/app-internal-unsigned.apk"
mapping_file="app/build/outputs/mapping/internal/mapping.txt"
test -s "$unsigned_apk"
test -s "$mapping_file" # R8 mapping proves the internal variant was minified.

printf 'Built minified internal APK: %s\n' "$unsigned_apk"
