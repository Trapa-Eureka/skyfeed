"use client";

import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { ageSeconds, sensorStatus } from "@/lib/age";
import { isNotice, isSensorSite, readCmsAnswer, type CmsState, type Notice, type SensorSite } from "@/lib/cms";
import { LiveConnection } from "@/lib/connection";
import { isAdsbState, isSensorStatusEvent } from "@/lib/messages";
import { EMPTY, inArea, pruneAircraft, sensorFromTelemetry, withAircraft, withEvents, withSensors, type Picture } from "@/lib/store";
import type { AdsbState, Area, ConnectionState, ConsoleConfig, Sensor, SensorStatusEvent, ServerMessage } from "@/lib/types";
import { AircraftCard } from "./AircraftCard";
import { MapView } from "./MapView";
import { Notices } from "./Notices";
import { SensorCharts } from "./SensorCharts";
import { SensorEvents } from "./SensorEvents";
import { SensorPanel } from "./SensorPanel";
import { StatusBar } from "./StatusBar";

async function getJson(path: string): Promise<unknown> {
  const response = await fetch(path, { cache: "no-store" });
  if (!response.ok) throw new Error(`${path} answered ${response.status}`);
  return response.json();
}

function toSensors(value: unknown): Sensor[] {
  if (!Array.isArray(value)) return [];
  return value.flatMap((item) => {
    const s = item as Partial<Sensor> | null;
    return s && typeof s.sensorId === "string" && typeof s.lat === "number" && typeof s.lon === "number" && s.latest && typeof s.latest.ts === "string"
      ? [{ sensorId: s.sensorId, lat: s.lat, lon: s.lon, latest: s.latest }]
      : [];
  });
}

export function Console({ config }: { config: ConsoleConfig }) {
  const [picture, setPicture] = useState<Picture>(EMPTY);
  const [connection, setConnection] = useState<ConnectionState>("reconnecting");
  // Time as the gateway sees it (this machine's clock corrected by the heartbeat). Null until the page runs in the browser.
  const [now, setNow] = useState<number | null>(null);
  const [lastHeartbeat, setLastHeartbeat] = useState<string | null>(null);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [events, setEvents] = useState<readonly SensorStatusEvent[]>([]);
  const [cms, setCms] = useState<{ state: CmsState; notices: Notice[]; sites: SensorSite[] }>({ state: "off", notices: [], sites: [] });
  const [selectedSensor, setSelectedSensor] = useState<string | null>(null);
  const [selectedAircraft, setSelectedAircraft] = useState<string | null>(null);

  const clockOffset = useRef(0);
  const area = useRef<Area | null>(null);
  const live = useRef<LiveConnection | null>(null);

  /** The feed sends changes only, so the state at this moment comes from the REST API. */
  const reload = useCallback(async () => {
    try {
      const [aircraft, sensors, recent] = await Promise.all([getJson("/api/v1/aircraft"), getJson("/api/v1/sensors"), getJson("/api/v1/sensor-events?limit=30")]);
      setEvents((current) => withEvents(current, (Array.isArray(recent) ? recent : []).filter(isSensorStatusEvent)));
      const states = (Array.isArray(aircraft) ? aircraft : []).filter(isAdsbState).filter((state: AdsbState) => !area.current || inArea(state, area.current));
      setPicture((current) => withSensors(withAircraft(current, states), toSensors(sensors)));
      setLoadError(null);
    } catch (error) {
      setLoadError(error instanceof Error ? error.message : "the stored data could not be loaded");
    }
  }, []);

  useEffect(() => {
    const onMessage = (message: ServerMessage) => {
      if (message.type === "aircraft") {
        // A state that was on its way when the area changed may still arrive: keep only what is in the area now.
        if (!area.current || inArea(message.state, area.current)) setPicture((current) => withAircraft(current, [message.state]));
      } else if (message.type === "telemetry") {
        setPicture((current) => withSensors(current, [sensorFromTelemetry(message.reading)]));
      } else if (message.type === "sensor-status") {
        setEvents((current) => withEvents(current, [message.event]));
      } else if (message.type === "heartbeat") {
        setLastHeartbeat(message.ts);
      }
    };
    const connectionToGateway = new LiveConnection({
      url: config.wsUrl,
      onState: setConnection,
      onMessage,
      onConnected: () => void reload(),
      onClockOffset: (offset) => (clockOffset.current = offset),
    });
    live.current = connectionToGateway;
    connectionToGateway.start();
    return () => connectionToGateway.stop();
  }, [config.wsUrl, reload]);

  // Optional CMS content: notices and sensor site details. Asked for at the start and once a minute; when there
  // is no CMS the answer is "off" and nothing changes on screen.
  useEffect(() => {
    let cancelled = false;
    const load = async () => {
      try {
        const [notices, sites] = await Promise.all([getJson("/api/cms/notices"), getJson("/api/cms/sensor-sites")]);
        const n = readCmsAnswer(notices, isNotice);
        const s = readCmsAnswer(sites, isSensorSite);
        if (!cancelled) setCms({ state: n.state, notices: n.items, sites: s.items });
      } catch {
        if (!cancelled) setCms((current) => (current.state === "off" ? current : { ...current, state: "unavailable" }));
      }
    };
    void load();
    const timer = setInterval(load, 60_000);
    return () => {
      cancelled = true;
      clearInterval(timer);
    };
  }, []);

  useEffect(() => {
    const tick = () => {
      const corrected = Date.now() + clockOffset.current;
      setNow(corrected);
      setPicture((current) => pruneAircraft(current, corrected, config.aircraftExpireSeconds));
    };
    tick();
    const timer = setInterval(tick, 1000);
    return () => clearInterval(timer);
  }, [config.aircraftExpireSeconds]);

  const onAreaChange = useCallback(
    (next: Area) => {
      area.current = next;
      live.current?.subscribe({ area: next, sensors: true });
      setPicture((current) => pruneAircraft(current, Date.now() + clockOffset.current, config.aircraftExpireSeconds, next));
      void reload();
    },
    [config.aircraftExpireSeconds, reload],
  );

  const aircraft = useMemo(() => [...picture.aircraft.values()], [picture.aircraft]);
  const sensors = useMemo(() => [...picture.sensors.values()].sort((a, b) => a.sensorId.localeCompare(b.sensorId)), [picture.sensors]);
  const selected = selectedAircraft ? (picture.aircraft.get(selectedAircraft) ?? null) : null;
  const sensor = selectedSensor ? (picture.sensors.get(selectedSensor) ?? null) : null;
  const staleAircraft = now === null ? 0 : aircraft.filter((a) => ageSeconds(a.lastContact, now) >= config.aircraftStaleSeconds).length;
  const sensorsNotOnline =
    now === null ? 0 : sensors.filter((s) => sensorStatus(ageSeconds(s.latest.ts, now), config.sensorDegradedSeconds, config.sensorOfflineSeconds) !== "online").length;

  return (
    <div className="console">
      <StatusBar
        connection={connection}
        now={now}
        lastHeartbeat={lastHeartbeat}
        aircraftCount={aircraft.length}
        staleAircraft={staleAircraft}
        sensorCount={sensors.length}
        sensorsNotOnline={sensorsNotOnline}
        loadError={loadError}
      />
      <Notices state={cms.state} notices={cms.notices} />
      <main className="workspace">
        <section className="map-area" aria-label="Aircraft map">
          <MapView
            config={config}
            aircraft={aircraft}
            sensors={sensors}
            now={now}
            selectedAircraft={selectedAircraft}
            selectedSensor={selectedSensor}
            onAreaChange={onAreaChange}
            onSelectAircraft={setSelectedAircraft}
            onSelectSensor={setSelectedSensor}
          />
          {selectedAircraft && (
            <AircraftCard icao24={selectedAircraft} state={selected} now={now} staleAfter={config.aircraftStaleSeconds} onClose={() => setSelectedAircraft(null)} />
          )}
        </section>
        <aside className="side" aria-label="Sensors">
          <SensorPanel sensors={sensors} now={now} config={config} selected={selectedSensor} onSelect={setSelectedSensor} />
          {sensor && <SensorCharts key={sensor.sensorId} sensor={sensor} now={now} site={cms.sites.find((site) => site.sensorId === sensor.sensorId) ?? null} />}
          <SensorEvents events={events} now={now} />
        </aside>
      </main>
    </div>
  );
}
