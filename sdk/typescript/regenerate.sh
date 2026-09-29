#!/usr/bin/env bash
# Regenerates the typed schema from contracts/runtime-v1.yaml.
# Usage: sdk/typescript/regenerate.sh [output-file]   (default: src/generated/schema.d.ts)
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
out="${1:-$here/src/generated/schema.d.ts}"
mkdir -p "$(dirname "$out")"
"$here/node_modules/.bin/openapi-typescript" "$here/../../contracts/runtime-v1.yaml" -o "$out"
