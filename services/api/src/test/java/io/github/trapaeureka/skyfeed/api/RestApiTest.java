package io.github.trapaeureka.skyfeed.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.trapaeureka.skyfeed.api.aircraft.AircraftStore;
import io.github.trapaeureka.skyfeed.api.telemetry.TelemetryStore;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

class RestApiTest extends ApiTestBase {

    @Autowired
    TelemetryStore telemetryStore;
    @Autowired
    AircraftStore aircraftStore;

    private JsonNode find(JsonNode array, String field, String value) {
        for (JsonNode item : array) {
            if (value.equals(item.get(field).asString())) {
                return item;
            }
        }
        throw new AssertionError("no element with " + field + " = " + value);
    }

    @Test
    @DisplayName("R-API-03 sensors are listed with their latest reading and a status from its age")
    void sensorsWithStatus() throws Exception {
        String online = newSensorId();
        String degraded = newSensorId();
        String offline = newSensorId();
        Instant now = now();
        telemetryStore.store(reading(online, now.minusSeconds(4), 1, 30.0));
        telemetryStore.store(reading(online, now.minusSeconds(2), 2, 31.5));
        telemetryStore.store(reading(degraded, now.minusSeconds(18), 1, 32.0));
        telemetryStore.store(reading(offline, now.minusSeconds(300), 1, 33.0));

        JsonNode sensors = getJson("/api/v1/sensors");

        JsonNode first = find(sensors, "sensorId", online);
        assertEquals("online", first.get("status").asString());
        assertEquals(2, first.get("latest").get("seq").asLong());
        assertEquals(31.5, first.get("latest").get("temperatureC").asDouble());
        assertEquals(now.minusSeconds(2), Instant.parse(first.get("latest").get("ts").asString()));
        assertEquals(10.833, first.get("lat").asDouble());
        assertEquals("degraded", find(sensors, "sensorId", degraded).get("status").asString());
        JsonNode last = find(sensors, "sensorId", offline);
        assertEquals("offline", last.get("status").asString());
        assertTrue(last.get("ageSeconds").asLong() >= 300);
    }

    @Test
    @DisplayName("R-API-03 the telemetry series of a sensor covers the time range, oldest first")
    void telemetrySeries() throws Exception {
        String id = newSensorId();
        Instant base = now().minusSeconds(600);
        for (int i = 0; i < 10; i++) {
            telemetryStore.store(reading(id, base.plusSeconds(i * 10L), i, 30.0 + i));
        }

        JsonNode all = getJson("/api/v1/sensors/" + id + "/telemetry");
        assertEquals(10, all.size());
        assertEquals(0, all.get(0).get("seq").asLong());
        assertEquals(9, all.get(9).get("seq").asLong());

        JsonNode ranged = getJson("/api/v1/sensors/" + id + "/telemetry?from=" + base.plusSeconds(20) + "&to=" + base.plusSeconds(50));
        List<Long> seqs = new ArrayList<>();
        ranged.forEach(reading -> seqs.add(reading.get("seq").asLong()));
        assertEquals(List.of(2L, 3L, 4L, 5L), seqs);

        JsonNode newest = getJson("/api/v1/sensors/" + id + "/telemetry?limit=3");
        assertEquals(3, newest.size());
        assertEquals(7, newest.get(0).get("seq").asLong());
        assertEquals(39.0, newest.get(2).get("temperatureC").asDouble());
    }

    @Test
    @DisplayName("R-API-03 telemetry requests that cannot be answered get 404 or 400 as a problem document")
    void telemetryErrors() throws Exception {
        String id = newSensorId();
        telemetryStore.store(reading(id, now(), 1, 30.0));

        assertEquals(404, get("/api/v1/sensors/" + newSensorId() + "/telemetry").statusCode());
        assertEquals(400, get("/api/v1/sensors/" + id + "/telemetry?limit=0").statusCode());
        assertEquals(400, get("/api/v1/sensors/" + id + "/telemetry?limit=5001").statusCode());
        assertEquals(400, get("/api/v1/sensors/" + id + "/telemetry?from=yesterday").statusCode());

        var backwards = get("/api/v1/sensors/" + id + "/telemetry?from=2026-11-17T10:00:00Z&to=2026-11-17T09:00:00Z");
        assertEquals(400, backwards.statusCode());
        assertTrue(backwards.headers().firstValue("content-type").orElse("").startsWith("application/problem+json"));
        assertEquals("from must not be after to", json.readTree(backwards.body()).get("detail").asString());
    }

    @Test
    @DisplayName("R-API-03 current aircraft are those with a recent last contact")
    void currentAircraft() throws Exception {
        String current = newIcao24();
        String gone = newIcao24();
        Instant now = now();
        aircraftStore.store(state(current, now.minusSeconds(5), 14.3));
        aircraftStore.store(state(gone, now.minusSeconds(600), 14.4));

        JsonNode aircraft = getJson("/api/v1/aircraft");

        JsonNode found = find(aircraft, "icao24", current);
        assertEquals(14.3, found.get("lat").asDouble());
        assertEquals("TEST1", found.get("callsign").asString());
        assertEquals(90.0, found.get("headingDeg").asDouble());
        assertEquals("replay", found.get("source").asString());
        assertTrue(found.get("ageSeconds").asLong() >= 5);
        for (JsonNode item : aircraft) {
            assertFalse(gone.equals(item.get("icao24").asString()), "an aircraft last seen 10 minutes ago is listed as current");
        }
    }

    @Test
    @DisplayName("R-API-03 the track of an aircraft is its recent positions, oldest first")
    void aircraftTrack() throws Exception {
        String icao24 = newIcao24();
        Instant now = now();
        aircraftStore.store(state(icao24, now.minusSeconds(3600), 13.0)); // outside the default 30 minutes
        aircraftStore.store(state(icao24, now.minusSeconds(20), 14.2));
        aircraftStore.store(state(icao24, now.minusSeconds(30), 14.1));
        aircraftStore.store(state(icao24, now.minusSeconds(10), 14.3));

        JsonNode track = getJson("/api/v1/aircraft/" + icao24 + "/track");
        List<Double> lats = new ArrayList<>();
        track.forEach(point -> lats.add(point.get("lat").asDouble()));
        assertEquals(List.of(14.1, 14.2, 14.3), lats);

        assertEquals(4, getJson("/api/v1/aircraft/" + icao24 + "/track?minutes=120").size());
    }

    @Test
    @DisplayName("R-API-03 track requests that cannot be answered get 404 or 400")
    void trackErrors() throws Exception {
        String icao24 = newIcao24();
        aircraftStore.store(state(icao24, now(), 14.0));

        assertEquals(400, get("/api/v1/aircraft/NOTHEX/track").statusCode());
        assertEquals(400, get("/api/v1/aircraft/" + icao24 + "/track?minutes=0").statusCode());
        assertEquals(400, get("/api/v1/aircraft/" + icao24 + "/track?minutes=1441").statusCode());
        String unknown = icao24.equals("000000") ? "000001" : "000000";
        assertEquals(404, get("/api/v1/aircraft/" + unknown + "/track").statusCode());
    }
}
