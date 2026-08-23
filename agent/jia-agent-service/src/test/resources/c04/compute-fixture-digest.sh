#!/usr/bin/env bash
set -euo pipefail
export LC_ALL=C
script_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
root=$(git -C "$script_dir" rev-parse --show-toplevel)
manifest="$script_dir/fixture-files.txt"
grep -Ev '^[[:space:]]*(#|$)' "$manifest" | sort -c -u
stream=$(mktemp)
trap 'rm -f "$stream"' EXIT
while IFS= read -r path; do
  [[ -z "$path" || "$path" =~ ^[[:space:]]*# ]] && continue
  file="$root/$path"
  [[ -f "$file" ]] || { echo "fixture file is missing: $path" >&2; exit 1; }
  printf '%s\0%s\n' "$path" "$(sha256sum -- "$file" | awk '{print $1}')" >> "$stream"
done < "$manifest"
sha256sum -- "$stream" | awk '{print $1}'
