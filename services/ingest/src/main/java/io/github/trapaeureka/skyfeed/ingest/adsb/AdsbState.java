package io.github.trapaeureka.skyfeed.ingest.adsb;

/** One adsb.state.v1 message. Field names are the JSON names of the contract; times are RFC 3339 strings in UTC. */
public record AdsbState(
        String icao24,
        String callsign,
        String originCountry,
        String ts,
        String lastContact,
        double lat,
        double lon,
        Double baroAltitudeM,
        Double geoAltitudeM,
        boolean onGround,
        Double velocityMs,
        Double headingDeg,
        Double verticalRateMs,
        String squawk,
        String source) {}
