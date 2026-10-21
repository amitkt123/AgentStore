#!/usr/bin/env bash
# Dev only: adds AMOUNT to a buyer org's wallet through the admin adjustments endpoint (there is no top-up
# endpoint until payments exist). Credits on top of the current balance; a negative AMOUNT debits.
# Needs the backend running and an ADMIN user (scripts/demo-make-admin.sh).
# Usage: scripts/demo-fund-wallet.sh buyer@example.com [amount]
# Env: ADMIN_EMAIL (demo-admin@example.com), ADMIN_PASSWORD (Demo-pass-12345), API_URL (http://localhost:8080)
set -euo pipefail
EMAIL="${1:?usage: demo-fund-wallet.sh EMAIL [AMOUNT]}"; AMOUNT="${2:-10000}"
[[ "$AMOUNT" =~ ^-?[0-9]+(\.[0-9]{1,2})?$ ]] || { echo "AMOUNT must be a number, got '$AMOUNT'" >&2; exit 1; }
API_URL="${API_URL:-http://localhost:8080}"
export ADMIN_EMAIL="${ADMIN_EMAIL:-demo-admin@example.com}" ADMIN_PASSWORD="${ADMIN_PASSWORD:-Demo-pass-12345}"

# Read-only lookup of the buyer org (the API has no lookup by user email).
org="$(docker exec -i agentstore-pg psql -U agentstore -d agentstore -v ON_ERROR_STOP=1 -qtA -v email="$EMAIL" <<'SQL'
select m.org_id
from org_membership m join app_user u on u.id = m.user_id join org o on o.id = m.org_id
where lower(u.email) = lower(:'email') and o.type = 'BUYER'
limit 1;
SQL
)"
[ -n "$org" ] || { echo "no buyer org found for $EMAIL (sign up as a buyer first)" >&2; exit 1; }

# POST JSON ($3) to $2 with optional bearer token ($1); prints the body, fails with status and body on non-2xx.
post_json() {
  local resp status
  resp="$(curl -sS -w '\n%{http_code}' -X POST "$2" -H 'Content-Type: application/json' \
    ${1:+-H "Authorization: Bearer $1"} --data-binary "$3")" || { echo "request to $2 failed" >&2; exit 1; }
  status="${resp##*$'\n'}"; resp="${resp%$'\n'*}"
  [[ "$status" == 2* ]] || { echo "POST $2 -> HTTP $status: $resp" >&2; exit 1; }
  printf '%s' "$resp"
}

login="$(python3 -c 'import json,os; print(json.dumps({"email": os.environ["ADMIN_EMAIL"], "password": os.environ["ADMIN_PASSWORD"]}))' \
  | { read -r b; post_json "" "$API_URL/api/v1/auth/login" "$b"; })"
token="$(printf '%s' "$login" | python3 -c 'import json,sys; print(json.load(sys.stdin)["token"])')"

body="$(python3 -c 'import json,sys; print(json.dumps({"requestId": sys.argv[1], "amount": sys.argv[2], "note": "demo-fund-wallet.sh"}))' \
  "$(uuidgen)" "$AMOUNT")"
wallet="$(post_json "$token" "$API_URL/api/v1/admin/orgs/$org/wallet/adjustments" "$body")"
printf '%s' "$wallet" | python3 -c 'import json,sys; w=json.load(sys.stdin); print("org '"$org"' balance:", w["balance"])'
