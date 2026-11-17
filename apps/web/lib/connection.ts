import { parseServerMessage } from "./messages";
import type { Area, ConnectionState, ServerMessage } from "./types";

/** The part of the browser WebSocket this class uses, so a test can pass a stand-in. */
export interface SocketLike {
  onopen: (() => void) | null;
  onmessage: ((event: { data: unknown }) => void) | null;
  onclose: (() => void) | null;
  onerror: (() => void) | null;
  send(data: string): void;
  close(): void;
}

export interface Subscription {
  area: Area | null;
  sensors: boolean;
}

export interface LiveConnectionOptions {
  url: string;
  createSocket?: (url: string) => SocketLike;
  onState: (state: ConnectionState) => void;
  onMessage: (message: ServerMessage) => void;
  /** Called every time the feed is up again after being down, and on the first connect: time to reload the picture. */
  onConnected: () => void;
  /** The gateway's clock minus this machine's clock, in ms, measured from heartbeats. */
  onClockOffset?: (offsetMs: number) => void;
  /** How long an outage may last before the state changes from reconnecting to disconnected. */
  disconnectedAfterMs?: number;
}

const BACKOFF_MS = [1000, 2000, 4000, 8000, 10000];
/** Without a heartbeat interval from the gateway yet, wait this long for the first message. */
const FIRST_MESSAGE_MS = 10_000;
/** How many heartbeat intervals may pass in silence before the connection counts as broken. */
const MISSED_HEARTBEATS = 3;

/**
 * The console's connection to the gateway (R-UI-03). It reconnects by itself, forever. It reports three states:
 * connected; reconnecting (down, or not up yet, for a short time); disconnected (down for longer than
 * `disconnectedAfterMs`; it still keeps trying). A connection that is open but silent for three heartbeat
 * intervals is treated as broken and replaced.
 */
export class LiveConnection {
  private readonly options: Required<Omit<LiveConnectionOptions, "onClockOffset">> & Pick<LiveConnectionOptions, "onClockOffset">;
  private socket: SocketLike | null = null;
  private state: ConnectionState = "reconnecting";
  private subscription: Subscription | null = null;
  private attempts = 0;
  private stopped = true;
  private open = false;
  private heartbeatMs = 0;
  private retryTimer: ReturnType<typeof setTimeout> | null = null;
  private silenceTimer: ReturnType<typeof setTimeout> | null = null;
  private outageTimer: ReturnType<typeof setTimeout> | null = null;

  constructor(options: LiveConnectionOptions) {
    this.options = {
      createSocket: (url) => new WebSocket(url) as unknown as SocketLike,
      disconnectedAfterMs: 15_000,
      ...options,
    };
  }

  start(): void {
    if (!this.stopped) return;
    this.stopped = false;
    this.options.onState(this.state);
    this.startOutageTimer();
    this.connect();
  }

  stop(): void {
    this.stopped = true;
    for (const timer of [this.retryTimer, this.silenceTimer, this.outageTimer]) if (timer) clearTimeout(timer);
    this.retryTimer = this.silenceTimer = this.outageTimer = null;
    this.dropSocket();
  }

  /** What to receive. Remembered, and sent again after every reconnect. */
  subscribe(subscription: Subscription): void {
    this.subscription = subscription;
    if (this.open) this.sendSubscription();
  }

  private connect(): void {
    if (this.stopped) return;
    const socket = this.options.createSocket(this.options.url);
    this.socket = socket;
    this.heartbeatMs = 0;
    socket.onopen = () => {
      if (socket !== this.socket) return;
      this.open = true;
      this.sendSubscription();
      this.watchSilence(); // not "connected" yet: that needs a message from the gateway
    };
    socket.onmessage = (event) => {
      if (socket !== this.socket || typeof event.data !== "string") return;
      const message = parseServerMessage(event.data);
      if (!message) return;
      if (message.type === "hello") this.heartbeatMs = message.heartbeatSeconds * 1000;
      if (message.type === "heartbeat") this.options.onClockOffset?.(Date.parse(message.ts) - Date.now());
      this.watchSilence();
      if (this.state !== "connected") {
        this.attempts = 0;
        if (this.outageTimer) clearTimeout(this.outageTimer);
        this.outageTimer = null;
        this.setState("connected");
        this.options.onConnected();
      }
      this.options.onMessage(message);
    };
    socket.onclose = () => {
      if (socket === this.socket) this.lost();
    };
    socket.onerror = () => {
      if (socket === this.socket) this.lost();
    };
  }

  private sendSubscription(): void {
    if (this.subscription && this.socket) this.socket.send(JSON.stringify({ type: "subscribe", ...this.subscription }));
  }

  private watchSilence(): void {
    if (this.silenceTimer) clearTimeout(this.silenceTimer);
    const limit = this.heartbeatMs > 0 ? this.heartbeatMs * MISSED_HEARTBEATS : FIRST_MESSAGE_MS;
    this.silenceTimer = setTimeout(() => this.lost(), limit);
  }

  private lost(): void {
    if (this.stopped) return;
    this.dropSocket();
    if (this.state === "connected") {
      this.setState("reconnecting");
      this.startOutageTimer();
    }
    if (this.retryTimer) return;
    const delay = BACKOFF_MS[Math.min(this.attempts, BACKOFF_MS.length - 1)]!;
    this.attempts++;
    this.retryTimer = setTimeout(() => {
      this.retryTimer = null;
      this.connect();
    }, delay);
  }

  private startOutageTimer(): void {
    if (this.outageTimer) clearTimeout(this.outageTimer);
    this.outageTimer = setTimeout(() => {
      this.outageTimer = null;
      if (this.state === "reconnecting") this.setState("disconnected");
    }, this.options.disconnectedAfterMs);
  }

  private dropSocket(): void {
    if (this.silenceTimer) clearTimeout(this.silenceTimer);
    this.silenceTimer = null;
    this.open = false;
    const socket = this.socket;
    this.socket = null;
    if (socket) {
      socket.onopen = socket.onmessage = socket.onclose = socket.onerror = null;
      try {
        socket.close();
      } catch {
        // already closed
      }
    }
  }

  private setState(state: ConnectionState): void {
    if (state === this.state) return;
    this.state = state;
    this.options.onState(state);
  }
}
