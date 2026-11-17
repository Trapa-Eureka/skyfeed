#!/usr/bin/env bash
# Smoke test for the local stack (R-OPS-05): starts it with one command, then checks that every part answers.
# Usage: bash scripts/smoke.sh          The stack is left running; stop it with `docker compose down`.
set -euo pipefail
cd "$(dirname "$0")/.."

# Read port overrides from .env if there is one (same file Docker Compose reads).
if [ -f .env ]; then set -a; . ./.env; set +a; fi
REPLAY_PORT="${REPLAY_PORT:-18090}"
API_PORT="${API_PORT:-8080}"
API="http://127.0.0.1:${API_PORT}"
GATEWAY_PORT="${GATEWAY_PORT:-8082}"
WEB_PORT="${WEB_PORT:-3000}"

pass() { echo "ok    $1"; }
fail() { echo "FAIL  $1" >&2; exit 1; }

echo "Starting the stack..."
docker compose up -d --build --wait || fail "docker compose up"
pass "stack started and every service is healthy"

topics="$(docker compose exec -T kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --list)"
for topic in telemetry.v1 telemetry.dlq.v1 adsb.state.v1 sensor.status.v1; do
  echo "$topics" | grep -qx "$topic" || fail "Kafka topic $topic is missing"
done
pass "Kafka topics exist: telemetry.v1, telemetry.dlq.v1, adsb.state.v1, sensor.status.v1"

messages="$(docker compose exec -T mosquitto mosquitto_sub -h localhost -t 'sensors/+/telemetry' -C 5 -W 20 || true)"
count="$(echo "$messages" | grep -c '"sensorId"' || true)"
[ "$count" -eq 5 ] || fail "expected 5 telemetry messages on MQTT within 20 s, got $count"
pass "MQTT carries sensor telemetry (5 messages received)"

aircraft="$(curl -fsS "http://127.0.0.1:${REPLAY_PORT}/api/states/all" | node -e 'let s="";process.stdin.on("data",d=>s+=d).on("end",()=>console.log(JSON.parse(s).states.length))')"
[ "$aircraft" -gt 0 ] || fail "the replay server returned no aircraft"
pass "replay server returns aircraft states ($aircraft aircraft)"

consume() { # topic, how many records to wait for
  docker compose exec -T kafka /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server localhost:9092 \
    --topic "$1" --from-beginning --max-messages "$2" --timeout-ms 30000 --formatter-property print.key=true 2> /dev/null || true
}

records="$(consume telemetry.v1 5)"
[ "$(echo "$records" | grep -c '"sensorId"' || true)" -eq 5 ] || fail "expected 5 records on Kafka topic telemetry.v1 within 30 s"
echo "$records" | head -1 | grep -qE '^gs-[a-z]+[[:space:]]' || fail "telemetry.v1 records are not keyed by sensor id"
pass "ingest forwards MQTT telemetry to Kafka topic telemetry.v1, keyed by sensor id"

records="$(consume adsb.state.v1 5)"
[ "$(echo "$records" | grep -c '"icao24"' || true)" -eq 5 ] || fail "expected 5 records on Kafka topic adsb.state.v1 within 30 s"
echo "$records" | head -1 | grep -qE '^[0-9a-f]{6}[[:space:]]' || fail "adsb.state.v1 records are not keyed by ICAO address"
pass "ingest publishes aircraft states to Kafka topic adsb.state.v1, keyed by ICAO address"

marker="smoke-$(date +%s)-$$"
docker compose exec -T mosquitto mosquitto_pub -h localhost -q 1 -t sensors/gs-smoke/telemetry -m "{\"sensorId\":\"gs-smoke\",\"note\":\"$marker\"}"
dead=""
for _ in 1 2 3 4 5 6 7 8 9 10; do
  dead="$(docker compose exec -T kafka /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server localhost:9092 \
    --topic telemetry.dlq.v1 --from-beginning --timeout-ms 3000 --formatter-property print.headers=true 2> /dev/null | grep "$marker" || true)"
  [ -n "$dead" ] && break
done
echo "$dead" | grep -q "reason:does not match telemetry.v1" || fail "an invalid reading did not reach telemetry.dlq.v1 with a reason"
pass "ingest sends an invalid reading to telemetry.dlq.v1 with the reason"

# Reads JSON on stdin and prints the value of a JavaScript expression over it (the parsed JSON is `d`).
jq_node() { node -e 'let s="";process.stdin.on("data",c=>s+=c).on("end",()=>{const d=JSON.parse(s);console.log(eval(process.argv[1]))})' "$1"; }

online=0
for _ in $(seq 1 30); do
  online="$(curl -fsS "$API/api/v1/sensors" | jq_node 'd.filter(s => s.sensorId.startsWith("gs-") && s.status === "online" && s.latest).length' || echo 0)"
  [ "$online" -ge 5 ] && break
  sleep 1
done
[ "$online" -ge 5 ] || fail "expected 5 online sensors from GET /api/v1/sensors within 30 s, got $online"
pass "api stores telemetry and serves sensors with status ($online online)"

series="$(curl -fsS "$API/api/v1/sensors/gs-iloilo/telemetry?limit=3" | jq_node 'd.length')"
[ "$series" -eq 3 ] || fail "expected 3 readings from GET /api/v1/sensors/gs-iloilo/telemetry?limit=3, got $series"
pass "api serves a telemetry series"

icao=""
for _ in $(seq 1 30); do
  icao="$(curl -fsS "$API/api/v1/aircraft" | jq_node 'd.length ? d[0].icao24 : ""' || true)"
  [ -n "$icao" ] && break
  sleep 1
done
[ -n "$icao" ] || fail "GET /api/v1/aircraft returned no current aircraft within 30 s"
points="$(curl -fsS "$API/api/v1/aircraft/$icao/track" | jq_node 'd.length')"
[ "$points" -ge 1 ] || fail "GET /api/v1/aircraft/$icao/track returned no positions"
pass "api stores aircraft states and serves current aircraft and a track ($icao: $points positions)"

paths="$(curl -fsS "$API/v3/api-docs" | jq_node 'Object.keys(d.paths).length')"
[ "$paths" -eq 5 ] || fail "expected 5 paths in the OpenAPI document, got $paths"
pass "api serves its OpenAPI document"

live="$(node scripts/ws-check.mjs "ws://127.0.0.1:${GATEWAY_PORT}/ws/v1")" || fail "live feed: $live"
pass "gateway pushes hello, heartbeat, aircraft in the subscribed area and telemetry over WebSocket ($live)"

metric() { # url, text the metrics must contain. A Kafka consumer reports its lag only after its first fetch, so allow some time.
  for _ in $(seq 1 30); do
    if curl -fsS "$1" 2> /dev/null | grep -qF "$2"; then return 0; fi
    sleep 1
  done
  fail "$1 does not offer the metric $2"
}
metric "http://127.0.0.1:${INGEST_PORT:-8081}/q/metrics" 'skyfeed_source_last_message_age_seconds{source="mqtt"}'
metric "http://127.0.0.1:${INGEST_PORT:-8081}/q/metrics" 'skyfeed_source_last_message_age_seconds{source="adsb"}'
metric "$API/actuator/prometheus" 'skyfeed_kafka_last_record_age_seconds{topic="telemetry.v1"}'
metric "$API/actuator/prometheus" 'kafka_consumer_fetch_manager_records_lag'
metric "$API/actuator/prometheus" 'skyfeed_outbox_waiting'
metric "http://127.0.0.1:${GATEWAY_PORT}/q/metrics" 'skyfeed_kafka_last_record_age_seconds{topic="telemetry.v1"}'
metric "http://127.0.0.1:${GATEWAY_PORT}/q/metrics" 'kafka_consumer_fetch_manager_records_lag'
pass "every service offers metrics: age of the last message per source or topic, and consumer lag"

curl -fsS "http://127.0.0.1:${WEB_PORT}/" | grep -q "SkyFeed" || fail "the console page does not load"
sensors_via_web="$(curl -fsS "http://127.0.0.1:${WEB_PORT}/api/v1/sensors" | jq_node 'd.length')"
[ "$sensors_via_web" -ge 5 ] || fail "the console's /api/v1 proxy returned $sensors_via_web sensors"
pass "web console loads and reaches the api service through its own /api/v1 path"

docker compose exec -T postgres psql -U skyfeed -d skyfeed -tAc 'select 1' | grep -qx 1 || fail "PostgreSQL does not answer"
pass "PostgreSQL answers"

echo "Smoke test passed."
