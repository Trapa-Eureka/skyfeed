package io.github.trapaeureka.skyfeed.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;

class PlatformTest extends ApiTestBase {

    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    Environment environment;
    @Autowired
    org.springframework.kafka.core.KafkaTemplate<String, String> kafka;

    @Test
    @DisplayName("R-API-04 the service serves an OpenAPI document that describes its five endpoints")
    void openApiDocument() throws Exception {
        JsonNode document = getJson("/v3/api-docs");

        assertTrue(document.get("openapi").asString().startsWith("3."));
        assertEquals("SkyFeed API", document.get("info").get("title").asString());
        Set<String> paths = new TreeSet<>(document.get("paths").propertyNames());
        assertEquals(Set.of("/api/v1/sensors", "/api/v1/sensors/{sensorId}/telemetry", "/api/v1/sensor-events",
                "/api/v1/aircraft", "/api/v1/aircraft/{icao24}/track"), paths);
        for (String path : paths) {
            assertTrue(document.get("paths").get(path).has("get"), path + " has no GET");
        }
    }

    @Test
    @DisplayName("R-API-05 the tables come from versioned migrations and Hibernate only validates them")
    void schemaComesFromMigrations() {
        List<Map<String, Object>> applied = jdbc.queryForList(
                "select version, description, success from flyway_schema_history where version is not null order by installed_rank");
        assertEquals(List.of("1", "2"), applied.stream().map(row -> row.get("version")).toList());
        assertTrue(applied.stream().allMatch(row -> Boolean.TRUE.equals(row.get("success"))));

        // Hibernate is not allowed to create or alter anything. With "validate" the application only starts
        // (and so this test only runs) when every entity fits the tables the migrations made.
        assertEquals("validate", environment.getProperty("spring.jpa.hibernate.ddl-auto"));

        List<String> tables = jdbc.queryForList(
                "select table_name from information_schema.tables where table_schema = 'public' order by table_name", String.class);
        assertEquals(List.of("aircraft", "aircraft_position", "flyway_schema_history", "outbox_event", "sensor",
                "sensor_status_change", "telemetry_reading"), tables);
    }

    @Test
    @DisplayName("R-OPS-04 the health endpoint reports UP and names the database as one of its parts")
    void health() throws Exception {
        JsonNode health = getJson("/actuator/health");
        assertEquals("UP", health.get("status").asString());
        assertEquals("UP", health.get("components").get("db").get("status").asString());
    }

    @Test
    @DisplayName("R-OPS-04 the metrics endpoint offers consumer lag, the age of the last record per topic and the outbox backlog")
    void metrics() throws Exception {
        kafka.send("telemetry.v1", "gs-metrics", "{}").get();
        // The consumer reports its lag only after it has fetched, so wait for the metrics to appear.
        String[] wanted = {
            "kafka_consumer_fetch_manager_records_lag",
            "skyfeed_kafka_last_record_age_seconds{topic=\"telemetry.v1\"}",
            "skyfeed_outbox_waiting",
            "skyfeed_telemetry_records_total",
        };
        String[] metrics = {""};
        try {
            await("all metrics to be offered", () -> {
                try {
                    metrics[0] = get("/actuator/prometheus").body();
                } catch (Exception e) {
                    return false;
                }
                return java.util.Arrays.stream(wanted).allMatch(metrics[0]::contains);
            });
        } catch (AssertionError e) {
            throw new AssertionError("missing from /actuator/prometheus: "
                    + java.util.Arrays.stream(wanted).filter(name -> !metrics[0].contains(name)).toList()
                    + "; the answer starts with: " + metrics[0].substring(0, Math.min(300, metrics[0].length())));
        }
    }
}
