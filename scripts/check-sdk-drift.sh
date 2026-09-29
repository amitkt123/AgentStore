#!/usr/bin/env bash
# Fails if a committed generated SDK client differs from what its regenerate script produces today.
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
scratch="$(mktemp -d)"
trap 'rm -rf "$scratch"' EXIT
status=0

"$root/sdk/python/regenerate.sh" "$scratch/py" >/dev/null
if ! diff -r -x __pycache__ "$scratch/py" "$root/sdk/python/agentstore/_generated" >/dev/null; then
  echo "Python SDK client is stale. Run sdk/python/regenerate.sh and commit the result." >&2
  status=1
fi

"$root/sdk/typescript/regenerate.sh" "$scratch/schema.d.ts" >/dev/null
if ! diff "$scratch/schema.d.ts" "$root/sdk/typescript/src/generated/schema.d.ts" >/dev/null; then
  echo "TypeScript SDK client is stale. Run sdk/typescript/regenerate.sh and commit the result." >&2
  status=1
fi

exit $status
