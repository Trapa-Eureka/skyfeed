package io.github.trapaeureka.skyfeed.api;

import io.github.trapaeureka.skyfeed.api.aircraft.AdsbStateMessage;
import io.github.trapaeureka.skyfeed.api.telemetry.TelemetryMessage;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.context.annotation.Import;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The whole service on a random port, against PostgreSQL and Kafka in containers. All test classes share one
 * application and one database, so every test uses sensor ids and aircraft addresses of its own.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, properties = {
    // Sensor status is checked often, so the status tests do not wait long.
    "skyfeed.sensor.check-interval=300ms",
    // Spring Boot switches metrics export off in tests; the metrics test needs the Prometheus endpoint.
    "management.prometheus.metrics.export.enabled=true",
})
@Import(TestcontainersConfiguration.class)
abstract class ApiTestBase {

    private static final HttpClient HTTP = HttpClient.newHttpClient();

    @Value("${local.server.port}")
    int port;

    @Autowired
    ObjectMapper json;

    HttpResponse<String> get(String path) throws Exception {
        return HTTP.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    JsonNode getJson(String path) throws Exception {
        HttpResponse<String> response = get(path);
        if (response.statusCode() != 200) {
            throw new AssertionError(path + " answered " + response.statusCode() + ": " + response.body());
        }
        return json.readTree(response.body());
    }

    static String newSensorId() {
        return "gs-" + UUID.randomUUID().toString().substring(0, 12);
    }

    static String newIcao24() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 6);
    }

    /** Now, cut to milliseconds: what PostgreSQL keeps is then exactly what was stored. */
    static Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.MILLIS);
    }

    static TelemetryMessage reading(String sensorId, Instant ts, long seq, double temperatureC) {
        return new TelemetryMessage(sensorId, ts, seq, 10.833, 122.493, temperatureC, -71.2, 184.5);
    }

    static AdsbStateMessage state(String icao24, Instant lastContact, double lat) {
        return new AdsbStateMessage(icao24, "TEST1", "Nowhere", lastContact, lastContact, lat, 121.0, 1000.0, 1100.0,
                false, 200.0, 90.0, 0.0, "1200", "replay");
    }

    static void await(String what, BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(200);
        }
        throw new AssertionError("timed out waiting for: " + what);
    }
}
