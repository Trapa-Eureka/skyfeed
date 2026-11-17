package io.github.trapaeureka.skyfeed.ingest.adsb;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Turns one OpenSky state vector (a positional JSON array) into an adsb.state.v1 message.
 * Index: 0 icao24, 1 callsign, 2 origin_country, 3 time_position, 4 last_contact, 5 longitude, 6 latitude,
 * 7 baro_altitude, 8 on_ground, 9 velocity, 10 true_track, 11 vertical_rate, 13 geo_altitude, 14 squawk.
 */
public final class StateVectorMapper {

    private static final Pattern ICAO24 = Pattern.compile("^[0-9a-f]{6}$");
    private static final Pattern SQUAWK = Pattern.compile("^[0-7]{4}$");

    private final String source;

    public StateVectorMapper(String source) {
        this.source = source;
    }

    /**
     * Empty when the state cannot be a valid message: no usable address, no position, or no position time.
     * A single optional value that is out of range is dropped (set to null) and the rest of the state is kept.
     */
    public Optional<AdsbState> map(JsonNode state) {
        if (state == null || !state.isArray() || state.size() < 15) {
            return Optional.empty();
        }
        String icao24 = text(state, 0) == null ? null : text(state, 0).trim().toLowerCase();
        Double lon = number(state, 5);
        Double lat = number(state, 6);
        Double timePosition = number(state, 3);
        Double lastContact = number(state, 4);
        if (icao24 == null || !ICAO24.matcher(icao24).matches()
                || lat == null || lon == null || timePosition == null || lastContact == null
                || lat < -90 || lat > 90 || lon < -180 || lon > 180) {
            return Optional.empty();
        }

        String callsign = text(state, 1) == null ? null : text(state, 1).trim();
        if (callsign != null && (callsign.isEmpty() || callsign.length() > 8)) {
            callsign = null;
        }
        Double velocity = number(state, 9);
        Double heading = number(state, 10);
        String squawk = text(state, 14);

        return Optional.of(new AdsbState(
                icao24,
                callsign,
                text(state, 2),
                Instant.ofEpochSecond(timePosition.longValue()).toString(),
                Instant.ofEpochSecond(lastContact.longValue()).toString(),
                lat,
                lon,
                number(state, 7),
                number(state, 13),
                state.get(8).asBoolean(false),
                velocity != null && velocity >= 0 ? velocity : null,
                heading != null && heading >= 0 && heading <= 360 ? heading : null,
                number(state, 11),
                squawk != null && SQUAWK.matcher(squawk).matches() ? squawk : null,
                source));
    }

    private static String text(JsonNode state, int index) {
        JsonNode node = state.get(index);
        return node != null && node.isTextual() ? node.asText() : null;
    }

    private static Double number(JsonNode state, int index) {
        JsonNode node = state.get(index);
        return node != null && node.isNumber() ? node.asDouble() : null;
    }
}
