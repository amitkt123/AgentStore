#!/usr/bin/env bash
set -euo pipefail

FILE="${1:-ONTOLOGY.md}"

if [[ ! -f "$FILE" ]]; then
  echo "error: $FILE not found" >&2
  exit 1
fi

TRACKED_LINE=$(grep -m1 '^ontology-tracked-files:' "$FILE" || true)
if [[ -z "$TRACKED_LINE" ]]; then
  echo "error: no 'ontology-tracked-files:' line found in $FILE header" >&2
  exit 1
fi

TRACKED_FILES=${TRACKED_LINE#ontology-tracked-files:}
TRACKED_FILES=$(echo "$TRACKED_FILES" | xargs)
IFS=',' read -ra FILES <<< "$TRACKED_FILES"

MISSING=0
for f in "${FILES[@]}"; do
  if [[ ! -f "$f" ]]; then
    echo "warning: tracked file missing: $f" >&2
    MISSING=1
  fi
done

HASH=$(
  for f in "${FILES[@]}"; do
    if [[ -f "$f" ]]; then
      printf '%s:' "$f"
      sha256sum "$f" | cut -d' ' -f1
    fi
  done | sort | sha256sum | cut -d' ' -f1
)

TIMESTAMP=$(date -u +"%Y-%m-%dT%H:%M:%SZ")

sed -i.bak \
  -e "s/^ontology-source-hash:.*/ontology-source-hash: ${HASH}/" \
  -e "s/^ontology-generated-at:.*/ontology-generated-at: ${TIMESTAMP}/" \
  "$FILE"
rm -f "${FILE}.bak"

echo "Stamped $FILE"
echo "  hash: $HASH"
echo "  at:   $TIMESTAMP"
if [[ "$MISSING" -eq 1 ]]; then
  echo "  note: some tracked files were missing (see warnings above)." >&2
fi
