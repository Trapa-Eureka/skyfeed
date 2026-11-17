// Records live OpenSky responses into a replay file (one JSON response per line).
// Each request costs API credits, so this is run by hand, rarely. Usage:
//   pnpm --filter @skyfeed/simulator record -- <out.jsonl> [frames=30] [intervalSeconds=10]
import { appendFileSync, writeFileSync } from "node:fs";
import { setTimeout as sleep } from "node:timers/promises";

// 5 x 5 degrees over the central Philippines (Manila, Iloilo, Cebu). 25 square degrees or less costs one credit.
export const BBOX = { lamin: 10, lomin: 119.5, lamax: 15, lomax: 124.5 };

const [out, framesArg, intervalArg] = process.argv.slice(2).filter((a) => a !== "--");
if (!out) {
  console.error("usage: record <out.jsonl> [frames=30] [intervalSeconds=10]");
  process.exit(1);
}
const frames = Number(framesArg ?? 30);
const intervalMs = Number(intervalArg ?? 10) * 1000;
const base = process.env.OPENSKY_BASE_URL ?? "https://opensky-network.org";
const url = `${base}/api/states/all?${new URLSearchParams(Object.entries(BBOX).map(([k, v]) => [k, String(v)]))}`;

writeFileSync(out, "");
let lastTime = 0;
for (let i = 0; i < frames; i++) {
  const started = Date.now();
  const res = await fetch(url, { signal: AbortSignal.timeout(20_000) });
  if (!res.ok) {
    console.error(`frame ${i}: HTTP ${res.status}; stopping`);
    process.exit(1);
  }
  const body = (await res.json()) as { time: number; states: unknown[] | null };
  if (body.time > lastTime) {
    appendFileSync(out, JSON.stringify({ time: body.time, states: body.states ?? [] }) + "\n");
    lastTime = body.time;
  }
  console.log(`frame ${i + 1}/${frames}: time=${body.time} aircraft=${body.states?.length ?? 0} credits-left=${res.headers.get("x-rate-limit-remaining")}`);
  if (i < frames - 1) await sleep(Math.max(0, intervalMs - (Date.now() - started)));
}
