#!/usr/bin/env bash
# Fails if any tracked source file changed since ONTOLOGY.md was last stamped.
set -euo pipefail
cd "$(git rev-parse --show-toplevel)"

FILE="${1:-ONTOLOGY.md}"
[[ -f "$FILE" ]] || { echo "error: $FILE not found" >&2; exit 1; }

STORED_HASH=$(grep -m1 '^ontology-source-hash:' "$FILE" | sed 's/^ontology-source-hash:[[:space:]]*//')
if [[ -z "$STORED_HASH" || "$STORED_HASH" == "PLACEHOLDER" ]]; then
  echo "error: $FILE has no stamped hash yet - run scripts/stamp-ontology-hash.sh" >&2
  exit 1
fi

CURRENT_HASH=$(scripts/ontology-hash.sh)
if [[ "$CURRENT_HASH" != "$STORED_HASH" ]]; then
  echo "STALE: $FILE does not match the source files it documents ($(scripts/ontology-tracked-files.sh | wc -l | tr -d ' ') tracked)." >&2
  echo "  1. Update the affected sections of $FILE to match your code changes." >&2
  echo "  2. Run: scripts/stamp-ontology-hash.sh" >&2
  exit 1
fi
echo "OK: $FILE is in sync with its tracked source files."
