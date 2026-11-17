package io.github.trapaeureka.skyfeed.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/** Builds the messages of contracts/ws.server.v1.schema.json and reads those of ws.client.v1.schema.json. */
public final class Messages {

    static final ObjectMapper JSON = new ObjectMapper();

    /** A subscribe message that was understood. */
    public record Subscription(Area area, boolean sensors) {}

    private Messages() {}

    public static String hello(double heartbeatSeconds) {
        return JSON.createObjectNode().put("type", "hello").put("heartbeatSeconds", heartbeatSeconds).toString();
    }

    public static String subscribed(Subscription subscription) {
        ObjectNode message = JSON.createObjectNode().put("type", "subscribed");
        Area area = subscription.area();
        if (area == null) {
            message.putNull("area");
        } else {
            message.putObject("area").put("lamin", area.lamin()).put("lomin", area.lomin())
                    .put("lamax", area.lamax()).put("lomax", area.lomax());
        }
        return message.put("sensors", subscription.sensors()).toString();
    }

    public static String aircraft(JsonNode state) {
        ObjectNode message = JSON.createObjectNode().put("type", "aircraft");
        message.set("state", state);
        return message.toString();
    }

    public static String telemetry(JsonNode reading) {
        ObjectNode message = JSON.createObjectNode().put("type", "telemetry");
        message.set("reading", reading);
        return message.toString();
    }

    public static String heartbeat(Instant now) {
        return JSON.createObjectNode().put("type", "heartbeat").put("ts", now.truncatedTo(ChronoUnit.MILLIS).toString()).toString();
    }

    public static String error(String text) {
        return JSON.createObjectNode().put("type", "error").put("message", text).toString();
    }

    /**
     * Reads a message from a client.
     *
     * @throws IllegalArgumentException with a text for the client when the message cannot be used
     */
    public static Subscription parseSubscribe(String text) {
        JsonNode message;
        try {
            message = JSON.readTree(text);
        } catch (Exception e) {
            throw new IllegalArgumentException("message is not JSON");
        }
        if (message == null || !message.isObject() || !"subscribe".equals(message.path("type").asText(null))) {
            throw new IllegalArgumentException("the only message understood is {\"type\":\"subscribe\", ...}");
        }
        JsonNode sensors = message.get("sensors");
        if (sensors != null && !sensors.isBoolean()) {
            throw new IllegalArgumentException("sensors must be true or false");
        }
        JsonNode box = message.get("area");
        Area area = null;
        if (box != null && !box.isNull()) {
            if (!box.isObject() || !box.path("lamin").isNumber() || !box.path("lomin").isNumber()
                    || !box.path("lamax").isNumber() || !box.path("lomax").isNumber()) {
                throw new IllegalArgumentException("area needs the numbers lamin, lomin, lamax and lomax");
            }
            area = new Area(box.get("lamin").asDouble(), box.get("lomin").asDouble(),
                    box.get("lamax").asDouble(), box.get("lomax").asDouble());
        }
        return new Subscription(area, sensors != null && sensors.asBoolean());
    }
}
