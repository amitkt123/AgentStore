#!/usr/bin/env bash
# Starts everything the demo needs: stores, runner (E2B), a public tunnel, Spring backend, frontend.
# Secrets: put E2B_API_KEY in .dev/env (git-ignored) as `E2B_API_KEY=e2b_...`, or export it before running.
# The tunnel is required: sandboxes run on E2B's cloud and reach Spring through CALLBACK_BASE_URL.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DEV="$ROOT/.dev"; mkdir -p "$DEV"
[ -f "$DEV/env" ] && set -a && . "$DEV/env" && set +a
RUNNER_SECRET="${RUNNER_SECRET:-dev-runner-secret}"

die() { echo "dev-up: $*" >&2; exit 1; }
need() { command -v "$1" >/dev/null || die "$1 is not installed. $2"; }
need docker "Start Colima."; need gitleaks "brew install gitleaks"; need cloudflared "brew install cloudflared"
need node ""; need mvn ""
[ -n "${E2B_API_KEY:-}" ] || die "E2B_API_KEY is not set (put it in $DEV/env)."
for port in 8080 8090 3000; do
  lsof -iTCP:$port -sTCP:LISTEN >/dev/null 2>&1 && die "port $port is already in use. Stop what is on it (or run scripts/dev-down.sh)."
done

echo "stores..."
(cd "$ROOT" && docker compose up -d seaweedfs redis)
docker start agentstore-pg >/dev/null 2>&1 || die "no Postgres container named agentstore-pg (see backend/.env.example, port 5433)."

wait_for() { # url, name, log
  for _ in $(seq 1 120); do curl -sf -m2 "$1" >/dev/null 2>&1 && return 0; sleep 2; done
  die "$2 did not come up; see $3"
}
start() { # name, dir, command...
  local name=$1 dir=$2; shift 2
  (cd "$dir" && nohup "$@" >"$DEV/$name.log" 2>&1 & echo $! >"$DEV/$name.pid")
}

echo "tunnel..."
start tunnel "$ROOT" cloudflared tunnel --url http://localhost:8080 --no-autoupdate
URL=""
for _ in $(seq 1 30); do
  URL="$(grep -o 'https://[a-z0-9-]*\.trycloudflare\.com' "$DEV/tunnel.log" | head -1 || true)"; [ -n "$URL" ] && break; sleep 1
done
[ -n "$URL" ] || die "no tunnel URL; see $DEV/tunnel.log"
echo "callback URL: $URL"

echo "runner..."
(cd "$ROOT/runner/e2b-shim" && npm ci --silent)
start runner "$ROOT/runner" env RUNNER_SECRET="$RUNNER_SECRET" E2B_API_KEY="$E2B_API_KEY" SPRING_BASE_URL=http://localhost:8080 mvn -q spring-boot:run

echo "backend..."
start backend "$ROOT/backend" env CALLBACK_BASE_URL="$URL" RUNNER_SECRET="$RUNNER_SECRET" RUNNER_BASE_URL=http://localhost:8090 mvn -q spring-boot:run -Dspring-boot.run.profiles=dev
wait_for http://localhost:8080/actuator/health backend "$DEV/backend.log"

echo "frontend..."
[ -d "$ROOT/frontend/node_modules" ] || (cd "$ROOT/frontend" && npm ci --silent)
start frontend "$ROOT/frontend" npm run dev
wait_for http://localhost:3000 frontend "$DEV/frontend.log"

echo "up. portal http://localhost:3000  api http://localhost:8080  callback $URL"
echo "logs: $DEV/*.log   stop: scripts/dev-down.sh"
