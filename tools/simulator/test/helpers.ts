import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { Ajv2020 } from "ajv/dist/2020.js";
import addFormatsModule from "ajv-formats";
import type { StateVector } from "../src/replay.ts";

const addFormats = addFormatsModule as unknown as (ajv: Ajv2020) => void;

export const repoPath = (relative: string) => fileURLToPath(new URL(`../../../${relative}`, import.meta.url));
export const readJson = (relative: string) => JSON.parse(readFileSync(repoPath(relative), "utf8")) as unknown;

/** A validator for one schema in contracts/. */
export function validatorFor(schemaFile: string) {
  const ajv = new Ajv2020({ allErrors: true });
  addFormats(ajv);
  return ajv.compile(readJson(`contracts/${schemaFile}`) as object);
}

const iso = (seconds: unknown) => new Date((seconds as number) * 1000).toISOString();
const orNull = <T>(value: unknown) => (value === undefined ? null : (value as T | null));

/**
 * Reference mapping from one OpenSky state vector to an adsb.state.v1 message, used here only to check that the
 * contract fits real recorded data. The production mapping is written in `ingest` (Phase 2) against the same schema.
 */
export function toAdsbState(state: StateVector) {
  return {
    icao24: state[0],
    callsign: typeof state[1] === "string" && state[1].trim() !== "" ? state[1].trim() : null,
    originCountry: orNull<string>(state[2]),
    ts: iso(state[3]),
    lastContact: iso(state[4]),
    lon: state[5],
    lat: state[6],
    baroAltitudeM: orNull<number>(state[7]),
    onGround: state[8],
    velocityMs: orNull<number>(state[9]),
    headingDeg: orNull<number>(state[10]),
    verticalRateMs: orNull<number>(state[11]),
    geoAltitudeM: orNull<number>(state[13]),
    squawk: orNull<string>(state[14]),
    source: "replay",
  };
}
