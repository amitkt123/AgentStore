#!/usr/bin/env bash
# Dev only: gives a buyer org a wallet balance (there is no top-up endpoint until payments exist).
# Usage: scripts/demo-fund-wallet.sh buyer@example.com [amount]
set -euo pipefail
EMAIL="${1:?usage: demo-fund-wallet.sh EMAIL [AMOUNT]}"; AMOUNT="${2:-10000}"
psql_q() { docker exec agentstore-pg psql -U agentstore -d agentstore -v ON_ERROR_STOP=1 -tA -c "$1"; }
psql_q "insert into wallet (id, org_id, balance, reserved)
        select gen_random_uuid(), m.org_id, $AMOUNT, 0
        from org_membership m join app_user u on u.id = m.user_id
        where lower(u.email) = lower('$EMAIL')
        on conflict (org_id) do update set balance = $AMOUNT, updated_at = now()
        returning org_id, balance"
