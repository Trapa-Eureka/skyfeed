// Simulated ground stations. A reading depends only on (seed, sensor, seq, time), so a run can be repeated exactly.

export interface Sensor {
  id: string;
  lat: number;
  lon: number;
  /** Typical values this station moves around. */
  base: { temperatureC: number; signalDbm: number; messageRate: number };
}

/** Message shape of contracts/telemetry.v1.schema.json. */
export interface Telemetry {
  sensorId: string;
  ts: string;
  seq: number;
  lat: number;
  lon: number;
  temperatureC: number;
  signalDbm: number;
  messageRate: number;
}

// Five stations inside the recorded map area (central Philippines).
export const SENSORS: readonly Sensor[] = [
  { id: "gs-manila", lat: 14.509, lon: 121.02, base: { temperatureC: 38, signalDbm: -62, messageRate: 420 } },
  { id: "gs-iloilo", lat: 10.833, lon: 122.493, base: { temperatureC: 36, signalDbm: -71, messageRate: 180 } },
  { id: "gs-cebu", lat: 10.308, lon: 123.979, base: { temperatureC: 37, signalDbm: -66, messageRate: 310 } },
  { id: "gs-kalibo", lat: 11.679, lon: 122.376, base: { temperatureC: 35, signalDbm: -74, messageRate: 120 } },
  { id: "gs-legazpi", lat: 13.157, lon: 123.735, base: { temperatureC: 34, signalDbm: -77, messageRate: 90 } },
];

/** FNV-1a hash of a string to an unsigned 32-bit integer. */
function hash(text: string): number {
  let h = 0x811c9dc5;
  for (let i = 0; i < text.length; i++) {
    h ^= text.charCodeAt(i);
    h = Math.imul(h, 0x01000193);
  }
  return h >>> 0;
}

/** Deterministic noise in [-1, 1) for one (seed, sensor, seq, channel). */
function noise(seed: string, sensorId: string, seq: number, channel: string): number {
  // mulberry32, one step from a hashed state
  let t = (hash(`${seed}:${sensorId}:${seq}:${channel}`) + 0x6d2b79f5) >>> 0;
  t = Math.imul(t ^ (t >>> 15), t | 1);
  t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
  return (((t ^ (t >>> 14)) >>> 0) / 4294967296) * 2 - 1;
}

const round = (value: number, digits: number) => Number(value.toFixed(digits));

export function topicFor(sensorId: string): string {
  return `sensors/${sensorId}/telemetry`;
}

/** The reading sensor `sensor` reports as its `seq`-th message at time `tsMs`. Pure. */
export function reading(sensor: Sensor, seq: number, tsMs: number, seed: string): Telemetry {
  const n = (channel: string) => noise(seed, sensor.id, seq, channel);
  const drift = Math.sin(seq / 40); // slow swing so the series is not flat
  return {
    sensorId: sensor.id,
    ts: new Date(tsMs).toISOString(),
    seq,
    lat: sensor.lat,
    lon: sensor.lon,
    temperatureC: round(sensor.base.temperatureC + 3 * drift + 0.4 * n("t"), 2),
    signalDbm: round(sensor.base.signalDbm + 2 * drift + 1.5 * n("s"), 1),
    messageRate: round(Math.max(0, sensor.base.messageRate * (1 + 0.15 * drift + 0.05 * n("m"))), 1),
  };
}
