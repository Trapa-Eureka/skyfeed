# Failure drills

What happens when a part of SkyFeed goes away and comes back. Each drill is a step of [`scripts/drill.sh`](../scripts/drill.sh) (`pnpm drill`), which runs against the whole local stack, takes the part away for 20 seconds with `docker compose stop`, starts it again, and then checks two things: data flows end to end again, and no service container was restarted to get there (R-OPS-03).

"Flows end to end" means all five sensors are `online` in the REST API with fresh readings (MQTT → `ingest` → Kafka → `api` → PostgreSQL), the API lists an aircraft heard from in the last 30 seconds, and a WebSocket client receives every kind of message from the gateway.

The numbers below are from two runs on 2026-11-17 on a developer machine (macOS, Docker Desktop with 3 GB of memory). They are what was observed, not guarantees; the two runs differed, most of all for Kafka, so both are given.

| Drill | Data flowing again after the part was back | Readings of one sensor stored for the outage | What that shows |
|---|---|---|---|
| Kafka stopped for 20 s | 10 s and 30 s | 15 of about 15 sent, and 14 of about 14 | Nothing lost. `ingest` keeps what it could not send and delivers it when the broker is back; `api` and `gateway` reconnect by themselves. |
| PostgreSQL stopped for 20 s | 8 s and 11 s | 14 of about 14 sent, and 13 of about 13 | Nothing lost, and the drill fails if anything is. The records wait in Kafka while `api` retries the same record with a growing pause, then it catches up. |
| MQTT broker stopped for 20 s | 10 s and 3 s | 2 of about 13 sent, both times | Readings taken while the broker was away are lost. The simulated sensors do not keep readings they cannot send, as a simple real sensor would not; `ingest` reconnects by itself. |
| All sensors silent (simulator stopped) | sensors online again within 60 s of restart | not measured | All five sensors were marked `offline` and five events were published to `sensor.status.v1`; after the restart five `online` events followed (R-OPS-01). |

## What the drills do not cover

- **A Kafka outage longer than two minutes.** `ingest` gives up on a record when Kafka has not accepted it for the producer's delivery timeout (two minutes by default). Readings older than that are counted as `failed` and lost. This was not drilled.
- **A crash, as opposed to a stop.** `docker compose stop` lets a process shut down in order. Killing a broker or the database mid-write was not tried.
- **Loss of the data volumes.** Every drill keeps the Kafka and PostgreSQL volumes.
- **More than one instance of a service.** The stack runs one of each. The code allows for more (a consumer group for `api`, a consumer group per instance for `gateway`, compare-and-set and row locks for the status monitor and the outbox), but that was never run.
- **The gateway.** Stopping it is covered by the console's end-to-end test instead (`apps/web/e2e/console.spec.ts`), which checks what the operator sees: the connection state, stale marks, and recovery without a reload.

## What an operator would look at

Each service reports the age of the last message it handled, so "is it flowing?" has a number:

| Service | Endpoint | Metric |
|---|---|---|
| `ingest` | `http://localhost:8081/q/metrics` | `skyfeed_source_last_message_age_seconds{source="mqtt"\|"adsb"}`, `skyfeed_telemetry_messages_total{result="forwarded"\|"rejected"\|"failed"}`, `skyfeed_adsb_polling` |
| `api` | `http://localhost:8080/actuator/prometheus` | `skyfeed_kafka_last_record_age_seconds{topic=…}`, `kafka_consumer_fetch_manager_records_lag`, `skyfeed_outbox_waiting` |
| `gateway` | `http://localhost:8082/q/metrics` | `skyfeed_kafka_last_record_age_seconds{topic=…}`, `kafka_consumer_fetch_manager_records_lag`, `skyfeed_gateway_clients` |

Nothing collects or alerts on these metrics yet; they are there to be read or scraped.
