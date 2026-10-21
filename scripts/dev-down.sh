#!/usr/bin/env bash
# Stops what dev-up.sh started (processes only; the docker stores keep running).
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
kill_tree() { # mvn and npm spawn children (the JVM, next), so stop the whole tree
  for child in $(pgrep -P "$1" 2>/dev/null); do kill_tree "$child"; done
  kill "$1" 2>/dev/null
}
for name in frontend backend runner tunnel; do
  pidfile="$ROOT/.dev/$name.pid"
  [ -f "$pidfile" ] || continue
  kill_tree "$(cat "$pidfile")" && echo "stopped $name"
  rm -f "$pidfile"
done
