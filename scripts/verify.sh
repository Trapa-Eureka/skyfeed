#!/usr/bin/env bash
# Runs every check of the repository on this machine. This is what a CI server would do; the project
# uses no hosted CI, so this script (also run by the pre-push git hook) is the gate.
# Usage: bash scripts/verify.sh          everything, including the stack smoke test (needs Docker running)
#        bash scripts/verify.sh --fast   skip the stack smoke test, the console end-to-end tests and the failure drills
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

step "Simulator, contract and console unit tests"
pnpm test

# The service tests start Kafka, PostgreSQL and Mosquitto containers of their own. Together with a running stack
# that is more memory than a small Docker setup has (containers get killed), so the stack is paused meanwhile.
# Its data is kept, and it is brought back for the smoke test below.
stack_was_running=""
if docker info > /dev/null 2>&1; then
  stack_was_running="$(docker compose ps -q 2> /dev/null | head -1)"
  if [ -n "$stack_was_running" ]; then
    step "Pausing the running stack for the service tests"
    docker compose stop
  fi
fi

for service in ingest api gateway; do
  step "Service: $service"
  (cd "services/$service" && ./mvnw -B -q verify)
done

if $fast; then
  step "Stack smoke test, console end-to-end tests and failure drills: skipped (--fast)"
  if [ -n "$stack_was_running" ]; then
    step "Starting the stack again"
    docker compose up -d --wait
  fi
else
  step "Stack smoke test"
  docker info > /dev/null 2>&1 || { echo "Docker is not running. Start it, or use --fast to skip this step." >&2; exit 1; }
  # Leave the stack as it was found: stop it afterwards only if it was not running before.
  bash scripts/smoke.sh

  step "Console end-to-end tests (a real browser against the running stack)"
  pnpm --filter @skyfeed/web exec playwright install chromium > /dev/null
  pnpm --filter @skyfeed/web e2e

  step "Failure drills (Kafka, PostgreSQL and the MQTT broker are stopped and started; sensors go silent)"
  bash scripts/drill.sh

  [ -n "$stack_was_running" ] || docker compose down
fi

printf '\nAll checks passed.\n'
