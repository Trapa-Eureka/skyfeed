// Replays recorded OpenSky `GET /api/states/all` responses.
// Which frame is served, and with which timestamps, depends only on (frames, start time, current time, speed).
import { readFileSync } from "node:fs";

/** One aircraft in the OpenSky wire format: a positional array. Index 3 = time_position, 4 = last_contact, 5 = longitude, 6 = latitude. */
export type StateVector = unknown[];

export interface Frame {
  /** Unix seconds the snapshot is valid for. */
  time: number;
  states: StateVector[];
}

export interface BoundingBox {
  lamin: number;
  lomin: number;
  lamax: number;
  lomax: number;
}

const TIME_POSITION = 3;
const LAST_CONTACT = 4;
const LONGITUDE = 5;
const LATITUDE = 6;

export function parseFrames(jsonl: string): Frame[] {
  const frames = jsonl
    .split("\n")
    .filter((line) => line.trim() !== "")
    .map((line) => JSON.parse(line) as Frame);
  if (frames.length === 0) throw new Error("replay file has no frames");
  for (let i = 1; i < frames.length; i++) {
    if (frames[i]!.time <= frames[i - 1]!.time) throw new Error(`replay frames are not in time order at line ${i + 1}`);
  }
  return frames;
}

export function loadFrames(path: string): Frame[] {
  return parseFrames(readFileSync(path, "utf8"));
}

/** Recorded seconds one pass over the file covers. The last frame is given the average gap, so a loop has no jump. */
export function loopSeconds(frames: Frame[]): number {
  const first = frames[0]!.time;
  const last = frames[frames.length - 1]!.time;
  if (frames.length === 1) return 10;
  return last - first + Math.round((last - first) / (frames.length - 1));
}

/**
 * The response the replay server gives at `nowMs` when it was started at `startMs`.
 * Recorded times are moved so the recording appears to begin at `startMs`; after the last frame it starts over,
 * and time keeps moving forward. `speed` 2 plays the recording twice as fast. Pure.
 */
export function frameAt(frames: Frame[], startMs: number, nowMs: number, speed = 1): Frame {
  const first = frames[0]!.time;
  const loop = loopSeconds(frames);
  const played = Math.max(0, ((nowMs - startMs) / 1000) * speed); // recorded seconds played so far
  const loops = Math.floor(played / loop);
  const offset = played - loops * loop;

  let frame = frames[0]!;
  for (const candidate of frames) {
    if (candidate.time - first <= offset) frame = candidate;
    else break;
  }

  // recorded second -> wall-clock second
  const toWall = (recorded: number) => Math.floor(startMs / 1000 + (loops * loop + (recorded - first)) / speed);
  return {
    time: toWall(frame.time),
    states: frame.states.map((state) => {
      const moved = [...state];
      if (typeof moved[TIME_POSITION] === "number") moved[TIME_POSITION] = toWall(moved[TIME_POSITION]);
      if (typeof moved[LAST_CONTACT] === "number") moved[LAST_CONTACT] = toWall(moved[LAST_CONTACT]);
      return moved;
    }),
  };
}

/** Keeps the aircraft inside the box, as the real API does for the lamin/lomin/lamax/lomax query. */
export function withinBox(frame: Frame, box: BoundingBox): Frame {
  return {
    time: frame.time,
    states: frame.states.filter((state) => {
      const lon = state[LONGITUDE];
      const lat = state[LATITUDE];
      return typeof lon === "number" && typeof lat === "number" && lat >= box.lamin && lat <= box.lamax && lon >= box.lomin && lon <= box.lomax;
    }),
  };
}
