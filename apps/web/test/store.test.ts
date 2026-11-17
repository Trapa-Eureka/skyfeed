import { describe, expect, it } from "vitest";
import { EMPTY, pruneAircraft, sensorFromTelemetry, withAircraft, withSensors } from "../lib/store";
import type { AdsbState, Telemetry } from "../lib/types";

const state = (icao24: string, lastContact: string, lat = 14.5, lon = 121): AdsbState => ({
  icao24, callsign: "TEST1", originCountry: null, ts: lastContact, lastContact, lat, lon, baroAltitudeM: 1000, geoAltitudeM: null,
  onGround: false, velocityMs: 200, headingDeg: 90, verticalRateMs: 0, squawk: null, source: "replay",
});
const reading = (sensorId: string, ts: string, temperatureC: number): Telemetry => ({
  sensorId, ts, seq: 1, lat: 10.8, lon: 122.5, temperatureC, signalDbm: -70, messageRate: 180,
});

describe("the live picture", () => {
  it("R-UI-01 an aircraft is held at its last reported position", () => {
    let picture = withAircraft(EMPTY, [state("abc123", "2026-11-17T06:00:00Z", 14.1)]);
    picture = withAircraft(picture, [state("abc123", "2026-11-17T06:00:10Z", 14.2)]);
    expect(picture.aircraft.get("abc123")?.lat).toBe(14.2);
    expect(picture.aircraft.size).toBe(1);
  });

  it("R-UI-02 a state older than the one on screen never replaces it", () => {
    const current = withAircraft(EMPTY, [state("abc123", "2026-11-17T06:00:10Z", 14.2)]);
    const after = withAircraft(current, [state("abc123", "2026-11-17T06:00:00Z", 14.1), state("abc123", "2026-11-17T06:00:10Z", 14.9)]);
    expect(after).toBe(current); // nothing changed, same object
  });

  it("R-UI-04 a sensor keeps its newest reading; an older one is ignored", () => {
    let picture = withSensors(EMPTY, [sensorFromTelemetry(reading("gs-a", "2026-11-17T06:00:10Z", 36))]);
    picture = withSensors(picture, [sensorFromTelemetry(reading("gs-a", "2026-11-17T06:00:05Z", 30))]);
    picture = withSensors(picture, [sensorFromTelemetry(reading("gs-b", "2026-11-17T06:00:05Z", 31))]);
    expect(picture.sensors.get("gs-a")?.latest.temperatureC).toBe(36);
    expect([...picture.sensors.keys()]).toEqual(["gs-a", "gs-b"]);
  });

  it("R-OPS-02 an aircraft not heard from for the configured time is removed from the picture", () => {
    const now = Date.parse("2026-11-17T06:02:00Z");
    const picture = withAircraft(EMPTY, [state("old111", "2026-11-17T06:00:00Z"), state("new222", "2026-11-17T06:01:30Z")]);
    const pruned = pruneAircraft(picture, now, 120);
    expect([...pruned.aircraft.keys()]).toEqual(["new222"]);
    expect(pruneAircraft(pruned, now, 120)).toBe(pruned);
  });

  it("an aircraft outside the area being watched is removed when the area changes", () => {
    const now = Date.parse("2026-11-17T06:00:05Z");
    const picture = withAircraft(EMPTY, [state("man111", "2026-11-17T06:00:00Z", 14.5, 121), state("ceb222", "2026-11-17T06:00:00Z", 10.3, 123.9)]);
    const pruned = pruneAircraft(picture, now, 120, { lamin: 14, lomin: 120, lamax: 15, lomax: 122 });
    expect([...pruned.aircraft.keys()]).toEqual(["man111"]);
  });
});
