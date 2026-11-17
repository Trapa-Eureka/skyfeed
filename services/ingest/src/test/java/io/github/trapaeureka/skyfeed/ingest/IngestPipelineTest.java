package io.github.trapaeureka.skyfeed.ingest;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasItem;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.trapaeureka.skyfeed.ingest.contract.ContractSchemas;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.eclipse.microprofile.config.ConfigProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The service running against a real MQTT broker and a real Kafka broker, both in containers. */
@QuarkusTest
@QuarkusTestResource(LocalSources.class)
class IngestPipelineTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Duration TIMEOUT = Duration.ofSeconds(60);

    private static KafkaConsumer<String, String> consumer(String topic) {
        KafkaConsumer<String, String> consumer = new KafkaConsumer<>(Map.of(
                "bootstrap.servers", ConfigProvider.getConfig().getValue("kafka.bootstrap.servers", String.class),
                "group.id", "test-" + UUID.randomUUID(),
                "auto.offset.reset", "earliest",
                "key.deserializer", StringDeserializer.class.getName(),
                "value.deserializer", StringDeserializer.class.getName()));
        consumer.subscribe(List.of(topic));
        return consumer;
    }

    /** Reads the topic until a record matches. {@code retry} runs before every read; it re-sends the input. */
    private static ConsumerRecord<String, String> await(String topic, Predicate<ConsumerRecord<String, String>> match, Runnable retry) {
        long deadline = System.nanoTime() + TIMEOUT.toNanos();
        try (KafkaConsumer<String, String> consumer = consumer(topic)) {
            while (System.nanoTime() < deadline) {
                retry.run();
                for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofSeconds(1))) {
                    if (match.test(record)) {
                        return record;
                    }
                }
            }
        }
        throw new AssertionError("no matching record on " + topic + " within " + TIMEOUT);
    }

    private static String reading(String sensorId, int seq) {
        return "{\"sensorId\":\"" + sensorId + "\",\"ts\":\"2026-11-17T06:00:00.000Z\",\"seq\":" + seq
                + ",\"lat\":10.833,\"lon\":122.493,\"temperatureC\":36.4,\"signalDbm\":-71.2,\"messageRate\":184.5}";
    }

    @Test
    @DisplayName("R-ING-01 R-OPS-04 a reading published on MQTT arrives on telemetry.v1, unchanged, keyed by sensor id, and is counted")
    void forwardsTelemetry() {
        // The broker does not keep messages for a subscriber that is not connected yet, so publish until one arrives.
        int[] seq = {0};
        Set<String> sent = new HashSet<>();
        ConsumerRecord<String, String> record = await("telemetry.v1", r -> sent.contains(r.value()), () -> {
            String payload = reading("gs-test-a", seq[0]++);
            sent.add(payload);
            LocalSources.publish("sensors/gs-test-a/telemetry", payload);
        });

        assertEquals("gs-test-a", record.key());
        given().when().get("/q/health/ready").then().statusCode(200)
                .body("checks.find { it.name == 'telemetry-bridge' }.data.forwarded", greaterThan(0));

        // R-OPS-04: the same counts, and the age of the last message from each source, as metrics.
        String metrics = given().when().get("/q/metrics").then().statusCode(200).extract().asString();
        for (String wanted : List.of("skyfeed_telemetry_messages_total{result=\"forwarded\"}",
                "skyfeed_source_last_message_age_seconds{source=\"mqtt\"}",
                "skyfeed_source_last_message_age_seconds{source=\"adsb\"}",
                "skyfeed_adsb_polling", "kafka_producer_record_send_total")) {
            assertTrue(metrics.contains(wanted), "missing from /q/metrics: " + wanted);
        }
    }

    @Test
    @DisplayName("R-ING-02 an invalid reading goes to telemetry.dlq.v1 with the reason, and not to telemetry.v1")
    void deadLettersInvalidTelemetry() {
        String marker = "bad-" + UUID.randomUUID();
        String invalid = "{\"sensorId\":\"gs-test-b\",\"note\":\"" + marker + "\"}";

        ConsumerRecord<String, String> dead = await("telemetry.dlq.v1", r -> r.value().contains(marker),
                () -> LocalSources.publish("sensors/gs-test-b/telemetry", invalid));

        assertEquals("gs-test-b", dead.key());
        assertEquals(invalid, dead.value());
        String reason = new String(dead.headers().lastHeader("reason").value(), StandardCharsets.UTF_8);
        assertTrue(reason.startsWith("does not match telemetry.v1"), reason);
        assertEquals("sensors/gs-test-b/telemetry",
                new String(dead.headers().lastHeader("mqtt-topic").value(), StandardCharsets.UTF_8));

        // A valid reading sent afterwards arrives; the invalid one must not be anywhere before it.
        List<String> forwarded = new ArrayList<>();
        String valid = reading("gs-test-b", 1);
        await("telemetry.v1", r -> {
            forwarded.add(r.value());
            return r.value().equals(valid);
        }, () -> LocalSources.publish("sensors/gs-test-b/telemetry", valid));
        assertTrue(forwarded.stream().noneMatch(value -> value.contains(marker)), "the invalid reading was forwarded");
    }

    @Test
    @DisplayName("R-ING-03 R-ING-04 aircraft from the replay source arrive on adsb.state.v1, valid and keyed by ICAO address")
    void publishesAircraftStates() throws Exception {
        JsonNode firstState = JSON.readTree(java.nio.file.Files.readAllLines(LocalSources.RECORDING).get(0)).get("states").get(0);
        String icao24 = firstState.get(0).asText();

        ConsumerRecord<String, String> record = await("adsb.state.v1", r -> icao24.equals(r.key()), () -> {});

        JsonNode message = JSON.readTree(record.value());
        assertEquals(List.of(), ContractSchemas.ADSB_STATE_V1.errors(message));
        assertEquals(icao24, message.get("icao24").asText());
        assertEquals("replay", message.get("source").asText());
        assertEquals(firstState.get(6).asDouble(), message.get("lat").asDouble());
        assertEquals(firstState.get(5).asDouble(), message.get("lon").asDouble());

        given().when().get("/q/health/ready").then().statusCode(200)
                .body("checks.name", hasItem("adsb-source"))
                .body("checks.find { it.name == 'adsb-source' }.data.source", equalTo("replay"))
                .body("checks.find { it.name == 'adsb-source' }.data.status", equalTo("POLLING"));
    }
}
