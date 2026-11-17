// Shapes of the data the console handles. They follow the contracts in /contracts
// (adsb.state.v1, telemetry.v1, ws.server.v1) and the REST API of the api service.

export interface AdsbState {
  icao24: string;
  callsign: string | null;
  originCountry: string | null;
  ts: string;
  lastContact: string;
  lat: number;
  lon: number;
  baroAltitudeM: number | null;
  geoAltitudeM: number | null;
  onGround: boolean;
  velocityMs: number | null;
  headingDeg: number | null;
  verticalRateMs: number | null;
  squawk: string | null;
  source: string;
}

export interface Reading {
  ts: string;
  seq: number;
  temperatureC: number;
  signalDbm: number;
  messageRate: number;
}

export interface Telemetry extends Reading {
  sensorId: string;
  lat: number;
  lon: number;
}

/** A sensor as the console keeps it: where it is and its latest reading. */
export interface Sensor {
  sensorId: string;
  lat: number;
  lon: number;
  latest: Reading;
}

export interface Area {
  lamin: number;
  lomin: number;
  lamax: number;
  lomax: number;
}

/** A sensor.status.v1 message: a sensor went from one status to another. */
export interface SensorStatusEvent {
  eventId: string;
  sensorId: string;
  status: SensorStatus;
  previousStatus: SensorStatus;
  changedAt: string;
  lastReadingTs: string;
}

export type ServerMessage =
  | { type: "hello"; heartbeatSeconds: number }
  | { type: "subscribed"; area: Area | null; sensors: boolean }
  | { type: "aircraft"; state: AdsbState }
  | { type: "telemetry"; reading: Telemetry }
  | { type: "sensor-status"; event: SensorStatusEvent }
  | { type: "heartbeat"; ts: string }
  | { type: "error"; message: string };

export type ConnectionState = "connected" | "reconnecting" | "disconnected";

export type SensorStatus = "online" | "degraded" | "offline";

/** Settings the server passes to the page at request time. */
export interface ConsoleConfig {
  wsUrl: string;
  /** A MapLibre style URL, or "none" for a plain background with a grid (no network needed). */
  mapStyleUrl: string;
  aircraftStaleSeconds: number;
  aircraftExpireSeconds: number;
  sensorDegradedSeconds: number;
  sensorOfflineSeconds: number;
}
