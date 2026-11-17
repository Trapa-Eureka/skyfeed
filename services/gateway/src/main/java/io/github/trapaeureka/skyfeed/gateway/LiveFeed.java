package io.github.trapaeureka.skyfeed.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import java.time.Instant;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.eclipse.microprofile.reactive.messaging.Incoming;
import org.jboss.logging.Logger;

/**
 * Passes what arrives on Kafka to the connected clients that asked for it: aircraft states to clients whose map
 * area contains the aircraft (R-RT-01), sensor readings to clients that subscribed to sensors (R-RT-02), and a
 * heartbeat to everyone (R-RT-04). Nothing here waits for a client; see {@link ClientSession}.
 */
@ApplicationScoped
public class LiveFeed {

    private static final Logger LOG = Logger.getLogger(LiveFeed.class);

    private final Map<String, ClientSession> sessions = new ConcurrentHashMap<>();
    private final AtomicLong undeliverable = new AtomicLong();

    void add(String connectionId, ClientSession session) {
        sessions.put(connectionId, session);
    }

    ClientSession get(String connectionId) {
        return sessions.get(connectionId);
    }

    void remove(String connectionId) {
        ClientSession session = sessions.remove(connectionId);
        if (session != null) {
            session.close();
        }
    }

    Collection<ClientSession> sessions() {
        return sessions.values();
    }

    long undeliverable() {
        return undeliverable.get();
    }

    @Incoming("adsb-in")
    public void onAircraftState(String record) {
        JsonNode state = parse(record);
        if (state == null || !state.path("icao24").isTextual() || !state.path("lat").isNumber() || !state.path("lon").isNumber()) {
            undeliverable.incrementAndGet();
            return;
        }
        double lat = state.get("lat").asDouble();
        double lon = state.get("lon").asDouble();
        String key = "aircraft:" + state.get("icao24").asText();
        String message = null; // built once, and only if somebody wants it
        for (ClientSession session : sessions.values()) {
            Area area = session.area();
            if (area != null && area.contains(lat, lon)) {
                if (message == null) {
                    message = Messages.aircraft(state);
                }
                session.offer(key, message);
            }
        }
    }

    @Incoming("telemetry-in")
    public void onTelemetry(String record) {
        JsonNode reading = parse(record);
        if (reading == null || !reading.path("sensorId").isTextual()) {
            undeliverable.incrementAndGet();
            return;
        }
        String key = "telemetry:" + reading.get("sensorId").asText();
        String message = null;
        for (ClientSession session : sessions.values()) {
            if (session.sensors()) {
                if (message == null) {
                    message = Messages.telemetry(reading);
                }
                session.offer(key, message);
            }
        }
    }

    @Scheduled(every = "${skyfeed.gateway.heartbeat-interval}")
    void heartbeat() {
        String message = Messages.heartbeat(Instant.now());
        for (ClientSession session : sessions.values()) {
            session.offer("heartbeat", message);
        }
    }

    private static JsonNode parse(String record) {
        try {
            JsonNode node = Messages.JSON.readTree(record);
            return node != null && node.isObject() ? node : null;
        } catch (Exception e) {
            LOG.debug("Ignored a Kafka record that is not JSON");
            return null;
        }
    }
}
