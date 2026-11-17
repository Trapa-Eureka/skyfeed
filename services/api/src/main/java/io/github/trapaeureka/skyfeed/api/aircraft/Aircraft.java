package io.github.trapaeureka.skyfeed.api.aircraft;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** An aircraft with its latest state. Rows are written by {@link AircraftRepository#upsertLatest}; the entity is read-only. */
@Entity
@Table(name = "aircraft")
public class Aircraft {

    @Id
    private String icao24;
    private String callsign;
    private String originCountry;
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
    private String squawk;
    private String source;
    private Instant updatedAt;

    protected Aircraft() {}

    public String getIcao24() {
        return icao24;
    }

    public String getCallsign() {
        return callsign;
    }

    public String getOriginCountry() {
        return originCountry;
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

    public String getSquawk() {
        return squawk;
    }

    public String getSource() {
        return source;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
