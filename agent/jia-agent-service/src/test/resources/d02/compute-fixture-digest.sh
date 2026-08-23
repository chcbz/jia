#!/usr/bin/env bash
set -euo pipefail
export LC_ALL=C

script_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
root=$(git -C "$script_dir" rev-parse --show-toplevel)
manifest="$script_dir/fixture-files.txt"

if ! grep -Ev '^[[:space:]]*(#|$)' "$manifest" | sort -c -u; then
  echo "fixture manifest must be byte-sorted and unique: $manifest" >&2
  exit 1
fi

canonical_stream=$(mktemp)
trap 'rm -f "$canonical_stream"' EXIT
while IFS= read -r path; do
  [[ -z "$path" || "$path" =~ ^[[:space:]]*# ]] && continue
  file="$root/$path"
  if [[ ! -f "$file" ]]; then
    echo "fixture file is missing: $path" >&2
    exit 1
  fi
  file_digest=$(sha256sum -- "$file" | awk '{print $1}')
  printf '%s\0%s\n' "$path" "$file_digest" >> "$canonical_stream"
done < "$manifest"

digest=$(sha256sum -- "$canonical_stream" | awk '{print $1}')
if [[ ! "$digest" =~ ^[0-9a-f]{64}$ ]]; then
  echo "invalid fixture digest: $digest" >&2
  exit 1
fi
printf '%s\n' "$digest"
