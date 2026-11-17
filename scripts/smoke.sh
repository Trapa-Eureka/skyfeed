#!/usr/bin/env bash
# Smoke test for the local stack (R-OPS-05): starts it with one command, then checks that every part answers.
# Usage: bash scripts/smoke.sh          The stack is left running; stop it with `docker compose down`.
set -euo pipefail
cd "$(dirname "$0")/.."

# Read port overrides from .env if there is one (same file Docker Compose reads).
if [ -f .env ]; then set -a; . ./.env; set +a; fi
REPLAY_PORT="${REPLAY_PORT:-18090}"

pass() { echo "ok    $1"; }
fail() { echo "FAIL  $1" >&2; exit 1; }

echo "Starting the stack..."
docker compose up -d --build --wait || fail "docker compose up"
pass "stack started and every service is healthy"

topics="$(docker compose exec -T kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --list)"
for topic in telemetry.v1 telemetry.dlq.v1 adsb.state.v1; do
  echo "$topics" | grep -qx "$topic" || fail "Kafka topic $topic is missing"
done
pass "Kafka topics exist: telemetry.v1, telemetry.dlq.v1, adsb.state.v1"

messages="$(docker compose exec -T mosquitto mosquitto_sub -h localhost -t 'sensors/+/telemetry' -C 5 -W 20 || true)"
count="$(echo "$messages" | grep -c '"sensorId"' || true)"
[ "$count" -eq 5 ] || fail "expected 5 telemetry messages on MQTT within 20 s, got $count"
pass "MQTT carries sensor telemetry (5 messages received)"

aircraft="$(curl -fsS "http://127.0.0.1:${REPLAY_PORT}/api/states/all" | node -e 'let s="";process.stdin.on("data",d=>s+=d).on("end",()=>console.log(JSON.parse(s).states.length))')"
[ "$aircraft" -gt 0 ] || fail "the replay server returned no aircraft"
pass "replay server returns aircraft states ($aircraft aircraft)"

docker compose exec -T postgres psql -U skyfeed -d skyfeed -tAc 'select 1' | grep -qx 1 || fail "PostgreSQL does not answer"
pass "PostgreSQL answers"

echo "Smoke test passed."
