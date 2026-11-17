import { execFileSync } from "node:child_process";
import { fileURLToPath } from "node:url";
import { expect, test, type Page } from "@playwright/test";
import { formatAltitude } from "../lib/age";
import type { AdsbState } from "../lib/types";

// These tests drive the real console against the whole Compose stack with replay data.

const REPO = fileURLToPath(new URL("../../..", import.meta.url));
const compose = (...args: string[]) => execFileSync("docker", ["compose", ...args], { cwd: REPO, stdio: "pipe" });

const connection = (page: Page) => page.getByTestId("connection");
const aircraft = (page: Page) => page.locator(".ac");
const sensorRow = (page: Page, sensorId: string) => page.locator(`[data-testid="sensor-row"][data-sensor-id="${sensorId}"]`);

async function openConsole(page: Page) {
  await page.goto("/");
  await expect(connection(page)).toHaveAttribute("data-state", "connected");
  await expect(page.getByTestId("map")).toHaveAttribute("data-ready", "true");
  await expect.poll(() => aircraft(page).count()).toBeGreaterThan(5);
}

test("R-UI-01 the map shows aircraft with callsign, altitude and heading, as the API reports them", async ({ page }) => {
  await openConsole(page);

  // Every marker carries a name, an altitude and, where known, a heading the icon is turned to.
  const markers = await aircraft(page).evaluateAll((elements) =>
    elements.map((element) => ({
      callsign: element.querySelector(".ac-callsign")?.textContent ?? "",
      altitude: element.querySelector(".ac-alt")?.textContent ?? "",
      heading: (element as HTMLElement).dataset.heading ?? "",
      rotation: (element.querySelector(".ac-icon") as SVGElement).style.transform,
    })),
  );
  for (const marker of markers) {
    expect(marker.callsign).not.toBe("");
    expect(marker.altitude).toMatch(/^([\d,]+ ft|GND|alt \?)$/);
    if (marker.heading !== "") expect(marker.rotation).toMatch(/^rotate\([\d.]+deg\)$/);
  }
  expect(markers.filter((marker) => marker.heading !== "").length).toBeGreaterThan(0);

  // One aircraft compared with what the REST API holds for it. The picture moves every few seconds, so retry until both agree.
  await expect
    .poll(
      async () => {
        const states = (await (await page.request.get("/api/v1/aircraft")).json()) as AdsbState[];
        for (const state of states) {
          const marker = page.locator(`.ac[data-icao24="${state.icao24}"]`);
          if ((await marker.count()) === 0 || state.headingDeg === null) continue;
          const shown = {
            callsign: await marker.locator(".ac-callsign").textContent(),
            altitude: await marker.locator(".ac-alt").textContent(),
            heading: await marker.getAttribute("data-heading"),
          };
          const expected = {
            callsign: state.callsign ?? state.icao24,
            altitude: formatAltitude(state.baroAltitudeM ?? state.geoAltitudeM, state.onGround),
            heading: String(Math.round(state.headingDeg)),
          };
          if (JSON.stringify(shown) === JSON.stringify(expected)) return "match";
        }
        return "no marker matches the API yet";
      },
      { timeout: 60_000 },
    )
    .toBe("match");
});

test("R-CMS-02 without the optional CMS the console shows no notices, no site details and no error about them", async ({ page }) => {
  test.skip(process.env.CMS === "1", "the CMS is running in this run; cms.spec.ts covers that");
  await openConsole(page);
  expect(await (await page.request.get("/api/cms/notices")).json()).toEqual({ state: "off", items: [] });
  await sensorRow(page, "gs-iloilo").getByRole("button", { name: "gs-iloilo" }).click();
  await expect(page.getByTestId("sensor-charts")).toBeVisible();
  await expect(page.getByTestId("notices")).toHaveCount(0);
  await expect(page.getByTestId("sensor-site")).toHaveCount(0);
  await expect(page.getByTestId("load-error")).toHaveCount(0);
});

test("R-UI-02 every value from the feed is shown with its age, and the ages move", async ({ page }) => {
  await openConsole(page);

  const ages = await aircraft(page).locator(".ac-age").allTextContents();
  expect(ages.length).toBeGreaterThan(5);
  for (const age of ages) expect(age).toMatch(/^\d+ s$|^\d+ min \d\d s$/);

  await expect(page.getByTestId("sensor-row")).toHaveCount(5);
  for (const age of await page.getByTestId("sensor-age").allTextContents()) expect(age).toMatch(/^\d+ s$/);

  await expect(page.getByTestId("heartbeat-age")).toContainText(/\d+ s ago/);
  // The heartbeat age counts up between heartbeats and falls back when the next one arrives.
  const seen = new Set<string>();
  await expect
    .poll(async () => {
      seen.add((await page.getByTestId("heartbeat-age").textContent()) ?? "");
      return seen.size;
    }, { timeout: 20_000 })
    .toBeGreaterThanOrEqual(3);
});

test("R-UI-04 the sensor panel lists every sensor with status and readings; selecting one shows its recent series", async ({ page }) => {
  await openConsole(page);

  for (const sensorId of ["gs-cebu", "gs-iloilo", "gs-kalibo", "gs-legazpi", "gs-manila"]) {
    const row = sensorRow(page, sensorId);
    await expect(row).toHaveAttribute("data-status", "online");
    await expect(row.locator(".status")).toHaveText("online");
    const values = await row.locator(".value").allTextContents();
    expect(values).toHaveLength(3);
    for (const value of values) expect(Number.isFinite(Number(value))).toBe(true);
  }
  await expect(page.locator('.sensor-marker[data-status="online"]')).toHaveCount(5);

  await sensorRow(page, "gs-iloilo").getByRole("button", { name: "gs-iloilo" }).click();
  const charts = page.getByTestId("sensor-charts");
  await expect(charts).toHaveAttribute("data-sensor-id", "gs-iloilo");
  await expect(charts.getByTestId("chart")).toHaveCount(3);
  for (const line of await charts.getByTestId("chart-line").all()) {
    await expect.poll(async () => Number(await line.getAttribute("data-points"))).toBeGreaterThan(5);
  }
  await expect(charts.getByTestId("chart-readout").first()).toContainText(/latest [\d.]+ °C/);

  // The series moves on as readings arrive over the live feed: its newest point gets newer. (The number of points
  // is no measure of that. The chart shows a 15-minute window, so when readings from 15 minutes ago are sliding
  // out of it, one point leaves for each one that arrives and the count stands still.)
  const line = charts.getByTestId("chart-line").first();
  const before = Number(await line.getAttribute("data-latest"));
  expect(before).toBeGreaterThan(0);
  await expect.poll(async () => Number(await line.getAttribute("data-latest")), { timeout: 20_000 }).toBeGreaterThan(before);

  await charts.getByRole("button", { name: /as a table/ }).click();
  await expect(charts.locator("table.readings tbody tr").first()).toBeVisible();
});

test("an aircraft can be selected from the keyboard and its details show the age of the data", async ({ page }) => {
  await openConsole(page);

  const marker = aircraft(page).first();
  const icao24 = await marker.getAttribute("data-icao24");
  await marker.focus();
  await page.keyboard.press("Enter");

  const card = page.getByTestId("aircraft-card");
  await expect(card).toContainText(icao24!);
  await expect(card.getByTestId("aircraft-card-age")).toContainText(/Last contact \d+ s ago/);
  await expect(page.locator(`.ac[data-icao24="${icao24}"]`)).toHaveAttribute("data-selected", "true");

  await card.getByRole("button", { name: "Close aircraft details" }).click();
  await expect(card).toHaveCount(0);
});

test("R-OPS-01 R-RT-02 when the sensors go silent the console shows them offline and lists the changes, while the feed stays up", async ({ page }) => {
  await openConsole(page);
  await expect(page.locator('[data-testid="sensor-row"][data-status="online"]')).toHaveCount(5);
  const started = new Date().toISOString().slice(11, 19);
  const eventsSince = (status: string) =>
    page.locator(`[data-testid="sensor-event"][data-status="${status}"]`).evaluateAll(
      (items, since) => new Set(items.filter((item) => (item.querySelector("time")?.textContent ?? "") >= since).map((item) => (item as HTMLElement).dataset.sensorId)).size,
      started,
    );

  compose("stop", "simulator");
  try {
    await expect(page.locator('[data-testid="sensor-row"][data-status="offline"]')).toHaveCount(5, { timeout: 60_000 });
    // The changes were noticed by the api service and pushed over the feed, which is still connected.
    await expect.poll(() => eventsSince("offline"), { timeout: 30_000 }).toBe(5);
    await expect(connection(page)).toHaveAttribute("data-state", "connected");
    await expect(page.getByTestId("feed-down")).toHaveCount(0);
    await expect(page.locator('[data-testid="sensor-event"]').first()).toContainText("offline");
  } finally {
    compose("start", "simulator");
  }

  await expect(page.locator('[data-testid="sensor-row"][data-status="online"]')).toHaveCount(5, { timeout: 60_000 });
  await expect.poll(() => eventsSince("online"), { timeout: 30_000 }).toBe(5);
  await expect(page.locator('[data-testid="sensor-event"]').first()).toContainText("online");
  // Aircraft come from the same simulator; wait until they are current again so the next test starts clean.
  await expect.poll(() => page.locator('.ac[data-stale="false"]').count(), { timeout: 90_000 }).toBeGreaterThan(5);
});

test("R-UI-03 R-UI-02 when the live feed goes down the console says so, marks old values stale, and recovers by itself", async ({ page }) => {
  await openConsole(page);
  await expect(page.getByTestId("feed-down")).toHaveCount(0);

  compose("stop", "gateway");
  try {
    // The state is on screen within seconds, and after a longer outage it is called disconnected.
    await expect(connection(page)).toHaveAttribute("data-state", "reconnecting", { timeout: 20_000 });
    await expect(connection(page)).toHaveText("Reconnecting");
    await expect(page.getByTestId("feed-down")).toBeVisible();
    await expect(connection(page)).toHaveAttribute("data-state", "disconnected", { timeout: 30_000 });
    await expect(connection(page)).toHaveText("Disconnected");

    // Nothing is updated any more, so nothing may keep looking current: sensors go offline, aircraft go stale.
    await expect(page.locator('[data-testid="sensor-row"][data-status="offline"]')).toHaveCount(5, { timeout: 45_000 });
    await expect(sensorRow(page, "gs-iloilo").locator(".status")).toHaveText("offline");
    await expect(page.getByTestId("sensor-count")).toContainText("5 not online");
    await expect.poll(() => page.locator('.ac[data-stale="true"]').count(), { timeout: 45_000 }).toBeGreaterThan(5);
    await expect(page.locator('.ac[data-stale="false"]')).toHaveCount(0, { timeout: 45_000 });
    await expect(page.locator('.ac[data-stale="true"] .ac-stale').first()).toBeVisible();
    await expect(page.getByTestId("aircraft-count")).toContainText("stale");
  } finally {
    compose("start", "gateway");
  }

  // No reload: the console reconnects, fetches the current picture and the marks go away.
  await expect(connection(page)).toHaveAttribute("data-state", "connected", { timeout: 60_000 });
  await expect(page.getByTestId("feed-down")).toHaveCount(0);
  await expect(page.locator('[data-testid="sensor-row"][data-status="online"]')).toHaveCount(5, { timeout: 30_000 });
  await expect.poll(() => page.locator('.ac[data-stale="false"]').count(), { timeout: 60_000 }).toBeGreaterThan(5);
});
