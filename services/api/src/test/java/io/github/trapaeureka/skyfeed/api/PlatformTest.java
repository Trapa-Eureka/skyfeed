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

    @Test
    @DisplayName("R-API-04 the service serves an OpenAPI document that describes its four endpoints")
    void openApiDocument() throws Exception {
        JsonNode document = getJson("/v3/api-docs");

        assertTrue(document.get("openapi").asString().startsWith("3."));
        assertEquals("SkyFeed API", document.get("info").get("title").asString());
        Set<String> paths = new TreeSet<>(document.get("paths").propertyNames());
        assertEquals(Set.of("/api/v1/sensors", "/api/v1/sensors/{sensorId}/telemetry", "/api/v1/aircraft",
                "/api/v1/aircraft/{icao24}/track"), paths);
        for (String path : paths) {
            assertTrue(document.get("paths").get(path).has("get"), path + " has no GET");
        }
    }

    @Test
    @DisplayName("R-API-05 the tables come from versioned migrations and Hibernate only validates them")
    void schemaComesFromMigrations() {
        List<Map<String, Object>> applied = jdbc.queryForList(
                "select version, description, success from flyway_schema_history where version is not null order by installed_rank");
        assertEquals(1, applied.size());
        assertEquals("1", applied.get(0).get("version"));
        assertEquals(true, applied.get(0).get("success"));

        // Hibernate is not allowed to create or alter anything. With "validate" the application only starts
        // (and so this test only runs) when every entity fits the tables the migrations made.
        assertEquals("validate", environment.getProperty("spring.jpa.hibernate.ddl-auto"));

        List<String> tables = jdbc.queryForList(
                "select table_name from information_schema.tables where table_schema = 'public' order by table_name", String.class);
        assertEquals(List.of("aircraft", "aircraft_position", "flyway_schema_history", "sensor", "telemetry_reading"), tables);
    }

    @Test
    @DisplayName("the health endpoint reports UP")
    void health() throws Exception {
        assertEquals("UP", getJson("/actuator/health").get("status").asString());
    }
}
