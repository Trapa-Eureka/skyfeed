import type { AdsbState, ServerMessage, Telemetry } from "./types";

const isObject = (value: unknown): value is Record<string, unknown> => typeof value === "object" && value !== null && !Array.isArray(value);
const isTime = (value: unknown): value is string => typeof value === "string" && !Number.isNaN(Date.parse(value));
const isNumber = (value: unknown): value is number => typeof value === "number" && Number.isFinite(value);

/** True when the value has everything the console needs from an adsb.state.v1 message. */
export function isAdsbState(value: unknown): value is AdsbState {
  return (
    isObject(value) &&
    typeof value.icao24 === "string" &&
    isTime(value.ts) &&
    isTime(value.lastContact) &&
    isNumber(value.lat) &&
    isNumber(value.lon) &&
    typeof value.onGround === "boolean"
  );
}

/** True when the value has everything the console needs from a telemetry.v1 message. */
export function isTelemetry(value: unknown): value is Telemetry {
  return (
    isObject(value) &&
    typeof value.sensorId === "string" &&
    isTime(value.ts) &&
    isNumber(value.seq) &&
    isNumber(value.lat) &&
    isNumber(value.lon) &&
    isNumber(value.temperatureC) &&
    isNumber(value.signalDbm) &&
    isNumber(value.messageRate)
  );
}

/**
 * Reads one text frame from the gateway. Returns null for anything that is not a usable message:
 * the console never shows data it could not check.
 */
export function parseServerMessage(text: string): ServerMessage | null {
  let value: unknown;
  try {
    value = JSON.parse(text);
  } catch {
    return null;
  }
  if (!isObject(value)) return null;
  switch (value.type) {
    case "hello":
      return isNumber(value.heartbeatSeconds) && value.heartbeatSeconds > 0 ? { type: "hello", heartbeatSeconds: value.heartbeatSeconds } : null;
    case "subscribed":
      return typeof value.sensors === "boolean" ? (value as ServerMessage) : null;
    case "aircraft":
      return isAdsbState(value.state) ? { type: "aircraft", state: value.state } : null;
    case "telemetry":
      return isTelemetry(value.reading) ? { type: "telemetry", reading: value.reading } : null;
    case "heartbeat":
      return isTime(value.ts) ? { type: "heartbeat", ts: value.ts } : null;
    case "error":
      return typeof value.message === "string" ? { type: "error", message: value.message } : null;
    default:
      return null;
  }
}
