import { ageSeconds, formatAge } from "@/lib/age";
import type { SensorStatusEvent } from "@/lib/types";

/** The most recent changes of sensor status, as reported by the api service over the live feed (R-RT-02). */
export function SensorEvents({ events, now }: { events: readonly SensorStatusEvent[]; now: number | null }) {
  return (
    <section className="panel" data-testid="sensor-events">
      <h2>
        Sensor events <span className="h-note">status changes, newest first</span>
      </h2>
      {events.length === 0 ? (
        <p className="empty">No sensor has changed status yet.</p>
      ) : (
        <ol className="events">
          {events.map((event) => (
            <li key={event.eventId} data-testid="sensor-event" data-sensor-id={event.sensorId} data-status={event.status}>
              <time dateTime={event.changedAt} title={now === null ? undefined : `${formatAge(ageSeconds(event.changedAt, now))} ago`}>
                {event.changedAt.slice(11, 19)}
              </time>
              <span className="event-sensor">{event.sensorId}</span>
              <span className="event-change status" data-status={event.status}>
                <span className="event-from">{event.previousStatus} →</span>
                <span className="status-shape" aria-hidden="true" />
                {event.status}
              </span>
            </li>
          ))}
        </ol>
      )}
    </section>
  );
}
