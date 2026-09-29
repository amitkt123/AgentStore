#!/usr/bin/env bash
# Prints the combined sha256 of all files listed by ontology-tracked-files.sh.
set -euo pipefail
cd "$(git rev-parse --show-toplevel)"

sha() { if command -v sha256sum >/dev/null; then sha256sum "$@"; else shasum -a 256 "$@"; fi; }

scripts/ontology-tracked-files.sh | while IFS= read -r f; do
  printf '%s:' "$f"
  sha "$f" | cut -d' ' -f1
done | sha | cut -d' ' -f1
