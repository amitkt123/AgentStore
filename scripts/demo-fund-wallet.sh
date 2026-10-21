#!/usr/bin/env bash
# Dev only: gives a buyer org a wallet balance (there is no top-up endpoint until payments exist).
# Usage: scripts/demo-fund-wallet.sh buyer@example.com [amount]
set -euo pipefail
EMAIL="${1:?usage: demo-fund-wallet.sh EMAIL [AMOUNT]}"; AMOUNT="${2:-10000}"
[[ "$AMOUNT" =~ ^[0-9]+(\.[0-9]{1,2})?$ ]] || { echo "AMOUNT must be a number, got '$AMOUNT'" >&2; exit 1; }
out="$(docker exec -i agentstore-pg psql -U agentstore -d agentstore -v ON_ERROR_STOP=1 -qtA \
  -v email="$EMAIL" -v amount="$AMOUNT" <<'SQL'
insert into wallet (id, org_id, balance, reserved)
select gen_random_uuid(), m.org_id, :'amount'::numeric, 0
from org_membership m join app_user u on u.id = m.user_id join org o on o.id = m.org_id
where lower(u.email) = lower(:'email') and o.type = 'BUYER'
on conflict (org_id) do update set balance = excluded.balance, updated_at = now()
returning org_id, balance;
SQL
)"
[ -n "$out" ] || { echo "no buyer org found for $EMAIL (sign up as a buyer first)" >&2; exit 1; }
echo "$out"
