#!/usr/bin/env node
// Connects to the gateway's live feed like a browser would and checks that every kind of message arrives.
// Usage: node scripts/ws-check.mjs [ws://127.0.0.1:8082/ws/v1]     Exit code 0 when all were seen within 40 s.
const url = process.argv[2] ?? "ws://127.0.0.1:8082/ws/v1";
const area = { lamin: 10, lomin: 119.5, lamax: 15, lomax: 124.5 };
const wanted = ["hello", "subscribed", "heartbeat", "aircraft", "telemetry"];
const seen = new Map();
let outside = 0;

const socket = new WebSocket(url);
const finish = (code, text) => {
  console.log(text);
  socket.close();
  process.exit(code);
};
const timer = setTimeout(() => {
  const missing = wanted.filter((type) => !seen.has(type));
  finish(1, `timed out; never received: ${missing.join(", ")}`);
}, 40_000);

socket.addEventListener("open", () => socket.send(JSON.stringify({ type: "subscribe", area, sensors: true })));
socket.addEventListener("error", () => finish(1, `could not connect to ${url}`));
socket.addEventListener("message", (event) => {
  const message = JSON.parse(event.data);
  seen.set(message.type, (seen.get(message.type) ?? 0) + 1);
  if (message.type === "aircraft") {
    const { lat, lon } = message.state;
    if (lat < area.lamin || lat > area.lamax || lon < area.lomin || lon > area.lomax) outside++;
  }
  if (message.type === "error") finish(1, `the gateway answered with an error: ${message.message}`);
  if (wanted.every((type) => seen.has(type))) {
    clearTimeout(timer);
    if (outside > 0) finish(1, `${outside} aircraft outside the subscribed area were received`);
    finish(0, [...seen].map(([type, count]) => `${type} ${count}`).join(", "));
  }
});
