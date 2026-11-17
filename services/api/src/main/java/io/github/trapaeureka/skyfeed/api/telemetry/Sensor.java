package io.github.trapaeureka.skyfeed.api.telemetry;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** A sensor with its latest reading. Rows are written by {@link SensorRepository#upsertLatest}; the entity is read-only. */
@Entity
@Table(name = "sensor")
public class Sensor {

    @Id
    private String sensorId;
    private double lat;
    private double lon;
    private Instant lastTs;
    private long lastSeq;
    // Named by hand: the default naming would not put an underscore before a single trailing capital.
    @Column(name = "temperature_c")
    private double temperatureC;
    private double signalDbm;
    private double messageRate;
    private Instant updatedAt;
    private String status;
    private Instant statusSince;

    protected Sensor() {}

    public String getSensorId() {
        return sensorId;
    }

    public double getLat() {
        return lat;
    }

    public double getLon() {
        return lon;
    }

    public Instant getLastTs() {
        return lastTs;
    }

    public long getLastSeq() {
        return lastSeq;
    }

    public double getTemperatureC() {
        return temperatureC;
    }

    public double getSignalDbm() {
        return signalDbm;
    }

    public double getMessageRate() {
        return messageRate;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    /** The status the sensor was last found in by the status monitor: online, degraded or offline. */
    public String getStatus() {
        return status;
    }

    public Instant getStatusSince() {
        return statusSince;
    }
}
