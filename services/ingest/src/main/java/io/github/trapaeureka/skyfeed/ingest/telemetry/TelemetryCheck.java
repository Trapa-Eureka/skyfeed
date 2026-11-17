package io.github.trapaeureka.skyfeed.ingest.telemetry;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.trapaeureka.skyfeed.ingest.contract.ContractSchemas;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Decides whether one MQTT message may be forwarded as telemetry.v1. No I/O, no state. */
public final class TelemetryCheck {

    /** Largest payload that is looked at. A real reading is about 200 bytes. */
    static final int MAX_PAYLOAD_BYTES = 16 * 1024;

    private static final Pattern TOPIC = Pattern.compile("^sensors/([a-z0-9][a-z0-9-]{0,31})/telemetry$");
    private static final ObjectMapper JSON = new ObjectMapper();

    public sealed interface Result permits Accepted, Rejected {}

    /** A valid reading. {@code json} is the payload exactly as it was received. */
    public record Accepted(String sensorId, String json) implements Result {}

    /** A message that must not be forwarded. {@code sensorId} is null when the topic did not give a usable one. */
    public record Rejected(String sensorId, String reason) implements Result {}

    public Result check(String mqttTopic, byte[] payload) {
        Matcher topic = TOPIC.matcher(mqttTopic == null ? "" : mqttTopic);
        if (!topic.matches()) {
            return new Rejected(null, "topic is not sensors/{sensorId}/telemetry: " + mqttTopic);
        }
        String sensorId = topic.group(1);

        if (payload == null || payload.length == 0) {
            return new Rejected(sensorId, "empty payload");
        }
        if (payload.length > MAX_PAYLOAD_BYTES) {
            return new Rejected(sensorId, "payload is " + payload.length + " bytes; the limit is " + MAX_PAYLOAD_BYTES);
        }

        JsonNode message;
        try {
            message = JSON.readTree(payload);
        } catch (IOException e) {
            return new Rejected(sensorId, "payload is not JSON");
        }
        if (message == null || !message.isObject()) {
            return new Rejected(sensorId, "payload is not a JSON object");
        }

        List<String> errors = ContractSchemas.TELEMETRY_V1.errors(message);
        if (!errors.isEmpty()) {
            return new Rejected(sensorId, "does not match telemetry.v1: " + String.join("; ", errors.stream().limit(3).toList()));
        }
        if (!sensorId.equals(message.get("sensorId").asText())) {
            return new Rejected(sensorId, "sensorId in the payload (" + message.get("sensorId").asText() + ") is not the one in the topic");
        }
        return new Accepted(sensorId, new String(payload, StandardCharsets.UTF_8));
    }
}
