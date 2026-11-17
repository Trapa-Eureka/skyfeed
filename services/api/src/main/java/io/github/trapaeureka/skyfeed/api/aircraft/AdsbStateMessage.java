package io.github.trapaeureka.skyfeed.api.aircraft;

import java.time.Instant;

/** An adsb.state.v1 message as read from Kafka. */
public record AdsbStateMessage(String icao24, String callsign, String originCountry, Instant ts, Instant lastContact,
        Double lat, Double lon, Double baroAltitudeM, Double geoAltitudeM, Boolean onGround, Double velocityMs,
        Double headingDeg, Double verticalRateMs, String squawk, String source) {

    public boolean isComplete() {
        return icao24 != null && !icao24.isBlank() && ts != null && lastContact != null && lat != null && lon != null
                && onGround != null && source != null;
    }
}
