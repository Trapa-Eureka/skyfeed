import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { LiveConnection, type SocketLike } from "../lib/connection";
import type { ConnectionState, ServerMessage } from "../lib/types";

class FakeSocket implements SocketLike {
  onopen: (() => void) | null = null;
  onmessage: ((event: { data: unknown }) => void) | null = null;
  onclose: (() => void) | null = null;
  onerror: (() => void) | null = null;
  sent: string[] = [];
  closed = false;
  send(data: string) {
    this.sent.push(data);
  }
  close() {
    this.closed = true;
  }
  /** The gateway accepts the connection and greets. */
  accept(heartbeatSeconds = 5) {
    this.onopen?.();
    this.receive({ type: "hello", heartbeatSeconds });
  }
  receive(message: ServerMessage) {
    this.onmessage?.({ data: JSON.stringify(message) });
  }
}

const AREA = { lamin: 10, lomin: 119.5, lamax: 15, lomax: 124.5 };

describe("connection to the gateway", () => {
  let sockets: FakeSocket[];
  let states: ConnectionState[];
  let messages: ServerMessage[];
  let connectedCount: number;
  let connection: LiveConnection;
  const last = () => sockets[sockets.length - 1]!;

  beforeEach(() => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date("2026-11-17T06:00:00Z"));
    sockets = [];
    states = [];
    messages = [];
    connectedCount = 0;
    connection = new LiveConnection({
      url: "ws://test/ws/v1",
      createSocket: () => {
        const socket = new FakeSocket();
        sockets.push(socket);
        return socket;
      },
      onState: (state) => states.push(state),
      onMessage: (message) => messages.push(message),
      onConnected: () => connectedCount++,
      disconnectedAfterMs: 15_000,
    });
  });
  afterEach(() => {
    connection.stop();
    vi.useRealTimers();
  });

  it("R-UI-03 the state is reconnecting until the gateway has answered, then connected", () => {
    connection.start();
    expect(states).toEqual(["reconnecting"]);
    last().onopen?.();
    expect(states).toEqual(["reconnecting"]); // open is not enough: nothing came from the gateway yet
    last().receive({ type: "hello", heartbeatSeconds: 5 });
    expect(states).toEqual(["reconnecting", "connected"]);
    expect(connectedCount).toBe(1);
  });

  it("R-UI-03 a lost connection is replaced by itself, and the subscription is sent again", () => {
    connection.start();
    connection.subscribe({ area: AREA, sensors: true });
    last().accept();
    expect(JSON.parse(last().sent[0]!)).toEqual({ type: "subscribe", area: AREA, sensors: true });

    last().onclose?.();
    expect(states.at(-1)).toBe("reconnecting");
    vi.advanceTimersByTime(1000);
    expect(sockets.length).toBe(2);
    last().accept();

    expect(states).toEqual(["reconnecting", "connected", "reconnecting", "connected"]);
    expect(JSON.parse(last().sent[0]!)).toEqual({ type: "subscribe", area: AREA, sensors: true });
    expect(connectedCount).toBe(2); // the console reloads the picture each time
  });

  it("R-UI-03 an outage longer than the limit is reported as disconnected, and retries go on", () => {
    connection.start();
    last().accept();
    last().onclose?.();
    vi.advanceTimersByTime(14_000);
    expect(states.at(-1)).toBe("reconnecting");
    for (let i = 0; i < 30; i++) {
      last().onerror?.(); // every attempt fails
      vi.advanceTimersByTime(1000);
    }
    expect(states.at(-1)).toBe("disconnected");
    const attempts = sockets.length;
    vi.advanceTimersByTime(60_000);
    expect(sockets.length).toBeGreaterThan(attempts);

    last().accept();
    expect(states.at(-1)).toBe("connected");
  });

  it("R-UI-03 a connection that stays open but goes silent for three heartbeat intervals counts as broken", () => {
    connection.start();
    last().accept(5);
    vi.advanceTimersByTime(14_000);
    last().receive({ type: "heartbeat", ts: new Date().toISOString() });
    vi.advanceTimersByTime(14_000);
    expect(states.at(-1)).toBe("connected");

    vi.advanceTimersByTime(1_000); // 15 s since the last message
    expect(states.at(-1)).toBe("reconnecting");
    expect(sockets[0]!.closed).toBe(true);
  });

  it("the waits between attempts grow to at most 10 seconds", () => {
    connection.start();
    const waits: number[] = [];
    for (let i = 0; i < 7; i++) {
      const before = sockets.length;
      last().onerror?.();
      let waited = 0;
      while (sockets.length === before) {
        vi.advanceTimersByTime(500);
        waited += 500;
      }
      waits.push(waited);
    }
    expect(waits).toEqual([1000, 2000, 4000, 8000, 10000, 10000, 10000]);
  });

  it("messages are passed on, unusable ones are not, and the clock offset comes from heartbeats", () => {
    const offsets: number[] = [];
    connection.stop();
    connection = new LiveConnection({
      url: "ws://test/ws/v1",
      createSocket: () => {
        const socket = new FakeSocket();
        sockets.push(socket);
        return socket;
      },
      onState: () => {},
      onMessage: (message) => messages.push(message),
      onConnected: () => {},
      onClockOffset: (offset) => offsets.push(offset),
    });
    connection.start();
    last().accept();
    last().onmessage?.({ data: "garbage" });
    last().receive({ type: "heartbeat", ts: "2026-11-17T06:00:02.000Z" }); // the gateway's clock is 2 s ahead

    expect(messages.map((m) => m.type)).toEqual(["hello", "heartbeat"]);
    expect(offsets).toEqual([2000]);
  });

  it("after stop nothing reconnects", () => {
    connection.start();
    last().accept();
    connection.stop();
    vi.advanceTimersByTime(120_000);
    expect(sockets.length).toBe(1);
    expect(sockets[0]!.closed).toBe(true);
  });
});
