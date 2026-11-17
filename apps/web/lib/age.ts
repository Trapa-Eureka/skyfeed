import type { SensorStatus } from "./types";

/** Whole seconds between a timestamp and now; never negative. */
export function ageSeconds(ts: string, nowMs: number): number {
  return Math.max(0, Math.floor((nowMs - Date.parse(ts)) / 1000));
}

/** A short age for a label: "4 s", "2 min 05 s", "1 h 03 min". */
export function formatAge(seconds: number): string {
  if (seconds < 60) return `${seconds} s`;
  if (seconds < 3600) return `${Math.floor(seconds / 60)} min ${String(seconds % 60).padStart(2, "0")} s`;
  return `${Math.floor(seconds / 3600)} h ${String(Math.floor((seconds % 3600) / 60)).padStart(2, "0")} min`;
}

/** The same rule the api service uses: online, degraded after one threshold, offline after the other. */
export function sensorStatus(age: number, degradedAfter: number, offlineAfter: number): SensorStatus {
  if (age < degradedAfter) return "online";
  return age < offlineAfter ? "degraded" : "offline";
}

/** Altitude the way a controller reads it: feet, to the nearest hundred, or GND. */
export function formatAltitude(metres: number | null, onGround: boolean): string {
  if (onGround) return "GND";
  if (metres === null) return "alt ?";
  return `${(Math.round((metres * 3.28084) / 100) * 100).toLocaleString("en-US")} ft`;
}

export function formatSpeed(metresPerSecond: number | null): string {
  return metresPerSecond === null ? "?" : `${Math.round(metresPerSecond * 1.94384)} kt`;
}
