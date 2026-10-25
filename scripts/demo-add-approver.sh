#!/usr/bin/env bash
# Dev only: puts an already signed-up buyer-side user into another buyer org as BUYER_MEMBER. Invitations need an
# emailed link and email.mode=log never prints links. Same approach as frontend/e2e/seed.ts.
# Buyer admins can approve too; use this when a demo needs a second person who approves without billing access.
# Only the approver's buyer-org membership moves; their own signup org is left with no members, which is fine for a demo.
# Usage: scripts/demo-add-approver.sh approver@example.com buyer-admin@example.com
set -euo pipefail
APPROVER="${1:?usage: demo-add-approver.sh APPROVER_EMAIL BUYER_ADMIN_EMAIL}"; ADMIN="${2:?usage: demo-add-approver.sh APPROVER_EMAIL BUYER_ADMIN_EMAIL}"
out="$(docker exec -i agentstore-pg psql -U agentstore -d agentstore -v ON_ERROR_STOP=1 -qtA \
  -v approver="$APPROVER" -v admin="$ADMIN" <<'SQL'
select m.org_id as target_org from org_membership m join app_user u on u.id = m.user_id
 where lower(u.email) = lower(:'admin') and m.role = 'BUYER_ADMIN' limit 1 \gset
update org_membership m set org_id = :'target_org', role = 'BUYER_MEMBER', title = 'Accounts manager'
  from app_user u, org o
 where m.user_id = u.id and o.id = m.org_id and o.type = 'BUYER'
   and lower(u.email) = lower(:'approver') and m.org_id <> :'target_org';
update app_user set last_org_id = :'target_org' where lower(email) = lower(:'approver');
select u.email, m.role, o.name from org_membership m join app_user u on u.id = m.user_id join org o on o.id = m.org_id
 where o.id = :'target_org' and lower(u.email) = lower(:'approver');
SQL
)" || { echo "no BUYER_ADMIN found for $ADMIN (sign up as a buyer first)" >&2; exit 1; }   # \gset fails on no rows
[ -n "$out" ] || { echo "$APPROVER is not a buyer-side user (sign up as a buyer first)" >&2; exit 1; }
echo "$out"
