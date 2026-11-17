import { ageSeconds } from "./age";
import type { AdsbState, Area, Sensor, SensorStatusEvent, Telemetry } from "./types";

/** Everything the console knows about the live picture. Updates return a new object; nothing is changed in place. */
export interface Picture {
  aircraft: ReadonlyMap<string, AdsbState>;
  sensors: ReadonlyMap<string, Sensor>;
}

export const EMPTY: Picture = { aircraft: new Map(), sensors: new Map() };

const isNewer = (candidate: string, current: string) => Date.parse(candidate) > Date.parse(current);

export function inArea(state: { lat: number; lon: number }, area: Area): boolean {
  return state.lat >= area.lamin && state.lat <= area.lamax && state.lon >= area.lomin && state.lon <= area.lomax;
}

/** Takes in aircraft states from the feed or the REST API. A state older than the one already held is ignored. */
export function withAircraft(picture: Picture, states: readonly AdsbState[]): Picture {
  let aircraft: Map<string, AdsbState> | null = null;
  for (const state of states) {
    const current = (aircraft ?? picture.aircraft).get(state.icao24);
    if (current && !isNewer(state.lastContact, current.lastContact)) continue;
    aircraft ??= new Map(picture.aircraft);
    aircraft.set(state.icao24, state);
  }
  return aircraft ? { ...picture, aircraft } : picture;
}

/** Takes in sensors with their latest reading. A reading older than the one already held is ignored. */
export function withSensors(picture: Picture, incoming: readonly Sensor[]): Picture {
  let sensors: Map<string, Sensor> | null = null;
  for (const sensor of incoming) {
    const current = (sensors ?? picture.sensors).get(sensor.sensorId);
    if (current && !isNewer(sensor.latest.ts, current.latest.ts)) continue;
    sensors ??= new Map(picture.sensors);
    sensors.set(sensor.sensorId, sensor);
  }
  return sensors ? { ...picture, sensors } : picture;
}

/**
 * Adds status changes to the list kept for the event panel: newest first, each event once (the feed and the REST
 * API can both deliver the same one, and delivery is at least once), at most `keep` entries.
 */
export function withEvents(current: readonly SensorStatusEvent[], incoming: readonly SensorStatusEvent[], keep = 30): readonly SensorStatusEvent[] {
  const known = new Set(current.map((event) => event.eventId));
  const added = incoming.filter((event) => !known.has(event.eventId) && known.add(event.eventId));
  if (added.length === 0) return current;
  return [...current, ...added].sort((a, b) => Date.parse(b.changedAt) - Date.parse(a.changedAt)).slice(0, keep);
}

export function sensorFromTelemetry(reading: Telemetry): Sensor {
  const { sensorId, lat, lon, ts, seq, temperatureC, signalDbm, messageRate } = reading;
  return { sensorId, lat, lon, latest: { ts, seq, temperatureC, signalDbm, messageRate } };
}

/**
 * Removes aircraft that have not been heard from for `expireSeconds` (R-OPS-02), and, when an area is given,
 * those outside it (no more updates will come for them).
 */
export function pruneAircraft(picture: Picture, nowMs: number, expireSeconds: number, area?: Area | null): Picture {
  let aircraft: Map<string, AdsbState> | null = null;
  for (const [icao24, state] of picture.aircraft) {
    if (ageSeconds(state.lastContact, nowMs) >= expireSeconds || (area && !inArea(state, area))) {
      aircraft ??= new Map(picture.aircraft);
      aircraft.delete(icao24);
    }
  }
  return aircraft ? { ...picture, aircraft } : picture;
}
