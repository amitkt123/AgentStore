#!/usr/bin/env bash
# Regenerates the typed client from contracts/runtime-v1.yaml.
# Usage: sdk/python/regenerate.sh [output-dir]   (default: agentstore/_generated)
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
out="${1:-$here/agentstore/_generated}"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
cat > "$work/config.yml" <<'EOF'
project_name_override: agentstore_generated
package_name_override: _generated
EOF
"${OPENAPI_PYTHON_CLIENT:-openapi-python-client}" generate \
  --path "$here/../../contracts/runtime-v1.yaml" \
  --config "$work/config.yml" --meta none \
  --output-path "$work/gen" --overwrite
rm -rf "$out"
mkdir -p "$out"
cp -R "$work/gen/." "$out/"
find "$out" \( -name __pycache__ -o -name .ruff_cache \) -prune -exec rm -rf {} +
