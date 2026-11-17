import { ageSeconds, formatAge, formatAltitude, formatSpeed } from "@/lib/age";
import type { AdsbState } from "@/lib/types";

interface Props {
  icao24: string;
  state: AdsbState | null;
  now: number | null;
  staleAfter: number;
  onClose: () => void;
}

/** Details of the selected aircraft, with the age of the data (R-UI-02). */
export function AircraftCard({ icao24, state, now, staleAfter, onClose }: Props) {
  const age = state && now !== null ? ageSeconds(state.lastContact, now) : null;
  const stale = age !== null && age >= staleAfter;
  return (
    <div className="card" data-testid="aircraft-card" data-stale={stale}>
      <div className="card-head">
        <h2>{state?.callsign ?? icao24}</h2>
        <button type="button" className="close" onClick={onClose} aria-label="Close aircraft details">
          ×
        </button>
      </div>
      {!state ? (
        <p className="empty">No longer in the picture: nothing was heard from {icao24} for too long, or it left the map area.</p>
      ) : (
        <>
          <p className="card-age" data-testid="aircraft-card-age">
            {stale && <span className="tag-stale">STALE</span>} Last contact {age === null ? "…" : `${formatAge(age)} ago`}
          </p>
          <dl>
            <dt>ICAO address</dt>
            <dd>{state.icao24}</dd>
            <dt>Altitude</dt>
            <dd>{formatAltitude(state.baroAltitudeM ?? state.geoAltitudeM, state.onGround)}</dd>
            <dt>Ground speed</dt>
            <dd>{formatSpeed(state.velocityMs)}</dd>
            <dt>Heading</dt>
            <dd>{state.headingDeg === null ? "?" : `${Math.round(state.headingDeg)}°`}</dd>
            <dt>Vertical rate</dt>
            <dd>{state.verticalRateMs === null ? "?" : `${Math.round(state.verticalRateMs * 196.85)} ft/min`}</dd>
            <dt>Squawk</dt>
            <dd>{state.squawk ?? "?"}</dd>
            <dt>Country</dt>
            <dd>{state.originCountry ?? "?"}</dd>
            <dt>Source</dt>
            <dd>{state.source}</dd>
          </dl>
        </>
      )}
    </div>
  );
}
