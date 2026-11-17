#!/usr/bin/env node
// Records the demo video: drives the real console in a browser against the running stack, stops and starts
// real containers, and writes docs/demo/skyfeed-demo.mp4 plus a short preview GIF.
// Usage: pnpm demo            The stack must be running (pnpm stack:up). Needs ffmpeg on the PATH.
// Nothing in the picture is staged except two overlays added by this script: the caption bar and a dot that
// stands in for the mouse pointer (a recorded page has no visible pointer). Waiting is shown speeded up, and
// the caption says so.
import { execFileSync } from "node:child_process";
import { mkdirSync, mkdtempSync, readdirSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { chromium } from "@playwright/test";

const here = dirname(fileURLToPath(import.meta.url));
const repo = join(here, "../../..");
const outDir = join(repo, "docs/demo");
const url = process.env.WEB_URL ?? "http://localhost:3000";
const SIZE = { width: 1280, height: 800 };
const FAST = 5; // how much faster the waiting parts are played

const compose = (...args) => execFileSync("docker", ["compose", ...args], { cwd: repo, stdio: "pipe" });
const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

/** Parts of the recording and how fast each is played: [{ start, end, speed }] in seconds of recorded time. */
const segments = [];
let recordingStart = 0;
let segmentStart = 0;
const elapsed = () => (Date.now() - recordingStart) / 1000;
function endSegment(speed) {
  const now = elapsed();
  if (now - segmentStart > 0.05) segments.push({ start: segmentStart, end: now, speed });
  segmentStart = now;
}

const browser = await chromium.launch();
const videoDir = mkdtempSync(join(tmpdir(), "skyfeed-demo-"));
const context = await browser.newContext({ viewport: SIZE, recordVideo: { dir: videoDir, size: SIZE } });
const page = await context.newPage();
recordingStart = Date.now();

async function overlays() {
  await page.addStyleTag({
    content: `
      #demo-caption { position: fixed; left: 50%; bottom: 28px; transform: translateX(-50%); z-index: 99999; max-width: 86%;
        padding: 12px 20px; border-radius: 8px; background: rgb(12 12 11 / 92%); border: 1px solid #5a5a55; color: #f0efec;
        font: 600 19px/1.35 ui-sans-serif, system-ui, sans-serif; text-align: center; box-shadow: 0 6px 24px rgb(0 0 0 / 50%); }
      #demo-caption small { display: block; margin-top: 3px; font-weight: 400; font-size: 14px; color: #c3c2b7; }
      #demo-pointer { position: fixed; z-index: 99998; width: 18px; height: 18px; margin: -9px 0 0 -9px; border-radius: 50%;
        border: 2px solid #fff; background: rgb(57 135 229 / 75%); pointer-events: none; transition: left 0.5s ease, top 0.5s ease; }`,
  });
  await page.evaluate(() => {
    const caption = document.createElement("div");
    caption.id = "demo-caption";
    caption.hidden = true;
    const pointer = document.createElement("div");
    pointer.id = "demo-pointer";
    pointer.style.left = "640px";
    pointer.style.top = "400px";
    document.body.append(caption, pointer);
  });
}

async function caption(text, note = "") {
  await page.evaluate(([main, small]) => {
    const element = document.getElementById("demo-caption");
    element.hidden = !main;
    element.textContent = main;
    if (small) element.appendChild(Object.assign(document.createElement("small"), { textContent: small }));
  }, [text, note]);
}

/** Moves the stand-in pointer to an element and waits for it to arrive. With `below`, just under it, so its text stays readable. */
async function pointAt(locator, { below = false } = {}) {
  const box = await locator.boundingBox();
  if (!box) return;
  await page.evaluate(([x, y]) => {
    const pointer = document.getElementById("demo-pointer");
    pointer.style.left = `${x}px`;
    pointer.style.top = `${y}px`;
  }, [box.x + box.width / 2, below ? box.y + box.height + 12 : box.y + box.height / 2]);
  await sleep(650);
}

/** Waits for something on the page while the recording runs; that stretch is played speeded up. */
async function waitFast(text, condition, timeout = 90_000) {
  endSegment(1);
  await caption(text, `waiting, shown ${FAST}x faster`);
  await page.waitForFunction(condition, null, { timeout });
  await sleep(1500);
  endSegment(FAST);
}

let gatewayStopped = false;
let simulatorStopped = false;
try {
  // --- 1. the picture ---
  await page.goto(url);
  await page.waitForSelector('[data-testid="connection"][data-state="connected"]', { timeout: 60_000 });
  await page.waitForSelector('[data-testid="map"][data-ready="true"]', { timeout: 60_000 });
  await page.waitForFunction(() => document.querySelectorAll('.ac[data-stale="false"]').length > 10, null, { timeout: 90_000 });
  await page.waitForFunction(() => document.querySelectorAll('[data-testid="sensor-row"][data-status="online"]').length === 5, null, { timeout: 60_000 });
  await overlays();
  segmentStart = elapsed(); // everything before this (loading) is left out of the video
  await caption("SkyFeed: live aircraft and ground sensors on one console", "Sensors over MQTT, aircraft over ADS-B, both through Kafka, pushed to the browser over WebSockets");
  await sleep(6000);

  // --- 2. an aircraft ---
  const icao24 = await page.evaluate(() => {
    const fresh = [...document.querySelectorAll('.ac[data-stale="false"]')].map((element) => ({ id: element.dataset.icao24, y: element.getBoundingClientRect().top }));
    return fresh.sort((a, b) => b.y - a.y)[0].id; // the southernmost: away from the crowd over Manila
  });
  const aircraft = page.locator(`.ac[data-icao24="${icao24}"]`);
  await caption("Every aircraft: callsign, altitude, heading, and the age of that information");
  await pointAt(aircraft);
  await sleep(1500);
  await aircraft.focus();
  await page.keyboard.press("Enter");
  await caption("Select one for its details and track");
  await sleep(5000);

  // --- 3. a sensor ---
  const sensor = page.getByRole("button", { name: "gs-iloilo", exact: true }).first();
  await pointAt(sensor);
  await sensor.click();
  await caption("Sensors: status, latest readings and their age. Select one for its last 15 minutes");
  await sleep(2500);
  await pointAt(page.getByTestId("chart").first());
  await page.getByTestId("chart").first().locator("svg").hover({ position: { x: 230, y: 50 } });
  await sleep(3500);
  await page.mouse.move(600, 500);
  await page.getByTestId("aircraft-card").getByRole("button", { name: "Close aircraft details" }).click();

  // --- 4. the live feed goes down ---
  await pointAt(page.getByTestId("connection"), { below: true });
  await caption("Now the WebSocket gateway is stopped: docker compose stop gateway");
  await sleep(2500);
  compose("stop", "gateway");
  gatewayStopped = true;
  await waitFast("The console notices by itself and says so", () => document.querySelector('[data-testid="connection"]').dataset.state === "disconnected");
  await waitFast("Nothing is updated any more, so nothing may look current", () =>
    document.querySelectorAll('.ac[data-stale="false"]').length === 0 && document.querySelectorAll('[data-testid="sensor-row"][data-status="offline"]').length === 5);
  await caption("Every aircraft is marked STALE, every sensor offline, values struck through");
  await sleep(5000);

  // --- 5. and comes back ---
  await caption("The gateway is started again: docker compose start gateway");
  await sleep(2000);
  compose("start", "gateway");
  gatewayStopped = false;
  await waitFast("No reload: the console reconnects and fetches the current picture", () =>
    document.querySelector('[data-testid="connection"]').dataset.state === "connected" &&
    document.querySelectorAll('[data-testid="sensor-row"][data-status="online"]').length === 5 &&
    document.querySelectorAll('.ac[data-stale="false"]').length > 5);
  await caption("Connected again, and current");
  await sleep(3500);

  // --- 6. the sensors go silent ---
  await pointAt(page.getByTestId("sensor-table"));
  await caption("Now the sensors go silent: docker compose stop simulator");
  await sleep(2500);
  compose("stop", "simulator");
  simulatorStopped = true;
  await waitFast("The backend notices: degraded after 10 s, offline after 30 s", () => document.querySelectorAll('[data-testid="sensor-row"][data-status="offline"]').length === 5);
  await page.getByTestId("sensor-events").scrollIntoViewIfNeeded();
  await pointAt(page.getByTestId("sensor-events"));
  await caption("Each change is stored, published as a Kafka event and pushed here", "The connection is still up; this is the sensors, not the feed");
  await sleep(6000);

  // --- 7. and return ---
  compose("start", "simulator");
  simulatorStopped = false;
  await waitFast("The sensors are started again", () => document.querySelectorAll('[data-testid="sensor-row"][data-status="online"]').length === 5);
  await page.getByTestId("sensor-table").scrollIntoViewIfNeeded();
  await caption("Java 21 · Quarkus · Spring Boot · Kafka · MQTT · WebSockets · PostgreSQL · React", "One command to run it all: pnpm stack:up");
  await sleep(6000);
  endSegment(1);
} finally {
  if (gatewayStopped) compose("start", "gateway");
  if (simulatorStopped) compose("start", "simulator");
  await context.close(); // finishes the video file
  await browser.close();
}

// --- cut out the loading, speed up the waiting, encode ---
const raw = join(videoDir, readdirSync(videoDir).find((name) => name.endsWith(".webm")));
mkdirSync(outDir, { recursive: true });
const parts = segments.map((segment, i) => `[0:v]trim=start=${segment.start.toFixed(3)}:end=${segment.end.toFixed(3)},setpts=(PTS-STARTPTS)/${segment.speed}[v${i}]`);
const filter = `${parts.join(";")};${segments.map((_, i) => `[v${i}]`).join("")}concat=n=${segments.length}:v=1:a=0,fps=30,format=yuv420p[out]`;
const mp4 = join(outDir, "skyfeed-demo.mp4");
execFileSync("ffmpeg", ["-y", "-loglevel", "error", "-i", raw, "-filter_complex", filter, "-map", "[out]", "-c:v", "libx264", "-preset", "slow", "-crf", "27", "-movflags", "+faststart", mp4]);

// A short, silent preview that a README can show in place: the feed going down and coming back, smaller and at a low frame rate.
const downAt = segments.slice(0, 2).reduce((sum, segment) => sum + (segment.end - segment.start) / segment.speed, 0);
const gif = join(outDir, "skyfeed-demo-preview.gif");
execFileSync("ffmpeg", ["-y", "-loglevel", "error", "-ss", Math.max(0, downAt - 2).toFixed(2), "-t", "24", "-i", mp4,
  "-vf", "fps=6,scale=800:-1:flags=lanczos,split[a][b];[a]palettegen=max_colors=96[p];[b][p]paletteuse=dither=bayer:bayer_scale=4", gif]);
rmSync(videoDir, { recursive: true, force: true });

const length = segments.reduce((sum, segment) => sum + (segment.end - segment.start) / segment.speed, 0);
console.log(`wrote ${mp4} (${length.toFixed(0)} s) and ${gif}`);
