# Requirements

Each requirement has an id. A requirement is written here before the code, and the test that covers it names the id (in its display name or a tag). The **Covered by** column is filled in when that test exists and passes; an empty cell means not covered yet.

`pnpm trace` checks the link mechanically: it fails when a requirement is named by no test, or a test names an id that is not in this file. It cannot tell whether a test that names a requirement really proves it; the **Covered by** column is the human answer to that.

Phases refer to the [Roadmap](../README.md#roadmap).

## Ingest

| Id | Requirement | Phase | Covered by |
|---|---|---|---|
| R-ING-01 | Sensor telemetry published on MQTT topic `sensors/{sensorId}/telemetry` is forwarded to Kafka topic `telemetry.v1`, keyed by sensor id. | 2 | `services/ingest`: `TelemetryCheckTest`, `IngestPipelineTest` (real Mosquitto and Kafka); `scripts/smoke.sh` |
| R-ING-02 | A telemetry message that does not match the schema, or whose `sensorId` differs from the one in its MQTT topic, is not forwarded; it goes to a dead-letter topic with the reason. | 2 | `services/ingest`: `TelemetryCheckTest`, `IngestPipelineTest`; `scripts/smoke.sh` |
| R-ING-03 | Aircraft state vectors are read from an ADS-B source and published to Kafka topic `adsb.state.v1`, keyed by ICAO 24-bit address. | 2 | `services/ingest`: `StateVectorMapperTest`, `PollCycleTest`, `IngestPipelineTest`; `scripts/smoke.sh` |
| R-ING-04 | The ADS-B source is switchable by configuration: `replay` (a recorded file, the default) or `opensky` (the live API). | 2 | `services/ingest`: `PollCycleTest`, `IngestPipelineTest` (replay). The `opensky` setting was run once by hand against the live API; no automated test calls it. |
| R-ING-05 | In `opensky` mode the poller never spends more than the configured daily credit budget while it runs; when the budget is used up, or the API reports its own limit, it stops polling until the next UTC day and reports it in its health status. (The count is kept in memory, so it starts again from zero when the service restarts.) | 2 | `services/ingest`: `CreditBudgetTest`, `PollCycleTest`. Also seen once against the live API: with a budget of 2 it made 2 requests and reported `BUDGET_EXHAUSTED`. |
| R-ING-06 | Replay is deterministic: the same file, start time and speed produce the same sequence of responses. | 1 | `tools/simulator/test/replay.test.ts` |

## Storage and REST API

| Id | Requirement | Phase | Covered by |
|---|---|---|---|
| R-API-01 | Telemetry and aircraft states consumed from Kafka are stored in PostgreSQL. | 3 | `services/api`: `StoreTest`, `KafkaToDatabaseTest` (real PostgreSQL and Kafka); `scripts/smoke.sh` |
| R-API-02 | Processing the same Kafka record twice stores it once. The same holds for two records with the same content: a reading is identified by sensor, time and sequence number, an aircraft state by address and last-contact time. | 3 | `services/api`: `StoreTest`, `KafkaToDatabaseTest` |
| R-API-03 | `GET` endpoints return: sensors with their latest reading and status; a telemetry series for one sensor and time range; current aircraft; the recent track of one aircraft. The status of a sensor follows from the age of its latest reading: online, then degraded after 10 seconds, then offline after 30 seconds (both configurable). | 3 | `services/api`: `RestApiTest`; `scripts/smoke.sh` |
| R-API-04 | The API is described by an OpenAPI document served by the service. | 3 | `services/api`: `PlatformTest`; `scripts/smoke.sh` |
| R-API-05 | Schema changes are applied only through versioned migrations. | 3 | `services/api`: `PlatformTest`. Hibernate runs with `ddl-auto=validate`, so the service does not start if an entity and the migrated tables disagree. |

## Real-time gateway

| Id | Requirement | Phase | Covered by |
|---|---|---|---|
| R-RT-01 | A browser connected by WebSocket receives aircraft updates for the map area it subscribed to, and no others. | 4 | `services/gateway`: `LiveEndpointTest` (real WebSocket connections and Kafka), `MessagesTest`; `scripts/smoke.sh` |
| R-RT-02 | A connected browser receives sensor readings and sensor status changes. | 4 | `services/gateway`: `LiveEndpointTest` (readings and status changes); `scripts/smoke.sh`; `apps/web`: `e2e/console.spec.ts` (status changes reach the console while the feed is up) |
| R-RT-03 | A slow client does not delay other clients; for a slow client older position updates for the same aircraft are dropped in favour of the newest. The same applies to the readings of one sensor. | 4 | `services/gateway`: `ClientSessionTest`, with a simulated client that takes nothing. Not tried with a real congested network connection. |
| R-RT-04 | The gateway sends a heartbeat so a client can tell a quiet feed from a broken connection. | 4 | `services/gateway`: `LiveEndpointTest`; `scripts/smoke.sh` |

## Operator console

| Id | Requirement | Phase | Covered by |
|---|---|---|---|
| R-UI-01 | The map shows each aircraft at its last reported position with heading, callsign and altitude. | 5 | `apps/web`: `e2e/console.spec.ts` (a real browser against the stack, compared with the REST API), `test/age.test.ts`, `test/store.test.ts`. That a marker sits at the right place on the map is left to MapLibre and is not asserted. |
| R-UI-02 | Every value on screen that comes from a feed shows its age, and is visibly marked stale after a configured time (aircraft: 30 seconds since last contact; sensors: the degraded and offline thresholds). Stale data is never shown as current, and a chart shows a gap where readings are missing. | 5 | `apps/web`: `e2e/console.spec.ts` (ages shown; with the gateway stopped every aircraft is marked stale and every sensor offline), `test/age.test.ts`, `test/messages.test.ts`, `test/chart.test.ts` |
| R-UI-03 | The console shows the connection state (connected, reconnecting, disconnected) at all times and reconnects by itself. Reconnecting becomes disconnected after 15 seconds without the feed; a connection that is open but silent for three heartbeat intervals counts as lost. | 5 | `apps/web`: `e2e/console.spec.ts` (the gateway is stopped and started again under the open page), `test/connection.test.ts` |
| R-UI-04 | A sensor panel lists each sensor with status (online, degraded, offline) and its latest readings; selecting one shows its recent series. | 5 | `apps/web`: `e2e/console.spec.ts`, `test/age.test.ts`, `test/store.test.ts` |

## Reliability and operations

| Id | Requirement | Phase | Covered by |
|---|---|---|---|
| R-OPS-01 | A sensor that sends nothing for a configured time is marked degraded (10 seconds) and then offline (30 seconds), and online again with its next reading. Each change is published once as an event on Kafka topic `sensor.status.v1`; the change and its event are stored in one transaction, so there is no change without an event and no event without a change. | 6 | `services/api`: `SensorStatusTest` (real PostgreSQL and Kafka); `scripts/drill.sh`; `apps/web`: `e2e/console.spec.ts` |
| R-OPS-02 | An aircraft with no update for a configured time (120 seconds) is removed from the live picture. | 6 | `apps/web`: `test/store.test.ts`. This is the console's picture; the REST API has its own window for current aircraft (60 seconds, R-API-03). |
| R-OPS-03 | After the message broker, the MQTT broker or the database restarts, every service recovers without a manual restart. A database restart loses no data. | 6 | `scripts/drill.sh`: Kafka, PostgreSQL and the MQTT broker are each stopped for 20 seconds and started again; the drill fails unless data flows end to end afterwards with no service container restarted. Results and what is not covered: [drills.md](drills.md). |
| R-OPS-04 | Every service exposes health and metrics endpoints, including message lag and the age of the last message per source. | 6 | `services/ingest`: `IngestPipelineTest`; `services/api`: `PlatformTest`; `services/gateway`: `LiveEndpointTest`; `scripts/smoke.sh`. Lag is reported by the Kafka consumers (`api`, `gateway`); `ingest` only produces, so it reports the age of the last message per source instead. |
| R-OPS-05 | The whole stack starts with one command on a machine with only Docker installed. | 1 | `scripts/smoke.sh`: brokers, database, simulator, the three services and the console all start with `docker compose up -d --build --wait` and answer. |

## AI tooling

| Id | Requirement | Phase | Covered by |
|---|---|---|---|
| R-AI-01 | An MCP server exposes read-only tools over the message topics (list topics, recent messages, consumer lag). It has no tool that writes. | 7 | `tools/mcp`: `TopicToolsTest` (over HTTP against a real Kafka: the tool list, each tool's answer, and that calling them leaves records, committed positions, topics and groups unchanged; plus a scan of the source for any writing call); `scripts/smoke.sh` |
| R-AI-02 | A check reports every requirement in this file that no test names. | 7 | `scripts/trace.test.mjs`; the check itself is `scripts/trace.mjs` (`pnpm trace`), run by `scripts/verify.sh`. It also reports a test that names a requirement which does not exist. |

## Optional content (CMS)

| Id | Requirement | Phase | Covered by |
|---|---|---|---|
| R-CMS-01 | With the optional headless CMS running, the console shows the operator notices, and the site details of the selected sensor, as entered in the CMS. A visitor who is not signed in to the CMS can read that content and cannot change it. | 9 | `apps/web`: `test/cms.test.ts`, `e2e/cms.spec.ts`; `scripts/cms.sh check` (`pnpm cms:check`), which starts nothing itself and is not part of `pnpm verify`: it was run by hand with the CMS up. |
| R-CMS-02 | Without the CMS, or when it does not answer, the console works unchanged: no notices, no site details, and no error about them. | 9 | `apps/web`: `test/cms.test.ts`, `e2e/console.spec.ts` (the default stack has no CMS) |
