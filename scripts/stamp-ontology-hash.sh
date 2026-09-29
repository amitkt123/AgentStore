#!/usr/bin/env bash
# Re-stamps ONTOLOGY.md's source hash. Run this only after ONTOLOGY.md reflects the code changes.
set -euo pipefail
cd "$(git rev-parse --show-toplevel)"

FILE="${1:-ONTOLOGY.md}"
[[ -f "$FILE" ]] || { echo "error: $FILE not found" >&2; exit 1; }

HASH=$(scripts/ontology-hash.sh)
TIMESTAMP=$(date -u +"%Y-%m-%dT%H:%M:%SZ")

sed -i.bak \
  -e "s/^ontology-source-hash:.*/ontology-source-hash: ${HASH}/" \
  -e "s/^ontology-generated-at:.*/ontology-generated-at: ${TIMESTAMP}/" \
  "$FILE"
rm -f "${FILE}.bak"

echo "Stamped $FILE"
echo "  files: $(scripts/ontology-tracked-files.sh | wc -l | tr -d ' ')"
echo "  hash:  $HASH"
echo "  at:    $TIMESTAMP"
