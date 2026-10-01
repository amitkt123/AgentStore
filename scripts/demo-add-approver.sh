#!/usr/bin/env bash
# Dev only: puts an already signed-up buyer-side user into another buyer org as BUYER_APPROVER. Invitations need an
# emailed link and email.mode=log never prints links. Same approach as frontend/e2e/seed.ts.
# Usage: scripts/demo-add-approver.sh approver@example.com buyer-admin@example.com
set -euo pipefail
APPROVER="${1:?usage: demo-add-approver.sh APPROVER_EMAIL BUYER_ADMIN_EMAIL}"; ADMIN="${2:?usage: demo-add-approver.sh APPROVER_EMAIL BUYER_ADMIN_EMAIL}"
docker exec -i agentstore-pg psql -U agentstore -d agentstore -v ON_ERROR_STOP=1 -tA <<SQL
with target as (
  select m.org_id from org_membership m join app_user u on u.id = m.user_id
  where lower(u.email) = lower('$ADMIN') and m.role = 'BUYER_ADMIN' limit 1),
me as (select id from app_user where lower(email) = lower('$APPROVER'))
update org_membership set org_id = (select org_id from target), role = 'BUYER_APPROVER', title = 'Accounts manager'
 where user_id = (select id from me) and org_id <> (select org_id from target);
update app_user set last_org_id = (select m.org_id from org_membership m join app_user a on a.id = m.user_id where lower(a.email) = lower('$APPROVER') limit 1)
 where lower(email) = lower('$APPROVER');
select u.email, m.role, o.name from org_membership m join app_user u on u.id = m.user_id join org o on o.id = m.org_id
 where lower(u.email) in (lower('$APPROVER'), lower('$ADMIN'));
SQL
