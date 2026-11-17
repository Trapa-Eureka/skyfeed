package io.github.trapaeureka.skyfeed.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.trapaeureka.skyfeed.api.aircraft.AdsbStateMessage;
import io.github.trapaeureka.skyfeed.api.aircraft.Aircraft;
import io.github.trapaeureka.skyfeed.api.aircraft.AircraftPositionRepository;
import io.github.trapaeureka.skyfeed.api.aircraft.AircraftRepository;
import io.github.trapaeureka.skyfeed.api.aircraft.AircraftStore;
import io.github.trapaeureka.skyfeed.api.telemetry.Sensor;
import io.github.trapaeureka.skyfeed.api.telemetry.SensorRepository;
import io.github.trapaeureka.skyfeed.api.telemetry.TelemetryReadingRepository;
import io.github.trapaeureka.skyfeed.api.telemetry.TelemetryStore;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class StoreTest extends ApiTestBase {

    @Autowired
    TelemetryStore telemetryStore;
    @Autowired
    TelemetryReadingRepository readings;
    @Autowired
    SensorRepository sensors;
    @Autowired
    AircraftStore aircraftStore;
    @Autowired
    AircraftPositionRepository positions;
    @Autowired
    AircraftRepository aircraft;

    @Test
    @DisplayName("R-API-01 a reading is stored and becomes the sensor's latest reading")
    void storesReading() {
        String id = newSensorId();
        Instant ts = now();

        assertTrue(telemetryStore.store(reading(id, ts, 7, 36.4)));

        assertEquals(1, readings.countBySensorId(id));
        Sensor sensor = sensors.findById(id).orElseThrow();
        assertEquals(ts, sensor.getLastTs());
        assertEquals(7, sensor.getLastSeq());
        assertEquals(36.4, sensor.getTemperatureC());
        assertEquals(10.833, sensor.getLat());
    }

    @Test
    @DisplayName("R-API-02 storing the same reading twice keeps one row")
    void sameReadingTwice() {
        String id = newSensorId();
        Instant ts = now();

        assertTrue(telemetryStore.store(reading(id, ts, 1, 36.4)));
        assertFalse(telemetryStore.store(reading(id, ts, 1, 36.4)));

        assertEquals(1, readings.countBySensorId(id));
    }

    @Test
    @DisplayName("R-API-01 a reading that arrives late is stored but does not replace a newer latest reading")
    void lateReadingDoesNotReplaceLatest() {
        String id = newSensorId();
        Instant ts = now();

        telemetryStore.store(reading(id, ts, 2, 40.0));
        telemetryStore.store(reading(id, ts.minusSeconds(2), 1, 30.0));

        assertEquals(2, readings.countBySensorId(id));
        assertEquals(40.0, sensors.findById(id).orElseThrow().getTemperatureC());
    }

    @Test
    @DisplayName("R-API-01 an aircraft state is stored, including values that are unknown")
    void storesAircraftStateWithUnknownValues() {
        String icao24 = newIcao24();
        Instant ts = now();

        assertTrue(aircraftStore.store(new AdsbStateMessage(icao24, null, null, ts, ts, 14.5, 121.0, null, null, true,
                null, null, null, null, "replay")));

        Aircraft stored = aircraft.findById(icao24).orElseThrow();
        assertNull(stored.getCallsign());
        assertNull(stored.getBaroAltitudeM());
        assertNull(stored.getSquawk());
        assertTrue(stored.isOnGround());
        assertEquals(14.5, stored.getLat());
        assertEquals(ts, stored.getLastContact());
        assertEquals(1, positions.countByIcao24(icao24));
    }

    @Test
    @DisplayName("R-API-02 storing the same aircraft state twice keeps one row")
    void sameStateTwice() {
        String icao24 = newIcao24();
        Instant ts = now();

        assertTrue(aircraftStore.store(state(icao24, ts, 14.0)));
        assertFalse(aircraftStore.store(state(icao24, ts, 14.0)));

        assertEquals(1, positions.countByIcao24(icao24));
    }

    @Test
    @DisplayName("R-API-01 an older aircraft state is stored but does not replace the latest state")
    void olderStateDoesNotReplaceLatest() {
        String icao24 = newIcao24();
        Instant ts = now();

        aircraftStore.store(state(icao24, ts, 14.2));
        aircraftStore.store(state(icao24, ts.minusSeconds(10), 14.1));

        assertEquals(2, positions.countByIcao24(icao24));
        assertEquals(14.2, aircraft.findById(icao24).orElseThrow().getLat());
    }
}
