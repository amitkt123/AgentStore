#!/usr/bin/env bash
# Creates the demo agent through the CLI (so it gets a real agentId), swaps in examples/demo-agent's code, then pushes it.
# Needs: the stack running (scripts/dev-up.sh), the CLI linked (cd cli && npm run build && npm link), and `agentstore login`.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DIR="${1:-$ROOT/.dev/demo-agent-$(date +%s)}"
EXAMPLE="${EXAMPLE:-demo-agent}"   # a folder under examples/
NAME="${DEMO_AGENT_NAME:-Demo Agent}"

mkdir -p "$DIR" && cd "$DIR"   # an existing project dir is reused; set VERSION to push a new version of it
if [ -f agentstore.yaml ]; then
  echo "Reusing the agent in $DIR (pushing a new version)."
else
  agentstore init --python --name "$NAME" --category demo --description "A small demo agent."
fi
cp "$ROOT/examples/$EXAMPLE/agent/main.py" agent/main.py
# keep the generated name, version, runtime and entrypoint; take the scopes, resources and the rest from the example
python3 - "$ROOT/examples/$EXAMPLE/manifest.json" <<'PY'
import json, os, sys
mine = json.load(open("manifest.json"))
mine.update(json.load(open(sys.argv[1])))
mine["version"] = os.environ.get("VERSION", mine["version"])
json.dump(mine, open("manifest.json", "w"), indent=2)
PY
agentstore validate
agentstore push --wait
echo "Pushed from $DIR. Next: agentstore sandbox run --follow, then submit the version in the portal Studio."
