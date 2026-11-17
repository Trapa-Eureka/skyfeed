# Simulator

The two data sources SkyFeed runs on locally, so that nothing depends on hardware or on the live OpenSky API:

- **Sensors over MQTT.** Five simulated ground stations publish a [telemetry.v1](../../contracts/telemetry.v1.schema.json) reading to `sensors/{sensorId}/telemetry` at a fixed interval.
- **OpenSky replay server.** `GET /api/states/all` answers in the OpenSky format from a recorded file ([fixtures/opensky-states.jsonl](fixtures/opensky-states.jsonl)). It accepts the same `lamin`, `lomin`, `lamax`, `lomax` query as the real API. `GET /health` reports the number of frames.

Both are repeatable. A reading depends only on the seed, the sensor, its sequence number and the time. The replay response depends only on the file, the start time, the current time and the speed; recorded timestamps are moved so the recording appears to begin when the server started, and the file loops.

## Run

It starts with the rest of the stack (`docker compose up -d --build --wait` in the repository root). To run it alone against a broker that is already up:

```bash
pnpm install
pnpm --filter @skyfeed/simulator start
```

| Variable | Default | Meaning |
|---|---|---|
| `MQTT_URL` | `mqtt://localhost:11883` | broker to publish to |
| `SIM_INTERVAL_MS` | `2000` | time between readings of each sensor |
| `SIM_SEED` | `skyfeed` | seed for the noise in the readings |
| `REPLAY_PORT` | `18090` | port of the replay server |
| `REPLAY_SPEED` | `1` | `2` plays the recording twice as fast |
| `REPLAY_FILE` | the bundled fixture | another recording to play |

## Test

```bash
pnpm --filter @skyfeed/simulator test
pnpm --filter @skyfeed/simulator typecheck
```

The tests also check the contracts: everything the simulator produces, and every aircraft in the recording, must be valid against the schemas in [contracts/](../../contracts).

## Record a new replay file

This calls the live OpenSky API and uses its credits (one per request for this map area), so do it rarely:

```bash
pnpm --filter @skyfeed/simulator record fixtures/opensky-states.jsonl 30 10   # 30 frames, 10 s apart
```
