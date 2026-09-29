#!/usr/bin/env bash
# Prints, one per line and sorted, every git-tracked file whose change should be reflected in ONTOLOGY.md.
# Single source of truth for both stamp-ontology-hash.sh and check-ontology-staleness.sh.
set -euo pipefail
cd "$(git rev-parse --show-toplevel)"

git ls-files -z -- \
    'backend/src/main' \
    'runner/src/main' \
    'runner/e2b-shim' \
    'cli/src' \
    'contracts' \
    'sdk/python/agentstore' \
    'sdk/typescript/src' \
    'frontend/src/lib/types.ts' \
  | tr '\0' '\n' \
  | grep -v -E '(/_generated/|/generated/|\.md$|node_modules/)' \
  | LC_ALL=C sort
