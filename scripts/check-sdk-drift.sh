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

exit $status
