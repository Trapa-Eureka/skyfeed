package io.github.trapaeureka.skyfeed.api.status;

import java.time.Instant;
import java.util.UUID;

/** A sensor.status.v1 message, and one entry of GET /api/v1/sensor-events. Field names are the JSON names of the contract. */
public record SensorStatusEvent(UUID eventId, String sensorId, String status, String previousStatus, Instant changedAt, Instant lastReadingTs) {

    static SensorStatusEvent of(SensorStatusChange change) {
        return new SensorStatusEvent(change.getId(), change.getSensorId(), change.getStatus(), change.getPreviousStatus(),
                change.getChangedAt(), change.getLastReadingTs());
    }
}
