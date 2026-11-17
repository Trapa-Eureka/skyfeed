# MCP server: SkyFeed topics

A [Model Context Protocol](https://modelcontextprotocol.io) server that lets an AI agent look at the Kafka topics of a running SkyFeed stack. It can only read (R-AI-01): it has no producer, commits no offsets, and makes no admin call that creates, alters or deletes.

| Tool | Returns |
|---|---|
| `list_topics` | the topics with their number of partitions and messages |
| `recent_messages(topic, limit)` | the newest messages of a topic, newest first: partition, offset, timestamp, key, value, headers. `limit` 1 to 50, default 10; values are cut at 2000 characters |
| `consumer_lag` | per consumer group, how many messages it has not read yet of each topic it has a position on; groups that never commit a position are listed by name, and a topic a group has never committed on does not appear |

Every tool declares `readOnlyHint`, so a client may let an agent call it without asking each time. Every answer carries `asOf`, the time Kafka was asked, so a figure kept for a while is not taken for a current one.

Two things the tools do so that an answer cannot mislead: `recent_messages` fails if it cannot read the topic completely in 10 seconds, and never returns part of it; `consumer_lag` counts a partition the group has never committed on as backlog and reports how many such partitions there are.

## Run

It starts with the stack (`pnpm stack:up`) and answers on `http://localhost:8083/mcp` (streamable HTTP, no sessions). From source, against a running stack: `./mvnw spring-boot:run` in this folder.

The repository's [`.mcp.json`](../../.mcp.json) registers it for Claude Code under the name `skyfeed-topics`; Claude Code asks once whether to trust it. Then, with the stack up, try: "Which messages are in the dead-letter topic, and why were they rejected?"

By hand:

```bash
curl -s -X POST http://localhost:8083/mcp \
  -H 'Content-Type: application/json' -H 'Accept: application/json, text/event-stream' \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"recent_messages","arguments":{"topic":"telemetry.dlq.v1","limit":3}}}'
```

## Things to know

- There is no authentication, and the server shows message contents to whoever can reach it. So it listens on `127.0.0.1` only, both from source and in the stack, and it refuses requests addressed to any other host name or sent by a web page of another site (a browser can reach `127.0.0.1` too).
- [`.mcp.json`](../../.mcp.json) has the port written in it. If you change `MCP_PORT`, change it there too.
- Message contents come from outside (sensors, an aircraft feed, anyone who can publish to the MQTT broker). A client must treat them as data, not as instructions; the server says so in its description, but it cannot enforce it.
- Only Kafka is covered. MQTT topics are not.

## Test

```bash
./mvnw verify      # needs Docker: the tests call the server over HTTP against a real Kafka in a container
```
