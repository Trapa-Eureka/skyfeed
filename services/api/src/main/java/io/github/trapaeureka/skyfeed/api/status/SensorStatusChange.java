package io.github.trapaeureka.skyfeed.api.status;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** One change of a sensor's status. Also the payload of a sensor.status.v1 event (see {@link SensorStatusEvent}). */
@Entity
@Table(name = "sensor_status_change")
public class SensorStatusChange {

    @Id
    private UUID id;
    private String sensorId;
    private String status;
    private String previousStatus;
    private Instant changedAt;
    private Instant lastReadingTs;

    protected SensorStatusChange() {}

    SensorStatusChange(UUID id, String sensorId, String status, String previousStatus, Instant changedAt, Instant lastReadingTs) {
        this.id = id;
        this.sensorId = sensorId;
        this.status = status;
        this.previousStatus = previousStatus;
        this.changedAt = changedAt;
        this.lastReadingTs = lastReadingTs;
    }

    public UUID getId() {
        return id;
    }

    public String getSensorId() {
        return sensorId;
    }

    public String getStatus() {
        return status;
    }

    public String getPreviousStatus() {
        return previousStatus;
    }

    public Instant getChangedAt() {
        return changedAt;
    }

    public Instant getLastReadingTs() {
        return lastReadingTs;
    }
}
