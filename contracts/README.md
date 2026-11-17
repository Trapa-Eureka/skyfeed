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

## ADS-B source

`ingest` reads aircraft states over HTTP in the shape of the OpenSky `GET /api/states/all` response and turns each state with a position into one `adsb.state.v1` message. Locally the same endpoint is served by the replay server in [tools/simulator](../tools/simulator), so the service uses one code path for recorded and live data.

## Rules for changing a contract

- Adding an optional field keeps the version.
- Removing or renaming a field, changing a type or a unit, or making a field required needs a new version: a new schema file (`…v2.schema.json`) and a new topic (`….v2`).
- [examples/](examples) holds one valid and one invalid message per schema. The simulator tests check both, and that everything the simulator produces is valid.
