import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { describe, expect, it } from "vitest";
import { isAdsbState, isTelemetry, parseServerMessage } from "../lib/messages";

const example = (name: string) => JSON.parse(readFileSync(fileURLToPath(new URL(`../../../contracts/examples/${name}`, import.meta.url)), "utf8")) as unknown;

describe("messages from the gateway", () => {
  it("the contract's valid examples are accepted and its invalid examples are refused", () => {
    expect(isAdsbState(example("adsb.state.v1.valid.json"))).toBe(true);
    expect(isAdsbState(example("adsb.state.v1.invalid.json"))).toBe(false);
    expect(isTelemetry(example("telemetry.v1.valid.json"))).toBe(true);
    expect(isTelemetry(example("telemetry.v1.invalid.json"))).toBe(false);
  });

  it("each message type of ws.server.v1 is read", () => {
    const state = example("adsb.state.v1.valid.json");
    const reading = example("telemetry.v1.valid.json");
    expect(parseServerMessage('{"type":"hello","heartbeatSeconds":5}')).toEqual({ type: "hello", heartbeatSeconds: 5 });
    expect(parseServerMessage('{"type":"heartbeat","ts":"2026-11-17T06:00:00.000Z"}')).toEqual({ type: "heartbeat", ts: "2026-11-17T06:00:00.000Z" });
    expect(parseServerMessage(JSON.stringify({ type: "aircraft", state }))).toEqual({ type: "aircraft", state });
    expect(parseServerMessage(JSON.stringify({ type: "telemetry", reading }))).toEqual({ type: "telemetry", reading });
    expect(parseServerMessage('{"type":"subscribed","area":null,"sensors":true}')?.type).toBe("subscribed");
    expect(parseServerMessage('{"type":"error","message":"nope"}')).toEqual({ type: "error", message: "nope" });
  });

  it("R-UI-02 anything that is not a complete message is dropped, so it can never be shown", () => {
    expect(parseServerMessage("not json")).toBeNull();
    expect(parseServerMessage("[]")).toBeNull();
    expect(parseServerMessage('{"type":"aircraft","state":{"icao24":"abc123"}}')).toBeNull();
    expect(parseServerMessage('{"type":"aircraft","state":{"icao24":"abc123","ts":"x","lastContact":"y","lat":1,"lon":2,"onGround":false}}')).toBeNull();
    expect(parseServerMessage('{"type":"telemetry","reading":{"sensorId":"gs-a"}}')).toBeNull();
    expect(parseServerMessage('{"type":"heartbeat","ts":"soon"}')).toBeNull();
    expect(parseServerMessage('{"type":"hello","heartbeatSeconds":0}')).toBeNull();
    expect(parseServerMessage('{"type":"something-else"}')).toBeNull();
  });
});
