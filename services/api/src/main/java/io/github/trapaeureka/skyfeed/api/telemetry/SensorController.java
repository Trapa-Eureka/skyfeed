package io.github.trapaeureka.skyfeed.api.telemetry;

import io.github.trapaeureka.skyfeed.api.config.SkyfeedProperties;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.Limit;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/sensors")
@Tag(name = "Sensors")
class SensorController {

    static final int DEFAULT_LIMIT = 1000;
    static final int MAX_LIMIT = 5000;
    private static final Duration DEFAULT_RANGE = Duration.ofMinutes(15);

    record Reading(Instant ts, long seq, double temperatureC, double signalDbm, double messageRate) {}

    record SensorView(
            String sensorId,
            double lat,
            double lon,
            @Schema(allowableValues = {"online", "degraded", "offline"},
                    description = "From the age of the latest reading at the time of the request.")
            String status,
            @Schema(description = "Seconds since the latest reading was taken.") long ageSeconds,
            Reading latest) {}

    private final SensorRepository sensors;
    private final TelemetryReadingRepository readings;
    private final SkyfeedProperties properties;
    private final Clock clock;

    SensorController(SensorRepository sensors, TelemetryReadingRepository readings, SkyfeedProperties properties, Clock clock) {
        this.sensors = sensors;
        this.readings = readings;
        this.properties = properties;
        this.clock = clock;
    }

    @GetMapping
    @Operation(summary = "All sensors with their latest reading and status")
    List<SensorView> list() {
        Instant now = clock.instant();
        return sensors.findAllByOrderBySensorIdAsc().stream().map(sensor -> {
            Duration age = Duration.between(sensor.getLastTs(), now);
            return new SensorView(sensor.getSensorId(), sensor.getLat(), sensor.getLon(), status(age), Math.max(0, age.toSeconds()),
                    new Reading(sensor.getLastTs(), sensor.getLastSeq(), sensor.getTemperatureC(), sensor.getSignalDbm(), sensor.getMessageRate()));
        }).toList();
    }

    @GetMapping("/{sensorId}/telemetry")
    @Operation(summary = "The readings of one sensor in a time range, oldest first",
            description = "Without from and to: the last 15 minutes. If the range holds more than limit readings, the newest are returned.")
    List<Reading> telemetry(
            @PathVariable String sensorId,
            @Parameter(description = "Start of the range, RFC 3339. Default: 15 minutes before to.") @RequestParam(required = false) Instant from,
            @Parameter(description = "End of the range, RFC 3339. Default: now.") @RequestParam(required = false) Instant to,
            @Parameter(description = "Most readings to return, 1 to 5000.") @RequestParam(defaultValue = "" + DEFAULT_LIMIT) int limit) {
        Instant end = to != null ? to : clock.instant();
        Instant start = from != null ? from : end.minus(DEFAULT_RANGE);
        if (start.isAfter(end)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "from must not be after to");
        }
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "limit must be between 1 and " + MAX_LIMIT);
        }
        if (!sensors.existsById(sensorId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "no sensor with id " + sensorId);
        }
        return readings.findBySensorIdAndTsBetweenOrderByTsDescSeqDesc(sensorId, start, end, Limit.of(limit))
                .reversed().stream()
                .map(r -> new Reading(r.getTs(), r.getSeq(), r.getTemperatureC(), r.getSignalDbm(), r.getMessageRate()))
                .toList();
    }

    private String status(Duration age) {
        if (age.compareTo(properties.sensor().degradedAfter()) < 0) {
            return "online";
        }
        return age.compareTo(properties.sensor().offlineAfter()) < 0 ? "degraded" : "offline";
    }
}
