#!/usr/bin/env bash
# Verifies that a minified release/internal build still keeps DataExport kotlinx.serialization
# descriptors needed for Settings export/import (audit H1 / W0.1–W0.2).
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$repo_root"

mapping_file="${1:-app/build/outputs/mapping/release/mapping.txt}"
apk_file="${2:-app/build/outputs/apk/release/app-release-unsigned.apk}"

if [[ ! -s "$mapping_file" ]]; then
  echo "Missing R8 mapping at $mapping_file — assembleRelease/assembleInternal first." >&2
  exit 1
fi
if [[ ! -s "$apk_file" ]]; then
  echo "Missing minified APK at $apk_file." >&2
  exit 1
fi

# Keep rules must cover the data package (not only ai.**).
if ! grep -Eq 'com\.anaalarm\.data\.\*\*' app/proguard-rules.pro; then
  echo "proguard-rules.pro must keep kotlinx.serialization members under com.anaalarm.data.**" >&2
  exit 1
fi

scratch="$(mktemp -d "${RUNNER_TEMP:-/tmp}/anaalarm-minify-export.XXXXXX")"
cleanup() { rm -rf -- "$scratch"; }
trap cleanup EXIT

unzip -qo "$apk_file" 'classes*.dex' -d "$scratch"
dex_strings="$scratch/dex-strings.txt"
: > "$dex_strings"
shopt -s nullglob
for dex in "$scratch"/classes*.dex; do
  strings "$dex" >> "$dex_strings"
done
shopt -u nullglob

# Kept nested serializers retain their kotlinx class names in the dex string table.
if ! grep -Fq 'DataExport$Payload$$serializer' "$dex_strings"; then
  echo "Minified APK dex missing DataExport\$Payload\$\$serializer — keep rules failed." >&2
  exit 1
fi
if ! grep -Fq 'anaalarm-export' "$dex_strings"; then
  echo "Minified APK dex missing export format marker." >&2
  exit 1
fi

if ! grep -Eq 'com\.anaalarm\.data\.DataExport' "$mapping_file"; then
  echo "R8 mapping does not mention DataExport — unexpected for kept serializers." >&2
  exit 1
fi

echo "Minified DataExport serializers appear retained ($apk_file)."
