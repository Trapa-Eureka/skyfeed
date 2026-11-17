// A local stand-in for the OpenSky REST API, serving recorded frames. Only what `ingest` uses is implemented.
import { createServer, type Server } from "node:http";
import { frameAt, withinBox, type BoundingBox, type Frame } from "./replay.ts";

export interface MockOptions {
  frames: Frame[];
  startMs: number;
  speed?: number;
  /** Injected in tests so responses do not depend on the real clock. */
  now?: () => number;
}

function parseBox(params: URLSearchParams): BoundingBox | null | "invalid" {
  const keys = ["lamin", "lomin", "lamax", "lomax"] as const;
  if (!keys.some((key) => params.has(key))) return null;
  const values = keys.map((key) => (params.has(key) ? Number(params.get(key)) : NaN));
  if (values.some((value) => !Number.isFinite(value))) return "invalid";
  const [lamin, lomin, lamax, lomax] = values as [number, number, number, number];
  return { lamin, lomin, lamax, lomax };
}

export function createOpenSkyMock({ frames, startMs, speed = 1, now = Date.now }: MockOptions): Server {
  return createServer((req, res) => {
    const url = new URL(req.url ?? "/", "http://localhost");
    const json = (status: number, body: unknown) => {
      res.writeHead(status, { "content-type": "application/json" });
      res.end(JSON.stringify(body));
    };

    if (req.method !== "GET") return json(405, { error: "method not allowed" });
    if (url.pathname === "/health") return json(200, { status: "UP", frames: frames.length });
    if (url.pathname !== "/api/states/all") return json(404, { error: "not found" });

    const box = parseBox(url.searchParams);
    if (box === "invalid") return json(400, { error: "lamin, lomin, lamax and lomax must all be numbers" });
    const frame = frameAt(frames, startMs, now(), speed);
    return json(200, box ? withinBox(frame, box) : frame);
  });
}
