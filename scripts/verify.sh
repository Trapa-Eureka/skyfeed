#!/usr/bin/env bash
# Runs every check of the repository on this machine. This is what a CI server would do; the project
# uses no hosted CI, so this script (also run by the pre-push git hook) is the gate.
# Usage: bash scripts/verify.sh          everything, including the stack smoke test (needs Docker running)
#        bash scripts/verify.sh --fast   skip the stack smoke test
set -euo pipefail
cd "$(dirname "$0")/.."

fast=false
[ "${1:-}" = "--fast" ] && fast=true

step() { printf '\n== %s\n' "$1"; }

step "Development hooks"
node --test .claude/hooks/*.test.mjs

step "Install (lockfile must be up to date)"
pnpm install --frozen-lockfile

step "Typecheck"
pnpm typecheck

step "Simulator and contract tests"
pnpm test

for service in ingest api gateway; do
  step "Service: $service"
  (cd "services/$service" && ./mvnw -B -q verify)
done

if $fast; then
  step "Stack smoke test: skipped (--fast)"
else
  step "Stack smoke test"
  docker info > /dev/null 2>&1 || { echo "Docker is not running. Start it, or use --fast to skip this step." >&2; exit 1; }
  # Leave the stack as it was found: stop it afterwards only if this script started it.
  was_running="$(docker compose ps -q 2> /dev/null | head -1)"
  bash scripts/smoke.sh
  [ -n "$was_running" ] || docker compose down
fi

printf '\nAll checks passed.\n'
