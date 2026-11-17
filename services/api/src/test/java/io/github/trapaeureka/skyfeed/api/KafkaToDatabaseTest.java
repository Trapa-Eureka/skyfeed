package io.github.trapaeureka.skyfeed.api;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.trapaeureka.skyfeed.api.aircraft.AircraftPositionRepository;
import io.github.trapaeureka.skyfeed.api.aircraft.AircraftRepository;
import io.github.trapaeureka.skyfeed.api.telemetry.SensorRepository;
import io.github.trapaeureka.skyfeed.api.telemetry.TelemetryReadingRepository;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaTemplate;

class KafkaToDatabaseTest extends ApiTestBase {

    @Autowired
    KafkaTemplate<String, String> kafka;
    @Autowired
    TelemetryReadingRepository readings;
    @Autowired
    SensorRepository sensors;
    @Autowired
    AircraftPositionRepository positions;
    @Autowired
    AircraftRepository aircraft;

    @Test
    @DisplayName("R-API-01 R-API-02 telemetry from Kafka is stored; the same record sent twice is stored once")
    void telemetryFromKafka() throws Exception {
        String id = newSensorId();
        Instant ts = now();
        String first = json.writeValueAsString(reading(id, ts, 1, 36.4));
        String second = json.writeValueAsString(reading(id, ts.plusSeconds(2), 2, 36.6));

        kafka.send("telemetry.v1", id, first);
        kafka.send("telemetry.v1", id, first);
        kafka.send("telemetry.v1", id, second);

        // Same key, so the three records are consumed in order: once the last one is in, the duplicate was processed.
        await("the second reading to be stored", () -> sensors.findById(id).map(s -> s.getLastSeq() == 2).orElse(false));
        assertEquals(2, readings.countBySensorId(id));
    }

    @Test
    @DisplayName("R-API-01 R-API-02 aircraft states from Kafka are stored; the same record sent twice is stored once")
    void aircraftFromKafka() throws Exception {
        String icao24 = newIcao24();
        Instant ts = now();
        String first = json.writeValueAsString(state(icao24, ts, 14.0));
        String second = json.writeValueAsString(state(icao24, ts.plusSeconds(10), 14.1));

        kafka.send("adsb.state.v1", icao24, first);
        kafka.send("adsb.state.v1", icao24, first);
        kafka.send("adsb.state.v1", icao24, second);

        await("the second state to be stored", () -> aircraft.findById(icao24).map(a -> a.getLat() == 14.1).orElse(false));
        assertEquals(2, positions.countByIcao24(icao24));
    }

    @Test
    @DisplayName("R-API-01 a record that is not a complete message is dropped and the records after it are stored")
    void badRecordDoesNotBlock() throws Exception {
        String id = newSensorId();
        kafka.send("telemetry.v1", id, "{not json");
        kafka.send("telemetry.v1", id, "{\"sensorId\":\"" + id + "\"}");
        kafka.send("telemetry.v1", id, json.writeValueAsString(reading(id, now(), 1, 36.4)));

        await("the valid reading to be stored", () -> readings.countBySensorId(id) == 1);
    }
}
