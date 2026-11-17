package io.github.trapaeureka.skyfeed.api.telemetry;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** One stored reading. Rows are written by {@link TelemetryReadingRepository#insertIfAbsent}; the entity is read-only. */
@Entity
@Table(name = "telemetry_reading")
public class TelemetryReading {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String sensorId;
    private Instant ts;
    private long seq;
    // Named by hand: the default naming would not put an underscore before a single trailing capital.
    @Column(name = "temperature_c")
    private double temperatureC;
    private double signalDbm;
    private double messageRate;
    private Instant receivedAt;

    protected TelemetryReading() {}

    public Long getId() {
        return id;
    }

    public String getSensorId() {
        return sensorId;
    }

    public Instant getTs() {
        return ts;
    }

    public long getSeq() {
        return seq;
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

    public Instant getReceivedAt() {
        return receivedAt;
    }
}
