package io.github.trapaeureka.skyfeed.ingest;

import com.sun.net.httpserver.HttpServer;
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.testcontainers.containers.GenericContainer;

/**
 * The two inputs of the service for integration tests: a real Mosquitto broker in a container, and a small HTTP
 * server that answers the OpenSky states call with the first frame of the recording.
 */
public class LocalSources implements QuarkusTestResourceLifecycleManager {

    static final Path RECORDING = Path.of("../../tools/simulator/fixtures/opensky-states.jsonl");

    static GenericContainer<?> mosquitto;
    private HttpServer openSky;

    @Override
    public Map<String, String> start() {
        // The image ships this file: one listener on 1883 that accepts anonymous clients.
        mosquitto = new GenericContainer<>("eclipse-mosquitto:2.1.2-alpine")
                .withCommand("mosquitto", "-c", "/mosquitto-no-auth.conf")
                .withExposedPorts(1883);
        mosquitto.start();

        try {
            byte[] frame = Files.readAllLines(RECORDING).get(0).getBytes(StandardCharsets.UTF_8);
            openSky = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            openSky.createContext("/api/states/all", exchange -> {
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, frame.length);
                try (var body = exchange.getResponseBody()) {
                    body.write(frame);
                }
            });
            openSky.start();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        return Map.of(
                "mp.messaging.incoming.telemetry-mqtt.host", mosquitto.getHost(),
                "mp.messaging.incoming.telemetry-mqtt.port", String.valueOf(mosquitto.getMappedPort(1883)),
                "skyfeed.adsb.replay.url", "http://127.0.0.1:" + openSky.getAddress().getPort(),
                "skyfeed.adsb.poll-interval", "1s");
    }

    @Override
    public void stop() {
        if (openSky != null) {
            openSky.stop(0);
        }
        if (mosquitto != null) {
            mosquitto.stop();
        }
    }

    /** Publishes one message with the broker's own command-line client, at QoS 1. */
    static void publish(String topic, String payload) {
        try {
            var result = mosquitto.execInContainer("mosquitto_pub", "-h", "localhost", "-q", "1", "-t", topic, "-m", payload);
            if (result.getExitCode() != 0) {
                throw new IllegalStateException("mosquitto_pub failed: " + result.getStderr());
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
