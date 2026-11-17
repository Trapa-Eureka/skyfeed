package io.github.trapaeureka.skyfeed.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.fasterxml.jackson.databind.JsonNode;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.eclipse.microprofile.config.ConfigProvider;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The gateway with real WebSocket connections and a real Kafka broker in a container. */
@QuarkusTest
class LiveEndpointTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(60);
    private static final String MANILA = "{\"lamin\":14,\"lomin\":120,\"lamax\":15,\"lomax\":122}";
    private static final String CEBU = "{\"lamin\":10,\"lomin\":123,\"lamax\":11,\"lomax\":125}";

    @TestHTTPResource("/ws/v1")
    URI endpoint;

    static KafkaProducer<String, String> kafka;

    @BeforeAll
    static void startProducer() {
        kafka = new KafkaProducer<>(Map.of(
                "bootstrap.servers", ConfigProvider.getConfig().getValue("kafka.bootstrap.servers", String.class),
                "key.serializer", StringSerializer.class.getName(),
                "value.serializer", StringSerializer.class.getName()));
    }

    @AfterAll
    static void stopProducer() {
        kafka.close();
    }

    /** A WebSocket client that keeps everything it receives, in order. */
    final class Client implements WebSocket.Listener, AutoCloseable {
        final BlockingQueue<JsonNode> inbox = new LinkedBlockingQueue<>();
        final List<JsonNode> seen = new ArrayList<>();
        private final StringBuilder partial = new StringBuilder();
        private final WebSocket socket;

        Client() throws Exception {
            URI ws = URI.create(endpoint.toString().replaceFirst("^http", "ws"));
            socket = HttpClient.newHttpClient().newWebSocketBuilder().buildAsync(ws, this).get(30, TimeUnit.SECONDS);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            partial.append(data);
            if (last) {
                try {
                    String text = partial.toString();
                    assertEquals(List.of(), MessagesTest.errors("ws.server.v1.schema.json", text), text);
                    inbox.add(Messages.JSON.readTree(text));
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
                partial.setLength(0);
            }
            webSocket.request(1);
            return null;
        }

        void send(String text) throws Exception {
            socket.sendText(text, true).get(10, TimeUnit.SECONDS);
        }

        /** Waits for a message that matches; everything received on the way is kept in {@code seen}. */
        JsonNode await(String what, Predicate<JsonNode> match) throws InterruptedException {
            return await(what, match, () -> {});
        }

        /** As above, running {@code retry} about once a second while waiting (to send the input again). */
        JsonNode await(String what, Predicate<JsonNode> match, Runnable retry) throws InterruptedException {
            long deadline = System.nanoTime() + TIMEOUT.toNanos();
            while (System.nanoTime() < deadline) {
                retry.run();
                long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
                while (System.nanoTime() < until) {
                    JsonNode message = inbox.poll(100, TimeUnit.MILLISECONDS);
                    if (message != null) {
                        seen.add(message);
                        if (match.test(message)) {
                            return message;
                        }
                    }
                }
            }
            return fail("timed out waiting for " + what + "; received " + seen);
        }

        JsonNode awaitType(String type) throws InterruptedException {
            return await("a " + type + " message", message -> type.equals(message.path("type").asText()));
        }

        @Override
        public void close() {
            socket.abort();
        }
    }

    private static boolean isAircraft(JsonNode message, String icao24) {
        return "aircraft".equals(message.path("type").asText()) && icao24.equals(message.path("state").path("icao24").asText());
    }

    private static String newIcao24() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 6);
    }

    /** Publishes one aircraft state. Records with the same key go to one partition, so they are consumed in the order sent. */
    private static void publishState(String key, String icao24, double lat, double lon, int n) {
        String time = Instant.parse("2026-11-17T06:00:00Z").plusSeconds(n).toString();
        kafka.send(new ProducerRecord<>("adsb.state.v1", key, "{\"icao24\":\"" + icao24 + "\",\"callsign\":\"TEST1\",\"originCountry\":null,"
                + "\"ts\":\"" + time + "\",\"lastContact\":\"" + time + "\",\"lat\":" + lat + ",\"lon\":" + lon + ","
                + "\"baroAltitudeM\":1000.0,\"geoAltitudeM\":null,\"onGround\":false,\"velocityMs\":200.0,\"headingDeg\":90.0,"
                + "\"verticalRateMs\":0.0,\"squawk\":null,\"source\":\"replay\"}"));
    }

    private static void publishReading(String sensorId, int seq) {
        kafka.send(new ProducerRecord<>("telemetry.v1", sensorId, "{\"sensorId\":\"" + sensorId + "\",\"ts\":\"2026-11-17T06:00:00.000Z\","
                + "\"seq\":" + seq + ",\"lat\":10.833,\"lon\":122.493,\"temperatureC\":36.4,\"signalDbm\":-71.2,\"messageRate\":184.5}"));
    }

    @Test
    @DisplayName("R-RT-04 a client gets a hello and then heartbeats at the announced interval")
    void heartbeat() throws Exception {
        try (Client client = new Client()) {
            JsonNode hello = client.awaitType("hello");
            double interval = hello.get("heartbeatSeconds").asDouble();
            assertTrue(interval > 0);

            client.awaitType("heartbeat");
            long start = System.nanoTime();
            JsonNode second = client.awaitType("heartbeat");
            double seconds = (System.nanoTime() - start) / 1e9;

            assertTrue(seconds < interval * 3, "second heartbeat came after " + seconds + " s, interval is " + interval + " s");
            assertTrue(Duration.between(Instant.parse(second.get("ts").asText()), Instant.now()).abs().toSeconds() < 5);
        }
    }

    @Test
    @DisplayName("R-RT-01 a client receives the aircraft inside its area, and none outside it")
    void aircraftByArea() throws Exception {
        String inManila = newIcao24();
        String inCebu = newIcao24();
        String key = "order-" + UUID.randomUUID(); // one key, so the gateway reads these records in the order sent

        try (Client manila = new Client(); Client cebu = new Client()) {
            manila.send("{\"type\":\"subscribe\",\"area\":" + MANILA + "}");
            cebu.send("{\"type\":\"subscribe\",\"area\":" + CEBU + "}");
            JsonNode subscribed = manila.awaitType("subscribed");
            assertEquals(14.0, subscribed.get("area").get("lamin").asDouble());
            assertEquals(false, subscribed.get("sensors").asBoolean());
            cebu.awaitType("subscribed");

            // The gateway reads from the newest record on, so publish until its consumer is in place and one arrives.
            int[] n = {0};
            Runnable publishBoth = () -> {
                publishState(key, inCebu, 10.3, 123.9, n[0]);
                publishState(key, inManila, 14.5, 121.0, n[0]);
                publishState(key, inCebu, 10.3, 123.9, n[0]++);
            };
            JsonNode received = manila.await("the aircraft over Manila", m -> isAircraft(m, inManila), publishBoth);
            assertEquals(14.5, received.get("state").get("lat").asDouble());
            assertEquals(List.of(), MessagesTest.errors("adsb.state.v1.schema.json", received.get("state").toString()));
            cebu.await("the aircraft over Cebu", m -> isAircraft(m, inCebu), publishBoth);

            // When the Manila client got its aircraft, the gateway had already handled the Cebu record sent before it.
            assertTrue(manila.seen.stream().noneMatch(m -> isAircraft(m, inCebu)), "the Manila client received an aircraft over Cebu");
            assertTrue(cebu.seen.stream().noneMatch(m -> isAircraft(m, inManila)), "the Cebu client received an aircraft over Manila");
            assertTrue(manila.seen.stream().noneMatch(m -> "telemetry".equals(m.path("type").asText())));
        }
    }

    @Test
    @DisplayName("R-RT-01 a new subscribe message replaces the area")
    void resubscribe() throws Exception {
        String inManila = newIcao24();
        String inCebu = newIcao24();
        String key = "order-" + UUID.randomUUID();
        int[] n = {0};
        Runnable publishBoth = () -> {
            publishState(key, inManila, 14.5, 121.0, n[0]);
            publishState(key, inCebu, 10.3, 123.9, n[0]++);
        };

        try (Client client = new Client()) {
            client.send("{\"type\":\"subscribe\",\"area\":" + MANILA + "}");
            client.awaitType("subscribed");
            client.await("the aircraft over Manila", m -> isAircraft(m, inManila), publishBoth);

            client.send("{\"type\":\"subscribe\",\"area\":" + CEBU + "}");
            client.awaitType("subscribed");
            client.seen.clear();
            client.await("the aircraft over Cebu", m -> isAircraft(m, inCebu), publishBoth);

            // In each round the Manila record is sent first, so it was handled, and dropped, before the Cebu one arrived.
            assertTrue(client.seen.stream().noneMatch(m -> isAircraft(m, inManila)),
                    "an aircraft outside the new area was sent after the area changed");
        }
    }

    @Test
    @DisplayName("R-RT-02 a client that subscribed to sensors receives their readings; one that did not receives none")
    void telemetry() throws Exception {
        String sensorId = "gs-" + UUID.randomUUID().toString().substring(0, 8);
        String overManila = newIcao24();

        try (Client withSensors = new Client(); Client without = new Client()) {
            withSensors.send("{\"type\":\"subscribe\",\"sensors\":true}");
            without.send("{\"type\":\"subscribe\",\"area\":" + MANILA + ",\"sensors\":false}");
            assertEquals(true, withSensors.awaitType("subscribed").get("sensors").asBoolean());
            assertTrue(withSensors.seen.get(withSensors.seen.size() - 1).get("area").isNull());
            without.awaitType("subscribed");

            int[] seq = {0};
            JsonNode message = withSensors.await("a reading of " + sensorId,
                    m -> "telemetry".equals(m.path("type").asText()) && sensorId.equals(m.path("reading").path("sensorId").asText()),
                    () -> publishReading(sensorId, seq[0]++));
            assertEquals(List.of(), MessagesTest.errors("telemetry.v1.schema.json", message.get("reading").toString()));
            assertEquals(36.4, message.get("reading").get("temperatureC").asDouble());

            // Give the other client something it does want, sent after the readings, then check no reading reached it.
            int[] n = {0};
            without.await("the aircraft over Manila", m -> isAircraft(m, overManila), () -> {
                publishReading(sensorId, seq[0]++);
                publishState(overManila, overManila, 14.5, 121.0, n[0]++);
            });
            assertTrue(without.seen.stream().noneMatch(m -> "telemetry".equals(m.path("type").asText())),
                    "a client that did not subscribe to sensors received a reading");
        }
    }

    @Test
    @DisplayName("R-RT-02 a client that subscribed to sensors receives changes of sensor status")
    void sensorStatus() throws Exception {
        String sensorId = "gs-" + UUID.randomUUID().toString().substring(0, 8);
        String event = "{\"eventId\":\"" + UUID.randomUUID() + "\",\"sensorId\":\"" + sensorId + "\",\"status\":\"offline\","
                + "\"previousStatus\":\"degraded\",\"changedAt\":\"2026-11-17T06:00:30Z\",\"lastReadingTs\":\"2026-11-17T06:00:00Z\"}";
        assertEquals(List.of(), MessagesTest.errors("sensor.status.v1.schema.json", event));

        try (Client client = new Client()) {
            client.send("{\"type\":\"subscribe\",\"sensors\":true}");
            client.awaitType("subscribed");

            JsonNode message = client.await("the status change of " + sensorId,
                    m -> "sensor-status".equals(m.path("type").asText()) && sensorId.equals(m.path("event").path("sensorId").asText()),
                    () -> kafka.send(new ProducerRecord<>("sensor.status.v1", sensorId, event)));

            assertEquals("offline", message.get("event").get("status").asText());
            assertEquals("degraded", message.get("event").get("previousStatus").asText());
            assertEquals(List.of(), MessagesTest.errors("sensor.status.v1.schema.json", message.get("event").toString()));
        }
    }

    @Test
    @DisplayName("R-OPS-04 the metrics endpoint offers consumer lag, the age of the last record per topic and the number of clients")
    void metrics() throws Exception {
        try (Client client = new Client()) {
            client.send("{\"type\":\"subscribe\",\"sensors\":true}");
            client.awaitType("subscribed");
            String sensorId = "gs-" + UUID.randomUUID().toString().substring(0, 8);
            int[] seq = {0};
            client.await("a reading", m -> "telemetry".equals(m.path("type").asText()), () -> publishReading(sensorId, seq[0]++));

            URI metricsUri = endpoint.resolve("/q/metrics");
            String[] wanted = {
                "kafka_consumer_fetch_manager_records_lag",
                "skyfeed_kafka_last_record_age_seconds{topic=\"telemetry.v1\"}",
                "skyfeed_gateway_clients 1.0",
            };
            String body = "";
            long deadline = System.nanoTime() + TIMEOUT.toNanos();
            while (System.nanoTime() < deadline) {
                body = HttpClient.newHttpClient().send(java.net.http.HttpRequest.newBuilder(metricsUri).build(),
                        java.net.http.HttpResponse.BodyHandlers.ofString()).body();
                String current = body;
                if (java.util.Arrays.stream(wanted).allMatch(current::contains)) {
                    return;
                }
                Thread.sleep(500);
            }
            String last = body;
            fail("missing from /q/metrics: " + java.util.Arrays.stream(wanted).filter(name -> !last.contains(name)).toList());
        }
    }

    @Test
    @DisplayName("a message the gateway cannot use is answered with an error and the connection stays open")
    void badClientMessage() throws Exception {
        try (Client client = new Client()) {
            client.send("{\"type\":\"subscribe\",\"area\":{\"lamin\":15,\"lomin\":119,\"lamax\":10,\"lomax\":124}}");
            assertEquals("area needs lamin < lamax and lomin < lomax", client.awaitType("error").get("message").asText());

            client.send("not json");
            client.seen.clear();
            assertEquals("message is not JSON", client.awaitType("error").get("message").asText());

            client.send("{\"type\":\"subscribe\",\"sensors\":true}");
            client.awaitType("subscribed");
        }
    }
}
