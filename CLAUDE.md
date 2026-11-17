# skyfeed

Portfolio project: a sensor telemetry pipeline (MQTT → Kafka) and a live aircraft map (ADS-B → Kafka → WebSocket) in one system. Java services are the core; TypeScript/React and PostgreSQL support them. Plan and status: `README.md`. Requirements: `docs/requirements.md`.

- Java 21 services live in `services/` (Quarkus for ingest and the WebSocket gateway, Spring Boot + Hibernate for the API). The web console is `apps/web`.
- Write the requirement (an `R-…` id in `docs/requirements.md`) before the code, and name that id in the test that covers it.
- Tests run against the local stack and recorded replay data, never the live OpenSky API. `pnpm verify` runs everything, including the stack smoke test and the browser tests.
- Everything in the repository is written in English.

This file stays short on purpose. Rules that must hold are hooks in `.claude/hooks/` (dangerous commands and secrets are blocked; the README check runs when a turn ends). Step-by-step procedures are skills in `.claude/skills/`. If a hook blocks an action, do not work around it; tell the user.
