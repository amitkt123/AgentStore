#!/usr/bin/env bash
# Dev stopgap for `agentstore push` (WS5c): zips a directory, uploads it and prints the queued build.
# Usage: AGENTSTORE_TOKEN=<developer JWT> scripts/dev-push-bundle.sh <agentId> <bundleDir>
set -euo pipefail

AGENT_ID="${1:?agentId}"
DIR="${2:?bundle directory}"
API="${AGENTSTORE_API:-http://localhost:8080}"
: "${AGENTSTORE_TOKEN:?set AGENTSTORE_TOKEN to a developer access token}"

ZIP="$(mktemp -t agentstore-bundle).zip"
trap 'rm -f "$ZIP"' EXIT
(cd "$DIR" && zip -qr "$ZIP" . -x '.git/*' -x 'node_modules/*' -x '__pycache__/*')
SHA="$(shasum -a 256 "$ZIP" | cut -d' ' -f1)"
SIZE="$(wc -c < "$ZIP" | tr -d ' ')"

auth=(-H "Authorization: Bearer $AGENTSTORE_TOKEN" -H "Content-Type: application/json")
UPLOAD="$(curl -fsS "${auth[@]}" -d "{\"sha256\":\"$SHA\",\"sizeBytes\":$SIZE}" "$API/api/v1/agents/$AGENT_ID/bundles/uploads")"
BUNDLE_ID="$(printf '%s' "$UPLOAD" | python3 -c 'import json,sys; print(json.load(sys.stdin)["bundleId"])')"
URL="$(printf '%s' "$UPLOAD" | python3 -c 'import json,sys; print(json.load(sys.stdin)["uploadUrl"])')"
curl -fsS -X PUT --upload-file "$ZIP" "$URL" >/dev/null
curl -sS "${auth[@]}" -d "{\"sha256\":\"$SHA\"}" "$API/api/v1/agents/$AGENT_ID/bundles/$BUNDLE_ID/finalize"
echo
