import { ageSeconds, formatAge } from "@/lib/age";
import type { ConnectionState } from "@/lib/types";

const LABEL: Record<ConnectionState, string> = {
  connected: "Connected",
  reconnecting: "Reconnecting",
  disconnected: "Disconnected",
};

interface Props {
  connection: ConnectionState;
  now: number | null;
  lastHeartbeat: string | null;
  aircraftCount: number;
  staleAircraft: number;
  sensorCount: number;
  sensorsNotOnline: number;
  loadError: string | null;
}

/** Always on screen: the state of the connection (R-UI-03), how old the last sign of life is, and what is stale. */
export function StatusBar({ connection, now, lastHeartbeat, aircraftCount, staleAircraft, sensorCount, sensorsNotOnline, loadError }: Props) {
  const heartbeatAge = now !== null && lastHeartbeat ? ageSeconds(lastHeartbeat, now) : null;
  return (
    <header className="statusbar">
      <div className="brand">
        SkyFeed <span className="brand-note">demo · not for operational use</span>
      </div>
      <div className="conn" data-testid="connection" data-state={connection} role="status" aria-live="polite">
        <span className="conn-shape" aria-hidden="true" />
        <span className="conn-label">{LABEL[connection]}</span>
      </div>
      <div className="stat" data-testid="heartbeat-age">
        <span className="stat-label">Last heartbeat</span>
        <span className="stat-value">{heartbeatAge === null ? "none yet" : `${formatAge(heartbeatAge)} ago`}</span>
      </div>
      <div className="stat" data-testid="aircraft-count">
        <span className="stat-label">Aircraft</span>
        <span className="stat-value">
          {aircraftCount}
          {staleAircraft > 0 && <span className="stat-warn"> · {staleAircraft} stale</span>}
        </span>
      </div>
      <div className="stat" data-testid="sensor-count">
        <span className="stat-label">Sensors</span>
        <span className="stat-value">
          {sensorCount}
          {sensorsNotOnline > 0 && <span className="stat-warn"> · {sensorsNotOnline} not online</span>}
        </span>
      </div>
      <div className="stat clock">
        <span className="stat-label">UTC</span>
        <span className="stat-value">{now === null ? "--:--:--" : new Date(now).toISOString().slice(11, 19)}</span>
      </div>
      {connection !== "connected" && (
        <div className="banner" role="alert" data-testid="feed-down">
          The live feed is down. Values on screen are not being updated; check their age.
        </div>
      )}
      {loadError && (
        <div className="banner" role="alert" data-testid="load-error">
          Stored data could not be loaded ({loadError}).
        </div>
      )}
    </header>
  );
}
