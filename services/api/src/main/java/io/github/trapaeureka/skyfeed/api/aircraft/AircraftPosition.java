package io.github.trapaeureka.skyfeed.api.aircraft;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** One stored state of an aircraft. Rows are written by {@link AircraftPositionRepository#insertIfAbsent}; the entity is read-only. */
@Entity
@Table(name = "aircraft_position")
public class AircraftPosition {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String icao24;
    private Instant ts;
    private Instant lastContact;
    private double lat;
    private double lon;
    // Named by hand: the default naming would not put an underscore before a single trailing capital.
    @Column(name = "baro_altitude_m")
    private Double baroAltitudeM;
    // Named by hand: the default naming would not put an underscore before a single trailing capital.
    @Column(name = "geo_altitude_m")
    private Double geoAltitudeM;
    private boolean onGround;
    private Double velocityMs;
    private Double headingDeg;
    private Double verticalRateMs;
    private Instant receivedAt;

    protected AircraftPosition() {}

    public Long getId() {
        return id;
    }

    public String getIcao24() {
        return icao24;
    }

    public Instant getTs() {
        return ts;
    }

    public Instant getLastContact() {
        return lastContact;
    }

    public double getLat() {
        return lat;
    }

    public double getLon() {
        return lon;
    }

    public Double getBaroAltitudeM() {
        return baroAltitudeM;
    }

    public Double getGeoAltitudeM() {
        return geoAltitudeM;
    }

    public boolean isOnGround() {
        return onGround;
    }

    public Double getVelocityMs() {
        return velocityMs;
    }

    public Double getHeadingDeg() {
        return headingDeg;
    }

    public Double getVerticalRateMs() {
        return verticalRateMs;
    }

    public Instant getReceivedAt() {
        return receivedAt;
    }
}
