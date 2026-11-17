package io.github.trapaeureka.skyfeed.api.aircraft;

import io.github.trapaeureka.skyfeed.api.config.SkyfeedProperties;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.data.domain.Limit;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/aircraft")
@Tag(name = "Aircraft")
class AircraftController {

    static final int MAX_TRACK_MINUTES = 24 * 60;
    static final int MAX_TRACK_POINTS = 5000;
    private static final Pattern ICAO24 = Pattern.compile("^[0-9a-f]{6}$");

    record AircraftView(String icao24, String callsign, String originCountry, Instant ts, Instant lastContact,
            @Schema(description = "Seconds since the last contact.") long ageSeconds,
            double lat, double lon, Double baroAltitudeM, Double geoAltitudeM, boolean onGround, Double velocityMs,
            Double headingDeg, Double verticalRateMs, String squawk, String source) {}

    record TrackPoint(Instant ts, double lat, double lon, Double baroAltitudeM, Double geoAltitudeM, boolean onGround,
            Double velocityMs, Double headingDeg, Double verticalRateMs) {}

    private final AircraftRepository aircraft;
    private final AircraftPositionRepository positions;
    private final SkyfeedProperties properties;
    private final Clock clock;

    AircraftController(AircraftRepository aircraft, AircraftPositionRepository positions, SkyfeedProperties properties, Clock clock) {
        this.aircraft = aircraft;
        this.positions = positions;
        this.properties = properties;
        this.clock = clock;
    }

    @GetMapping
    @Operation(summary = "Current aircraft",
            description = "Aircraft whose last contact is recent enough to count as current (60 seconds by default).")
    List<AircraftView> current() {
        Instant now = clock.instant();
        return aircraft.findByLastContactGreaterThanEqualOrderByIcao24Asc(now.minus(properties.aircraft().currentWithin()))
                .stream()
                .map(a -> new AircraftView(a.getIcao24(), a.getCallsign(), a.getOriginCountry(), a.getTs(), a.getLastContact(),
                        Math.max(0, Duration.between(a.getLastContact(), now).toSeconds()), a.getLat(), a.getLon(),
                        a.getBaroAltitudeM(), a.getGeoAltitudeM(), a.isOnGround(), a.getVelocityMs(), a.getHeadingDeg(),
                        a.getVerticalRateMs(), a.getSquawk(), a.getSource()))
                .toList();
    }

    @GetMapping("/{icao24}/track")
    @Operation(summary = "The recent track of one aircraft, oldest position first",
            description = "At most 5000 positions; if there are more in the period, the newest are returned.")
    List<TrackPoint> track(
            @Parameter(description = "ICAO 24-bit address, six lower-case hex digits.") @PathVariable String icao24,
            @Parameter(description = "How far back to go, 1 to 1440 minutes.") @RequestParam(defaultValue = "30") int minutes) {
        if (!ICAO24.matcher(icao24).matches()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "icao24 must be six lower-case hex digits");
        }
        if (minutes < 1 || minutes > MAX_TRACK_MINUTES) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "minutes must be between 1 and " + MAX_TRACK_MINUTES);
        }
        if (!aircraft.existsById(icao24)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "no aircraft with address " + icao24);
        }
        Instant since = clock.instant().minus(Duration.ofMinutes(minutes));
        return positions.findByIcao24AndTsGreaterThanEqualOrderByTsDesc(icao24, since, Limit.of(MAX_TRACK_POINTS))
                .reversed().stream()
                .map(p -> new TrackPoint(p.getTs(), p.getLat(), p.getLon(), p.getBaroAltitudeM(), p.getGeoAltitudeM(),
                        p.isOnGround(), p.getVelocityMs(), p.getHeadingDeg(), p.getVerticalRateMs()))
                .toList();
    }
}
