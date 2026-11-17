"use client";

import { useEffect, useState } from "react";
import { ageSeconds, formatAge } from "@/lib/age";
import type { SensorSite } from "@/lib/cms";
import type { Reading, Sensor } from "@/lib/types";
import { LineChart, type Point } from "./LineChart";

const WINDOW_MS = 15 * 60 * 1000;

/** The recent series of the selected sensor (R-UI-04): stored readings from the REST API, extended by the live feed. */
export function SensorCharts({ sensor, now, site }: { sensor: Sensor; now: number | null; site: SensorSite | null }) {
  const [readings, setReadings] = useState<Reading[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [showTable, setShowTable] = useState(false);

  useEffect(() => {
    let cancelled = false;
    fetch(`/api/v1/sensors/${sensor.sensorId}/telemetry?limit=600`, { cache: "no-store" })
      .then((response) => (response.ok ? response.json() : Promise.reject(new Error(`answered ${response.status}`))))
      .then((stored: Reading[]) => {
        if (cancelled) return;
        // Readings that arrived over the feed while this was loading are newer than the stored ones: keep both.
        setReadings((live) => [...stored, ...live.filter((r) => !stored.some((s) => s.ts === r.ts && s.seq === r.seq))].sort((a, b) => Date.parse(a.ts) - Date.parse(b.ts)));
        setError(null);
      })
      .catch((cause: Error) => !cancelled && setError(cause.message));
    return () => {
      cancelled = true;
    };
  }, [sensor.sensorId]);

  // Each new latest reading from the feed is added to the series.
  const latest = sensor.latest;
  useEffect(() => {
    setReadings((current) => {
      const last = current[current.length - 1];
      if (last && Date.parse(last.ts) >= Date.parse(latest.ts)) return current;
      return [...current.filter((r) => Date.parse(r.ts) >= Date.parse(latest.ts) - WINDOW_MS), latest];
    });
  }, [latest]);

  const end = now ?? Date.parse(latest.ts);
  const window: [number, number] = [end - WINDOW_MS, end];
  const series = (pick: (reading: Reading) => number): Point[] => readings.map((reading) => ({ t: Date.parse(reading.ts), v: pick(reading) }));
  const age = now === null ? null : ageSeconds(latest.ts, now);

  return (
    <section className="panel" data-testid="sensor-charts" data-sensor-id={sensor.sensorId}>
      <h2>
        {sensor.sensorId} <span className="h-note">last 15 minutes · latest reading {age === null ? "…" : `${formatAge(age)} ago`}</span>
      </h2>
      {site && (
        <p className="site" data-testid="sensor-site">
          <strong>{site.name}</strong>
          {site.location && <> · {site.location}</>}
          {site.notes && <span className="site-notes">{site.notes}</span>}
        </p>
      )}
      {error && <p className="empty">The stored readings could not be loaded ({error}). Readings from the live feed are still drawn.</p>}
      <LineChart title="Temperature" unit="°C" points={series((r) => r.temperatureC)} window={window} />
      <LineChart title="Signal level" unit="dBm" points={series((r) => r.signalDbm)} window={window} />
      <LineChart title="Message rate" unit="msg/s" points={series((r) => r.messageRate)} window={window} digits={0} />
      <button type="button" className="link" onClick={() => setShowTable((shown) => !shown)} aria-expanded={showTable}>
        {showTable ? "Hide" : "Show"} the last readings as a table
      </button>
      {showTable && (
        <table className="readings">
          <thead>
            <tr>
              <th scope="col">Time (UTC)</th>
              <th scope="col" className="num">°C</th>
              <th scope="col" className="num">dBm</th>
              <th scope="col" className="num">msg/s</th>
            </tr>
          </thead>
          <tbody>
            {readings.slice(-10).reverse().map((reading) => (
              <tr key={`${reading.ts}-${reading.seq}`}>
                <td>{reading.ts.slice(11, 19)}</td>
                <td className="num">{reading.temperatureC.toFixed(1)}</td>
                <td className="num">{reading.signalDbm.toFixed(1)}</td>
                <td className="num">{reading.messageRate.toFixed(0)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </section>
  );
}
