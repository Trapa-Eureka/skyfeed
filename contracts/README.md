# Message contracts

Every message that crosses a service boundary is described here. The JSON Schemas are the single definition; services and tools validate against these files and do not keep their own copy.

## Topics

| Transport | Topic | Key | Value | Written by | Read by |
|---|---|---|---|---|---|
| MQTT | `sensors/{sensorId}/telemetry` (QoS 1) | none | [telemetry.v1](telemetry.v1.schema.json) | sensors (the simulator) | `ingest` |
| Kafka | `telemetry.v1` | `sensorId` | [telemetry.v1](telemetry.v1.schema.json) | `ingest` | `api`, `gateway` |
| Kafka | `telemetry.dlq.v1` | the `sensorId` from the MQTT topic, or none if the topic had no usable one | the rejected payload exactly as received; headers `reason` (why it was rejected) and `mqtt-topic` (where it came from) | `ingest` | people, tools |
| Kafka | `adsb.state.v1` | `icao24` | [adsb.state.v1](adsb.state.v1.schema.json) | `ingest` | `api`, `gateway` |

The Kafka topics are created by the `kafka-init` container in [compose.yaml](../compose.yaml), with 3 partitions each. Automatic topic creation is off, so a misspelled topic name fails instead of creating a new topic.

Keying by `sensorId` and `icao24` keeps all messages of one sensor or one aircraft in one partition, so they are consumed in the order they were produced.

## Live feed (WebSocket)

Browsers connect to `gateway` at `ws://…/ws/v1`. All messages are JSON text with a `type` field.

| Direction | Type | Meaning | Schema |
|---|---|---|---|
| client → gateway | `subscribe` | what to receive: a map area for aircraft, and whether to receive sensor readings. A new one replaces the previous one. | [ws.client.v1](ws.client.v1.schema.json) |
| gateway → client | `hello` | sent once on connect; says how often a heartbeat follows | [ws.server.v1](ws.server.v1.schema.json) |
| gateway → client | `subscribed` | the subscription now in force | same |
| gateway → client | `aircraft` | a new state of an aircraft inside the subscribed area; `state` is an [adsb.state.v1](adsb.state.v1.schema.json) message | same |
| gateway → client | `telemetry` | a new reading of a sensor; `reading` is a [telemetry.v1](telemetry.v1.schema.json) message | same |
| gateway → client | `heartbeat` | sent at a fixed interval, data or not | same |
| gateway → client | `error` | the client's message could not be used; the subscription is unchanged | same |

Things a client must know:

- The feed carries changes only. For the state at the moment of connecting, ask the REST API (`/api/v1/aircraft`, `/api/v1/sensors`), then apply what the feed sends.
- Nothing announces that an aircraft left the area or stopped transmitting; its updates just stop. A client decides from the age of the last update.
- A client that reads slower than the feed is not sent a backlog. For each aircraft and each sensor it gets the newest message and misses the ones in between.
- No heartbeat for a few intervals means the connection is broken, even if it still looks open.

## ADS-B source

`ingest` reads aircraft states over HTTP in the shape of the OpenSky `GET /api/states/all` response and turns each state with a position into one `adsb.state.v1` message. Locally the same endpoint is served by the replay server in [tools/simulator](../tools/simulator), so the service uses one code path for recorded and live data.

## Rules for changing a contract

- Adding an optional field keeps the version.
- Removing or renaming a field, changing a type or a unit, or making a field required needs a new version: a new schema file (`…v2.schema.json`) and a new topic (`….v2`).
- [examples/](examples) holds one valid and one invalid message for each of the two data schemas. The simulator tests check both, and that everything the simulator produces is valid.
