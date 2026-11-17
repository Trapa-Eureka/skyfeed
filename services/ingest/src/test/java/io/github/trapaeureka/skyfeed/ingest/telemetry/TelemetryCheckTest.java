package io.github.trapaeureka.skyfeed.ingest.telemetry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.trapaeureka.skyfeed.ingest.telemetry.TelemetryCheck.Accepted;
import io.github.trapaeureka.skyfeed.ingest.telemetry.TelemetryCheck.Rejected;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class TelemetryCheckTest {

    private static final Path EXAMPLES = Path.of("../../contracts/examples");
    private static final String TOPIC = "sensors/gs-iloilo/telemetry";

    private final TelemetryCheck check = new TelemetryCheck();

    private static byte[] example(String name) throws IOException {
        return Files.readAllBytes(EXAMPLES.resolve(name));
    }

    private Rejected rejected(String topic, String payload) {
        return assertInstanceOf(Rejected.class, check.check(topic, payload.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    @DisplayName("R-ING-01 the contract's valid example is accepted, unchanged, with the sensor id as key")
    void acceptsValidExample() throws IOException {
        byte[] payload = example("telemetry.v1.valid.json");
        Accepted accepted = assertInstanceOf(Accepted.class, check.check(TOPIC, payload));
        assertEquals("gs-iloilo", accepted.sensorId());
        assertEquals(new String(payload, StandardCharsets.UTF_8), accepted.json());
    }

    @Test
    @DisplayName("R-ING-02 the contract's invalid example is rejected with the reason")
    void rejectsInvalidExample() throws IOException {
        Rejected rejected = assertInstanceOf(Rejected.class, check.check(TOPIC, example("telemetry.v1.invalid.json")));
        assertEquals("gs-iloilo", rejected.sensorId());
        assertTrue(rejected.reason().startsWith("does not match telemetry.v1"), rejected.reason());
    }

    @Test
    @DisplayName("R-ING-02 a timestamp that is not a date-time is rejected")
    void rejectsBadTimestamp() throws IOException {
        String payload = new String(example("telemetry.v1.valid.json"), StandardCharsets.UTF_8)
                .replace("2026-11-17T06:00:00.000Z", "yesterday");
        assertTrue(rejected(TOPIC, payload).reason().contains("/ts"), rejected(TOPIC, payload).reason());
    }

    @Test
    @DisplayName("R-ING-02 a sensor may not report under another sensor's topic")
    void rejectsSensorIdMismatch() throws IOException {
        String payload = new String(example("telemetry.v1.valid.json"), StandardCharsets.UTF_8);
        Rejected rejected = rejected("sensors/gs-cebu/telemetry", payload);
        assertEquals("gs-cebu", rejected.sensorId());
        assertTrue(rejected.reason().contains("is not the one in the topic"), rejected.reason());
    }

    @Test
    @DisplayName("R-ING-02 payloads that are not a JSON object are rejected")
    void rejectsNonJson() {
        assertEquals("payload is not JSON", rejected(TOPIC, "{not json").reason());
        assertEquals("payload is not a JSON object", rejected(TOPIC, "[1,2,3]").reason());
        assertEquals("empty payload", rejected(TOPIC, "").reason());
        assertTrue(rejected(TOPIC, "x".repeat(TelemetryCheck.MAX_PAYLOAD_BYTES + 1)).reason().contains("the limit is"));
    }

    @Test
    @DisplayName("R-ING-02 a topic outside sensors/{sensorId}/telemetry is rejected without a key")
    void rejectsUnknownTopic() {
        assertNull(rejected("sensors/Bad_Id/telemetry", "{}").sensorId());
        assertNull(rejected(null, "{}").sensorId());
    }
}
