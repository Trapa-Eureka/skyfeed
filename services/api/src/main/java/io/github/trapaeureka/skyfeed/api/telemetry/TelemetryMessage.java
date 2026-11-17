package io.github.trapaeureka.skyfeed.api.telemetry;

import java.time.Instant;

/** A telemetry.v1 message as read from Kafka. Boxed types, so a missing field is null and can be told from zero. */
public record TelemetryMessage(String sensorId, Instant ts, Long seq, Double lat, Double lon,
        Double temperatureC, Double signalDbm, Double messageRate) {

    public boolean isComplete() {
        return sensorId != null && !sensorId.isBlank() && ts != null && seq != null && lat != null && lon != null
                && temperatureC != null && signalDbm != null && messageRate != null;
    }
}
