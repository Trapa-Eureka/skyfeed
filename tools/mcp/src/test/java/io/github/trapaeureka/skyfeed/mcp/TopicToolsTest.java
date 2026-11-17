package io.github.trapaeureka.skyfeed.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** The MCP server over HTTP, as a client would call it, against a real Kafka in a container. */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class TopicToolsTest {

    static final KafkaContainer KAFKA = new KafkaContainer(DockerImageName.parse("apache/kafka:4.4.0"));

    static {
        KAFKA.start(); // before anything asks for its address; the container is removed when the tests end
    }
    static final String TOPIC = "telemetry.v1";
    static final String GROUP = "skyfeed-api";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    @Value("${local.server.port}")
    int port;

    @DynamicPropertySource
    static void kafka(DynamicPropertyRegistry registry) {
        registry.add("skyfeed.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
    }

    /** Ten records on one topic, and a consumer group that has read and committed the first seven. */
    @BeforeAll
    static void data() throws Exception {
        try (KafkaProducer<String, String> producer = new KafkaProducer<>(Map.of(
                "bootstrap.servers", KAFKA.getBootstrapServers(),
                "key.serializer", StringSerializer.class.getName(),
                "value.serializer", StringSerializer.class.getName()))) {
            for (int i = 0; i < 10; i++) {
                ProducerRecord<String, String> record = new ProducerRecord<>(TOPIC, 0, 1_794_900_000_000L + i * 1000L, "gs-a", "{\"seq\":" + i + "}");
                record.headers().add(new RecordHeader("origin", "test".getBytes(StandardCharsets.UTF_8)));
                producer.send(record).get();
            }
            producer.send(new ProducerRecord<>("big.v1", "k", "x".repeat(KafkaReader.MAX_VALUE_CHARS + 500))).get();
        }
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(Map.of(
                "bootstrap.servers", KAFKA.getBootstrapServers(),
                "group.id", GROUP,
                "enable.auto.commit", false,
                "key.deserializer", StringDeserializer.class.getName(),
                "value.deserializer", StringDeserializer.class.getName()))) {
            TopicPartition partition = new TopicPartition(TOPIC, 0);
            consumer.assign(List.of(partition));
            consumer.commitSync(Map.of(partition, new org.apache.kafka.clients.consumer.OffsetAndMetadata(7)));
        }
    }

    private JsonNode rpc(String method, String params) throws IOException, InterruptedException {
        HttpResponse<String> response = HTTP.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/mcp"))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"" + method + "\",\"params\":" + params + "}"))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), response.body());
        return JSON.readTree(response.body()).get("result");
    }

    /** Calls a tool and returns its result: the JSON it answered with, parsed. */
    private JsonNode call(String tool, String arguments) throws IOException, InterruptedException {
        JsonNode result = rpc("tools/call", "{\"name\":\"" + tool + "\",\"arguments\":" + arguments + "}");
        assertFalse(result.get("isError").asBoolean(), result.toString());
        return JSON.readTree(result.get("content").get(0).get("text").asString());
    }

    private String error(String tool, String arguments) throws IOException, InterruptedException {
        JsonNode result = rpc("tools/call", "{\"name\":\"" + tool + "\",\"arguments\":" + arguments + "}");
        assertTrue(result.get("isError").asBoolean(), "expected an error, got " + result);
        return result.get("content").get(0).get("text").asString();
    }

    @Test
    @DisplayName("R-AI-01 the server offers exactly three tools, and each declares that it only reads")
    void offersOnlyReadOnlyTools() throws Exception {
        JsonNode tools = rpc("tools/list", "{}").get("tools");

        Set<String> names = new TreeSet<>();
        for (JsonNode tool : tools) {
            names.add(tool.get("name").asString());
            assertTrue(tool.get("annotations").get("readOnlyHint").asBoolean(), tool.get("name") + " does not declare readOnlyHint");
            assertFalse(tool.get("annotations").get("destructiveHint").asBoolean());
            assertFalse(tool.get("description").asString().isBlank());
        }
        assertEquals(Set.of("consumer_lag", "list_topics", "recent_messages"), names);
    }

    @Test
    @DisplayName("R-AI-01 the server's source contains none of the known ways to write to Kafka (a list of names, so not a proof on its own)")
    void sourceHasNoWritingCalls() throws IOException {
        Pattern writing = Pattern.compile("KafkaProducer|ProducerRecord|\\.send\\(|commitSync|commitAsync|createTopics|createPartitions|createAcls"
                + "|delete[A-Z]\\w*\\(|alter[A-Z]\\w*\\(|incrementalAlterConfigs|removeMembersFromConsumerGroup|electLeaders|\\.subscribe\\("
                + "|group\\.id|createDelegationToken|updateFeatures|unregisterBroker|fenceProducers|abortTransaction|Transaction\\(");
        List<String> found = new ArrayList<>();
        try (Stream<Path> files = Files.walk(Path.of("src/main/java"))) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                List<String> lines = Files.readAllLines(file);
                for (int i = 0; i < lines.size(); i++) {
                    if (writing.matcher(lines.get(i)).find()) {
                        found.add(file.getFileName() + ":" + (i + 1) + " " + lines.get(i).trim());
                    }
                }
            }
        }
        assertEquals(List.of(), found);
    }

    @Test
    @DisplayName("R-AI-01 list_topics names the topics with their partitions and message counts")
    void listTopics() throws Exception {
        JsonNode answer = call("list_topics", "{}");
        assertTrue(Duration.between(java.time.Instant.parse(answer.get("asOf").asString()), java.time.Instant.now()).abs().toSeconds() < 30);
        JsonNode topics = answer.get("topics");

        JsonNode telemetry = null;
        for (JsonNode topic : topics) {
            assertFalse(topic.get("name").asString().startsWith("__"), "an internal topic is listed");
            if (TOPIC.equals(topic.get("name").asString())) {
                telemetry = topic;
            }
        }
        assertEquals(10, telemetry.get("messages").asLong());
        assertEquals(1, telemetry.get("partitions").asInt());
    }

    @Test
    @DisplayName("R-AI-01 recent_messages returns the newest messages first, with key, value and headers")
    void recentMessages() throws Exception {
        JsonNode answer = call("recent_messages", "{\"topic\":\"" + TOPIC + "\",\"limit\":3}");
        assertEquals(TOPIC, answer.get("topic").asString());
        assertTrue(answer.has("asOf"));
        JsonNode messages = answer.get("messages");

        assertEquals(3, messages.size());
        assertEquals("{\"seq\":9}", messages.get(0).get("value").asString());
        assertEquals("{\"seq\":7}", messages.get(2).get("value").asString());
        assertEquals(9, messages.get(0).get("offset").asLong());
        assertEquals("gs-a", messages.get(0).get("key").asString());
        assertEquals("test", messages.get(0).get("headers").get("origin").asString());
        assertEquals(java.time.Instant.ofEpochMilli(1_794_900_000_000L + 9000L).toString(), messages.get(0).get("timestamp").asString());

        assertEquals(10, call("recent_messages", "{\"topic\":\"" + TOPIC + "\"}").get("messages").size(), "the default is 10");
        assertEquals(10, call("recent_messages", "{\"topic\":\"" + TOPIC + "\",\"limit\":50}").get("messages").size(), "there are only 10");

        JsonNode big = call("recent_messages", "{\"topic\":\"big.v1\",\"limit\":1}").get("messages").get(0);
        assertTrue(big.get("valueTruncated").asBoolean());
        assertEquals(KafkaReader.MAX_VALUE_CHARS, big.get("value").asString().length());
    }

    @Test
    @DisplayName("R-AI-01 consumer_lag tells how far a group is behind")
    void consumerLag() throws Exception {
        JsonNode report = call("consumer_lag", "{}");

        JsonNode group = null;
        for (JsonNode candidate : report.get("groups")) {
            if (GROUP.equals(candidate.get("group").asString())) {
                group = candidate;
            }
        }
        assertEquals(TOPIC, group.get("topics").get(0).get("topic").asString());
        assertEquals(3, group.get("topics").get(0).get("lag").asLong(), "10 records, 7 committed");
        assertEquals(0, group.get("topics").get(0).get("partitionsWithoutPosition").asInt());
        assertTrue(report.has("asOf"));
    }

    @Test
    @DisplayName("R-AI-01 consumer_lag counts a partition the group never committed on as backlog, and says so")
    void lagCountsPartitionsWithoutPosition() throws Exception {
        String topic = "three.v1";
        String groupId = "partial-" + java.util.UUID.randomUUID();
        try (Admin admin = Admin.create(Map.of("bootstrap.servers", KAFKA.getBootstrapServers()))) {
            admin.createTopics(List.of(new org.apache.kafka.clients.admin.NewTopic(topic, 3, (short) 1))).all().get();
        }
        try (KafkaProducer<String, String> producer = new KafkaProducer<>(Map.of(
                "bootstrap.servers", KAFKA.getBootstrapServers(),
                "key.serializer", StringSerializer.class.getName(),
                "value.serializer", StringSerializer.class.getName()))) {
            for (int partition = 0; partition < 3; partition++) {
                for (int i = 0; i < 4; i++) {
                    producer.send(new ProducerRecord<>(topic, partition, "k", "v")).get();
                }
            }
        }
        // The group has read partition 0 to the end and has never touched partitions 1 and 2.
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(Map.of(
                "bootstrap.servers", KAFKA.getBootstrapServers(),
                "group.id", groupId,
                "enable.auto.commit", false,
                "key.deserializer", StringDeserializer.class.getName(),
                "value.deserializer", StringDeserializer.class.getName()))) {
            TopicPartition first = new TopicPartition(topic, 0);
            consumer.assign(List.of(first));
            consumer.commitSync(Map.of(first, new org.apache.kafka.clients.consumer.OffsetAndMetadata(4)));
        }

        JsonNode lag = null;
        for (JsonNode candidate : call("consumer_lag", "{}").get("groups")) {
            if (groupId.equals(candidate.get("group").asString())) {
                lag = candidate.get("topics").get(0);
            }
        }
        assertEquals(topic, lag.get("topic").asString());
        assertEquals(3, lag.get("partitions").asInt());
        assertEquals(2, lag.get("partitionsWithoutPosition").asInt());
        assertEquals(8, lag.get("lag").asLong(), "4 unread records on each of the two partitions never read");
    }

    @Test
    @DisplayName("a request addressed to another host name, or coming from a page of another site, is refused")
    void refusesForeignHostAndOrigin() throws Exception {
        assertEquals(200, post(Map.of()));
        assertEquals(200, post(Map.of("Origin", "http://localhost:3000")));
        assertEquals(403, post(Map.of("Origin", "http://evil.example")));
        assertEquals(403, post(Map.of("Origin", "null")));
        assertEquals(403, rawWithHost("evil.example"));
        assertEquals(200, rawWithHost("localhost:" + port));
    }

    private int post(Map<String, String> headers) throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/mcp"))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\",\"params\":{}}"));
        headers.forEach(request::header);
        return HTTP.send(request.build(), HttpResponse.BodyHandlers.discarding()).statusCode();
    }

    /** The Java HTTP client does not let a test set the Host header, so this one request is written by hand. */
    private int rawWithHost(String host) throws IOException {
        String body = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\",\"params\":{}}";
        try (java.net.Socket socket = new java.net.Socket("127.0.0.1", port)) {
            socket.setSoTimeout(10_000);
            socket.getOutputStream().write(("POST /mcp HTTP/1.1\r\nHost: " + host + "\r\nContent-Type: application/json\r\n"
                    + "Accept: application/json, text/event-stream\r\nContent-Length: " + body.length() + "\r\nConnection: close\r\n\r\n" + body)
                    .getBytes(StandardCharsets.UTF_8));
            String status = new java.io.BufferedReader(new java.io.InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8)).readLine();
            return Integer.parseInt(status.split(" ")[1]);
        }
    }

    @Test
    @DisplayName("R-AI-01 calling the tools changes nothing in Kafka: no records, no committed positions, no new groups")
    void readingChangesNothing() throws Exception {
        try (Admin admin = Admin.create(Map.of("bootstrap.servers", KAFKA.getBootstrapServers()))) {
            TopicPartition partition = new TopicPartition(TOPIC, 0);
            Set<String> topicsBefore = admin.listTopics().names().get();
            List<String> groupsBefore = admin.listGroups().all().get().stream().map(g -> g.groupId()).sorted().toList();

            for (int i = 0; i < 3; i++) {
                call("list_topics", "{}");
                call("recent_messages", "{\"topic\":\"" + TOPIC + "\",\"limit\":50}");
                call("consumer_lag", "{}");
            }
            error("recent_messages", "{\"topic\":\"not.there.v1\"}"); // must not create the topic by asking for it
            Thread.sleep(Duration.ofSeconds(1));

            assertEquals(10, admin.listOffsets(Map.of(partition, OffsetSpec.latest())).all().get().get(partition).offset());
            assertEquals(7, admin.listConsumerGroupOffsets(GROUP).partitionsToOffsetAndMetadata().get().get(partition).offset());
            assertEquals(topicsBefore, admin.listTopics().names().get());
            assertEquals(groupsBefore, admin.listGroups().all().get().stream().map(g -> g.groupId()).sorted().toList());
        }
    }

    @Test
    @DisplayName("a request that cannot be answered is refused with a reason")
    void badRequests() throws Exception {
        assertTrue(error("recent_messages", "{\"topic\":\"not.there.v1\"}").contains("there is no topic named not.there.v1"));
        assertTrue(error("recent_messages", "{\"topic\":\"" + TOPIC + "\",\"limit\":51}").contains("limit must be between 1 and 50"));
        assertTrue(error("recent_messages", "{\"topic\":\"" + TOPIC + "\",\"limit\":0}").contains("limit must be between 1 and 50"));
        assertTrue(error("recent_messages", "{\"topic\":\"bad topic!\"}").contains("topic must be a Kafka topic name"));
    }
}
