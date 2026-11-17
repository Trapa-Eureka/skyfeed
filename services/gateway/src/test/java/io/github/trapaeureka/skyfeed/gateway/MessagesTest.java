package io.github.trapaeureka.skyfeed.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import io.github.trapaeureka.skyfeed.gateway.Messages.Subscription;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class MessagesTest {

    /** Errors of a message against one of the schemas in /contracts; empty means valid. */
    static List<String> errors(String schemaFile, String message) throws Exception {
        try (var in = MessagesTest.class.getResourceAsStream("/contracts/" + schemaFile)) {
            Schema schema = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12).getSchema(in);
            JsonNode node = Messages.JSON.readTree(message);
            return schema.validate(node, context -> context.executionConfig(config -> config.formatAssertionsEnabled(true)))
                    .stream().map(error -> error.getInstanceLocation() + ": " + error.getMessage()).toList();
        }
    }

    private static String rejection(String text) {
        return assertThrows(IllegalArgumentException.class, () -> Messages.parseSubscribe(text)).getMessage();
    }

    @Test
    @DisplayName("every message the gateway builds is valid against ws.server.v1")
    void serverMessagesMatchTheContract() throws Exception {
        JsonNode payload = Messages.JSON.readTree("{\"any\":\"object\"}");
        for (String message : List.of(
                Messages.hello(5),
                Messages.subscribed(new Subscription(new Area(10, 119.5, 15, 124.5), true)),
                Messages.subscribed(new Subscription(null, false)),
                Messages.aircraft(payload),
                Messages.telemetry(payload),
                Messages.heartbeat(Instant.parse("2026-11-17T06:00:00.123456Z")),
                Messages.error("nope"))) {
            assertEquals(List.of(), errors("ws.server.v1.schema.json", message), message);
        }
        assertFalse(errors("ws.server.v1.schema.json", "{\"type\":\"unknown\"}").isEmpty());
    }

    @Test
    @DisplayName("R-RT-01 a subscribe message gives the area and whether sensors are wanted")
    void parsesSubscribe() throws Exception {
        String text = "{\"type\":\"subscribe\",\"area\":{\"lamin\":10,\"lomin\":119.5,\"lamax\":15,\"lomax\":124.5},\"sensors\":true}";
        assertEquals(List.of(), errors("ws.client.v1.schema.json", text));

        Subscription subscription = Messages.parseSubscribe(text);
        assertEquals(new Area(10, 119.5, 15, 124.5), subscription.area());
        assertTrue(subscription.sensors());

        Subscription nothing = Messages.parseSubscribe("{\"type\":\"subscribe\",\"area\":null}");
        assertNull(nothing.area());
        assertFalse(nothing.sensors());
    }

    @Test
    @DisplayName("a message from a client that cannot be used is refused with a reason")
    void refusesBadMessages() {
        assertEquals("message is not JSON", rejection("{nope"));
        assertTrue(rejection("{\"type\":\"publish\"}").startsWith("the only message understood"));
        assertTrue(rejection("[]").startsWith("the only message understood"));
        assertEquals("sensors must be true or false", rejection("{\"type\":\"subscribe\",\"sensors\":\"yes\"}"));
        assertEquals("area needs the numbers lamin, lomin, lamax and lomax",
                rejection("{\"type\":\"subscribe\",\"area\":{\"lamin\":10}}"));
        assertEquals("area needs lamin < lamax and lomin < lomax",
                rejection("{\"type\":\"subscribe\",\"area\":{\"lamin\":15,\"lomin\":119,\"lamax\":10,\"lomax\":124}}"));
        assertEquals("area is outside latitude -90..90 or longitude -180..180",
                rejection("{\"type\":\"subscribe\",\"area\":{\"lamin\":10,\"lomin\":119,\"lamax\":95,\"lomax\":124}}"));
    }

    @Test
    @DisplayName("R-RT-01 an area contains the points inside it and on its edge, and no others")
    void areaContains() {
        Area area = new Area(10, 119.5, 15, 124.5);
        assertTrue(area.contains(12, 122));
        assertTrue(area.contains(10, 119.5));
        assertTrue(area.contains(15, 124.5));
        assertFalse(area.contains(9.999, 122));
        assertFalse(area.contains(12, 124.501));
    }
}
