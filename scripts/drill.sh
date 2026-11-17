#!/usr/bin/env bash
# Failure drills: takes one part of the running stack away at a time, brings it back, and checks that data flows
# end to end again without any service being restarted by hand. Each drill names the requirement it is evidence for.
# Usage: bash scripts/drill.sh            The stack must be running (pnpm stack:up). It is left running.
# What each drill showed when it was last run is written up in docs/drills.md.
set -euo pipefail
cd "$(dirname "$0")/.."

if [ -f .env ]; then set -a; . ./.env; set +a; fi
API="http://127.0.0.1:${API_PORT:-8080}"
WS="ws://127.0.0.1:${GATEWAY_PORT:-8082}/ws/v1"
MCP="http://127.0.0.1:${MCP_PORT:-8083}"
OUTAGE_SECONDS="${DRILL_OUTAGE_SECONDS:-20}"
RECOVERY_LIMIT_SECONDS="${DRILL_RECOVERY_LIMIT_SECONDS:-120}"
SERVICES="ingest api gateway web mcp"

pass() { echo "ok    $1"; }
fail() { echo "FAIL  $1" >&2; exit 1; }
now_iso() { date -u +%Y-%m-%dT%H:%M:%SZ; }
# Reads JSON on stdin and prints the value of a JavaScript expression over it (the parsed JSON is `d`).
jq_node() { node -e 'let s="";process.stdin.on("data",c=>s+=c).on("end",()=>{try{const d=JSON.parse(s);console.log(eval(process.argv[1]))}catch{console.log("")}})' "$1"; }

started_at() { # the start time of every service container, to prove later that none was restarted
  for service in $SERVICES; do
    printf '%s=%s ' "$service" "$(docker inspect -f '{{.State.StartedAt}}' "$(docker compose ps -q "$service")")"
  done
}

wait_healthy() { # service
  for _ in $(seq 1 60); do
    [ "$(docker inspect -f '{{.State.Health.Status}}' "$(docker compose ps -q "$1")" 2> /dev/null)" = "healthy" ] && return 0
    sleep 1
  done
  fail "$1 did not become healthy within 60 s"
}

# Data flows end to end when fresh readings of all five sensors are in the database (MQTT -> ingest -> Kafka ->
# api -> PostgreSQL), the API lists a recently heard aircraft, the MCP server can list the topics, and the live
# feed delivers every kind of message.
flow_ok() {
  local online fresh topics
  online="$(curl -fsS --max-time 5 "$API/api/v1/sensors" 2> /dev/null | jq_node 'd.filter(s => s.sensorId.startsWith("gs-") && s.status === "online").length')"
  [ "${online:-0}" -ge 5 ] || return 1
  fresh="$(curl -fsS --max-time 5 "$API/api/v1/aircraft" 2> /dev/null | jq_node 'd.filter(a => a.ageSeconds < 30).length')"
  [ "${fresh:-0}" -ge 1 ] || return 1
  # The MCP server keeps one long-lived connection to Kafka; it too must answer again by itself.
  topics="$(curl -fsS --max-time 15 -X POST "$MCP/mcp" -H 'Content-Type: application/json' -H 'Accept: application/json, text/event-stream' \
    -d '{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"list_topics","arguments":{}}}' 2> /dev/null \
    | jq_node 'd.result.isError ? 0 : JSON.parse(d.result.content[0].text).topics.length')"
  [ "${topics:-0}" -ge 4 ] || return 1
  node scripts/ws-check.mjs "$WS" > /dev/null 2>&1
}

wait_flow() { # prints the seconds it took
  local start=$SECONDS
  while [ $((SECONDS - start)) -lt "$RECOVERY_LIMIT_SECONDS" ]; do
    if flow_ok; then echo $((SECONDS - start)); return 0; fi
    sleep 1
  done
  return 1
}

# How many readings of one sensor the database holds for a period, against how many the sensor sent (one every 2 s).
stored_between() { # from to
  curl -fsS "$API/api/v1/sensors/gs-iloilo/telemetry?from=$1&to=$2&limit=5000" | jq_node 'd.length'
}

outage_drill() { # service, what the drill is called
  local service="$1" name="$2" before from to took stored expected
  before="$(started_at)"
  from="$(now_iso)"
  docker compose stop "$service" > /dev/null 2>&1
  sleep "$OUTAGE_SECONDS"
  docker compose start "$service" > /dev/null 2>&1
  wait_healthy "$service"
  to="$(now_iso)"
  took="$(wait_flow)" || fail "$name: data did not flow again within $RECOVERY_LIMIT_SECONDS s of $service coming back"
  [ "$(started_at)" = "$before" ] || fail "$name: a service was restarted (before: $before; after: $(started_at))"
  sleep 3 # let what was held back during the outage reach the database before counting
  stored="$(stored_between "$from" "$to")"
  expected=$(( ($(date -u -j -f %Y-%m-%dT%H:%M:%SZ "$to" +%s 2> /dev/null || date -u -d "$to" +%s) - $(date -u -j -f %Y-%m-%dT%H:%M:%SZ "$from" +%s 2> /dev/null || date -u -d "$from" +%s)) / 2 ))
  pass "$name: flowing again ${took} s after $service was back; no service restarted; readings of gs-iloilo stored for the outage: $stored of about $expected sent"
  LAST_STORED="$stored"
  LAST_EXPECTED="$expected"
}

echo "Checking that the stack is up and data flows..."
docker compose ps -q kafka > /dev/null 2>&1 || fail "the stack is not running; start it with: pnpm stack:up"
wait_flow > /dev/null || fail "data does not flow before any drill; fix that first"
pass "baseline: data flows end to end"

# R-OPS-03
echo "Drill 1: Kafka goes away for $OUTAGE_SECONDS s"
outage_drill kafka "Kafka restart"

# R-OPS-03
echo "Drill 2: PostgreSQL goes away for $OUTAGE_SECONDS s"
outage_drill postgres "PostgreSQL restart"
# Nothing may be lost here: Kafka keeps the records and the api service retries until the database is back.
[ "$LAST_STORED" -ge $((LAST_EXPECTED - 2)) ] || fail "PostgreSQL restart: readings were lost ($LAST_STORED stored of about $LAST_EXPECTED)"
pass "PostgreSQL restart: no reading was lost"

# R-OPS-03
echo "Drill 3: the MQTT broker goes away for $OUTAGE_SECONDS s"
outage_drill mosquitto "MQTT broker restart"

# R-OPS-01
echo "Drill 4: all sensors go silent"
since="$(now_iso)"
docker compose stop simulator > /dev/null 2>&1
offline=0
for _ in $(seq 1 60); do
  offline="$(curl -fsS "$API/api/v1/sensor-events?limit=200" | SINCE="$since" jq_node 'new Set(d.filter(e => e.status === "offline" && e.changedAt >= process.env.SINCE).map(e => e.sensorId)).size')"
  [ "${offline:-0}" -ge 5 ] && break
  sleep 1
done
docker compose start simulator > /dev/null 2>&1
[ "${offline:-0}" -ge 5 ] || fail "sensors silent: only $offline of 5 sensors were reported offline within 60 s"
published="$(docker compose exec -T kafka /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server localhost:9092 \
  --topic sensor.status.v1 --from-beginning --timeout-ms 5000 2> /dev/null | SINCE="$since" node -e '
    let s = ""; process.stdin.on("data", (c) => (s += c)).on("end", () => {
      const events = s.split("\n").filter(Boolean).map((line) => JSON.parse(line));
      console.log(new Set(events.filter((e) => e.status === "offline" && e.changedAt >= process.env.SINCE).map((e) => e.sensorId)).size);
    });')"
[ "$published" -ge 5 ] || fail "sensors silent: only $published of 5 offline events were published to Kafka topic sensor.status.v1"
pass "sensors silent: all 5 were marked offline and the events were published to sensor.status.v1"
wait_healthy simulator
online=0
for _ in $(seq 1 60); do
  online="$(curl -fsS "$API/api/v1/sensor-events?limit=200" | SINCE="$since" jq_node 'new Set(d.filter(e => e.status === "online" && e.changedAt >= process.env.SINCE).map(e => e.sensorId)).size')"
  [ "${online:-0}" -ge 5 ] && break
  sleep 1
done
[ "${online:-0}" -ge 5 ] || fail "sensors back: only $online of 5 sensors were reported online again within 60 s"
wait_flow > /dev/null || fail "sensors back: data did not flow again"
pass "sensors back: all 5 were reported online again"

echo "All drills passed."
