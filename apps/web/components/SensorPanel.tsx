import { ageSeconds, formatAge, sensorStatus } from "@/lib/age";
import type { ConsoleConfig, Sensor } from "@/lib/types";

interface Props {
  sensors: Sensor[];
  now: number | null;
  config: ConsoleConfig;
  selected: string | null;
  onSelect: (sensorId: string | null) => void;
}

/** Every sensor with its status, its latest readings and their age (R-UI-04, R-UI-02). */
export function SensorPanel({ sensors, now, config, selected, onSelect }: Props) {
  return (
    <section className="panel">
      <h2>Sensors</h2>
      {sensors.length === 0 ? (
        <p className="empty">No sensor has reported yet.</p>
      ) : (
        <table className="sensors" data-testid="sensor-table">
          <thead>
            <tr>
              <th scope="col">Sensor</th>
              <th scope="col">Status</th>
              <th scope="col" className="num">°C</th>
              <th scope="col" className="num">dBm</th>
              <th scope="col" className="num">msg/s</th>
              <th scope="col" className="num">Age</th>
            </tr>
          </thead>
          <tbody>
            {sensors.map((sensor) => {
              const age = now === null ? null : ageSeconds(sensor.latest.ts, now);
              const status = age === null ? null : sensorStatus(age, config.sensorDegradedSeconds, config.sensorOfflineSeconds);
              return (
                <tr key={sensor.sensorId} data-testid="sensor-row" data-sensor-id={sensor.sensorId} data-status={status ?? "unknown"} aria-selected={selected === sensor.sensorId}>
                  <th scope="row">
                    <button type="button" className="row-button" onClick={() => onSelect(selected === sensor.sensorId ? null : sensor.sensorId)} aria-pressed={selected === sensor.sensorId}>
                      {sensor.sensorId}
                    </button>
                  </th>
                  <td>
                    <span className="status" data-status={status ?? "unknown"}>
                      <span className="status-shape" aria-hidden="true" />
                      {status ?? "…"}
                    </span>
                  </td>
                  {/* Values of a sensor that is not online are old: they are dimmed and struck with the status beside them. */}
                  <td className="num value">{sensor.latest.temperatureC.toFixed(1)}</td>
                  <td className="num value">{sensor.latest.signalDbm.toFixed(1)}</td>
                  <td className="num value">{sensor.latest.messageRate.toFixed(0)}</td>
                  <td className="num age" data-testid="sensor-age">{age === null ? "…" : formatAge(age)}</td>
                </tr>
              );
            })}
          </tbody>
        </table>
      )}
    </section>
  );
}
