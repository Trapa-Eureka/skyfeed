package io.github.trapaeureka.skyfeed.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.trapaeureka.skyfeed.api.status.SensorStatusChange;
import io.github.trapaeureka.skyfeed.api.status.SensorStatusChangeRepository;
import io.github.trapaeureka.skyfeed.api.status.SensorStatusService;
import io.github.trapaeureka.skyfeed.api.telemetry.Sensor;
import io.github.trapaeureka.skyfeed.api.telemetry.SensorRepository;
import io.github.trapaeureka.skyfeed.api.telemetry.TelemetryStore;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.kafka.autoconfigure.KafkaConnectionDetails;
import tools.jackson.databind.JsonNode;

class SensorStatusTest extends ApiTestBase {

    @Autowired
    TelemetryStore telemetryStore;
    @Autowired
    SensorRepository sensors;
    @Autowired
    SensorStatusChangeRepository changes;
    @Autowired
    SensorStatusService statusService;
    @Autowired
    KafkaConnectionDetails kafkaConnection;

    private List<String> statusesOf(String sensorId) {
        return changes.findBySensorIdOrderByChangedAtAsc(sensorId).stream().map(SensorStatusChange::getStatus).toList();
    }

    /** Everything published on sensor.status.v1 for one sensor so far, read from the start of the topic. */
    private List<ConsumerRecord<String, String>> publishedFor(String sensorId, int atLeast, Duration wait) throws InterruptedException {
        List<ConsumerRecord<String, String>> found = new ArrayList<>();
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(Map.of(
                "bootstrap.servers", String.join(",", kafkaConnection.getBootstrapServers()),
                "group.id", "test-" + UUID.randomUUID(),
                "auto.offset.reset", "earliest",
                "key.deserializer", StringDeserializer.class.getName(),
                "value.deserializer", StringDeserializer.class.getName()))) {
            consumer.subscribe(List.of("sensor.status.v1"));
            long deadline = System.nanoTime() + wait.toNanos();
            while (found.size() < atLeast && System.nanoTime() < deadline) {
                for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(500))) {
                    if (sensorId.equals(record.key())) {
                        found.add(record);
                    }
                }
            }
        }
        return found;
    }

    @Test
    @DisplayName("R-OPS-01 a sensor that has sent nothing for the configured time is marked offline, and online again with its next reading; each change is published once")
    void offlineAndBackOnline() throws Exception {
        String id = newSensorId();
        Instant lastReading = now().minusSeconds(120);
        telemetryStore.store(reading(id, lastReading, 1, 36.4));

        await("the sensor to be marked offline", () -> statusesOf(id).equals(List.of("offline")));
        assertEquals("offline", sensors.findById(id).orElseThrow().getStatus());

        telemetryStore.store(reading(id, now(), 2, 36.6));
        await("the sensor to be marked online again", () -> statusesOf(id).equals(List.of("offline", "online")));

        // The same changes arrive on Kafka, keyed by sensor, in order, as sensor.status.v1 messages.
        List<ConsumerRecord<String, String>> published = publishedFor(id, 2, Duration.ofSeconds(60));
        assertEquals(2, published.size());
        JsonNode offline = json.readTree(published.get(0).value());
        JsonNode online = json.readTree(published.get(1).value());
        assertEquals(id, offline.get("sensorId").asString());
        assertEquals("offline", offline.get("status").asString());
        assertEquals("online", offline.get("previousStatus").asString());
        assertEquals(lastReading, Instant.parse(offline.get("lastReadingTs").asString()));
        assertNotNull(UUID.fromString(offline.get("eventId").asString()));
        assertFalse(Instant.parse(offline.get("changedAt").asString()).isBefore(lastReading));
        assertEquals("online", online.get("status").asString());
        assertEquals("offline", online.get("previousStatus").asString());
        assertEquals(List.of("changedAt", "eventId", "lastReadingTs", "previousStatus", "sensorId", "status"),
                offline.propertyNames().stream().sorted().toList());

        // The monitor keeps running; a status that did not change is not reported again. (Only a short look:
        // ten seconds after its last reading this sensor really does change again, to degraded.)
        assertEquals(2, publishedFor(id, 3, Duration.ofSeconds(4)).size());
        assertEquals(List.of("offline", "online"), statusesOf(id));
    }

    @Test
    @DisplayName("R-OPS-01 a sensor silent for the shorter time is marked degraded")
    void degraded() throws Exception {
        String id = newSensorId();
        telemetryStore.store(reading(id, now().minusSeconds(15), 1, 36.4));

        await("the sensor to be marked degraded", () -> statusesOf(id).equals(List.of("degraded")));
    }

    @Test
    @DisplayName("R-OPS-01 the recent changes are served by the API, newest first")
    void eventsEndpoint() throws Exception {
        String id = newSensorId();
        telemetryStore.store(reading(id, now().minusSeconds(120), 1, 36.4));
        await("the sensor to be marked offline", () -> statusesOf(id).equals(List.of("offline")));

        JsonNode events = getJson("/api/v1/sensor-events?limit=200");
        JsonNode mine = null;
        Instant previous = null;
        for (JsonNode event : events) {
            Instant changedAt = Instant.parse(event.get("changedAt").asString());
            assertTrue(previous == null || !changedAt.isAfter(previous), "events are not newest first");
            previous = changedAt;
            if (id.equals(event.get("sensorId").asString())) {
                mine = event;
            }
        }
        assertNotNull(mine, "the change is not in the list");
        assertEquals("offline", mine.get("status").asString());
        assertEquals(400, get("/api/v1/sensor-events?limit=0").statusCode());
        assertEquals(400, get("/api/v1/sensor-events?limit=201").statusCode());
    }

    @Test
    @DisplayName("R-OPS-01 two observers of the same change record it once")
    void changeIsRecordedOnce() {
        String id = newSensorId();
        telemetryStore.store(reading(id, now(), 1, 36.4));
        Sensor seen = sensors.findById(id).orElseThrow();
        assertEquals("online", seen.getStatus());

        assertTrue(statusService.recordChange(seen, "degraded", now()));
        assertFalse(statusService.recordChange(seen, "degraded", now()), "the second observer must find the change already made");

        assertEquals(List.of("degraded"), statusesOf(id).subList(0, 1));
    }
}
