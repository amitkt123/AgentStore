#!/usr/bin/env bash
set -euo pipefail

FILE="${1:-ONTOLOGY.md}"

if [[ ! -f "$FILE" ]]; then
  echo "error: $FILE not found" >&2
  exit 1
fi

STORED_HASH=$(grep -m1 '^ontology-source-hash:' "$FILE" | sed 's/^ontology-source-hash:[[:space:]]*//')
TRACKED_LINE=$(grep -m1 '^ontology-tracked-files:' "$FILE" | sed 's/^ontology-tracked-files:[[:space:]]*//')

if [[ -z "$STORED_HASH" || "$STORED_HASH" == "PLACEHOLDER" ]]; then
  echo "error: $FILE has no stamped hash yet — run stamp-ontology-hash.sh once after generating it." >&2
  exit 1
fi

IFS=',' read -ra FILES <<< "$TRACKED_LINE"

CURRENT_HASH=$(
  for f in "${FILES[@]}"; do
    if [[ -f "$f" ]]; then
      printf '%s:' "$f"
      sha256sum "$f" | cut -d' ' -f1
    fi
  done | sort | sha256sum | cut -d' ' -f1
)

if [[ "$CURRENT_HASH" != "$STORED_HASH" ]]; then
  echo "STALE: $FILE no longer matches its tracked source files." >&2
  echo "  One or more of these have changed since the last stamp:" >&2
  for f in "${FILES[@]}"; do
    echo "    - $f" >&2
  done
  echo "" >&2
  echo "  Re-run the ontology generation prompt, then:" >&2
  echo "    scripts/stamp-ontology-hash.sh $FILE" >&2
  exit 1
fi

echo "OK: $FILE is in sync with its tracked source files."
