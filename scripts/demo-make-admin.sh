#!/usr/bin/env bash
# Dev only: makes a developer-side user a platform ADMIN. Password signups are normally promoted only after a password
# reset from an ADMIN_EMAILS address, which needs a real inbox (email.mode=log never prints links).
# Usage: scripts/demo-make-admin.sh admin@example.com
set -euo pipefail
EMAIL="${1:?usage: demo-make-admin.sh EMAIL}"
docker exec agentstore-pg psql -U agentstore -d agentstore -v ON_ERROR_STOP=1 -tA -c \
  "update org_membership m set role='ADMIN' from app_user u, org o
   where m.user_id = u.id and o.id = m.org_id and o.type = 'DEVELOPER' and lower(u.email) = lower('$EMAIL')
   returning u.email, m.role"
