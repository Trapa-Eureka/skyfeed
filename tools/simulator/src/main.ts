// Starts both local data sources: sensors publishing over MQTT, and the OpenSky replay server.
// Configuration is by environment variables; see the table in tools/simulator/README.md.
import { fileURLToPath } from "node:url";
import mqtt from "mqtt";
import { createOpenSkyMock } from "./opensky-mock.ts";
import { loadFrames } from "./replay.ts";
import { reading, SENSORS, topicFor } from "./sensors.ts";

const env = process.env;
const mqttUrl = env.MQTT_URL ?? "mqtt://localhost:11883";
const intervalMs = Number(env.SIM_INTERVAL_MS ?? 2000);
const seed = env.SIM_SEED ?? "skyfeed";
const httpPort = Number(env.REPLAY_PORT ?? 18090);
const speed = Number(env.REPLAY_SPEED ?? 1);
const replayFile = env.REPLAY_FILE ?? fileURLToPath(new URL("../fixtures/opensky-states.jsonl", import.meta.url));

// --- OpenSky replay server ---
const frames = loadFrames(replayFile);
const server = createOpenSkyMock({ frames, startMs: Date.now(), speed });
server.listen(httpPort, () => console.log(`replay: ${frames.length} frames on http://0.0.0.0:${httpPort}/api/states/all (speed ${speed})`));

// --- sensors over MQTT ---
const client = mqtt.connect(mqttUrl, { clientId: `skyfeed-simulator-${process.pid}`, reconnectPeriod: 2000 });
client.on("connect", () => console.log(`mqtt: connected to ${mqttUrl}, ${SENSORS.length} sensors every ${intervalMs} ms`));
client.on("reconnect", () => console.log("mqtt: reconnecting"));
client.on("error", (error) => console.error(`mqtt: ${error.message}`));

let seq = 0;
const timer = setInterval(() => {
  // Readings made while the broker is away are dropped, like a station that lost its link.
  // seq still grows, so the gap is visible downstream.
  const now = Date.now();
  if (client.connected) {
    for (const sensor of SENSORS) {
      client.publish(topicFor(sensor.id), JSON.stringify(reading(sensor, seq, now, seed)), { qos: 1 });
    }
  }
  seq++;
}, intervalMs);

function shutdown() {
  clearInterval(timer);
  server.close();
  client.end(false, {}, () => process.exit(0));
  setTimeout(() => process.exit(0), 2000).unref();
}
process.on("SIGTERM", shutdown);
process.on("SIGINT", shutdown);
