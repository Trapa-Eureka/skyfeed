package io.github.trapaeureka.skyfeed.mcp;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.admin.TopicDescription;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Everything this server does with Kafka. It only asks and reads: it lists and describes through the admin
 * client, and reads records with consumers that belong to no group, so they commit nothing and move nobody's
 * position. There is no producer and no call that creates, alters or deletes anything (R-AI-01).
 */
@Component
public class KafkaReader implements AutoCloseable {

    static final int MAX_MESSAGES = 50;
    static final int MAX_VALUE_CHARS = 2000;
    private static final Pattern TOPIC_NAME = Pattern.compile("^[A-Za-z0-9._-]{1,249}$");
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    public record TopicInfo(String name, int partitions, long messages) {}

    public record TopicMessage(int partition, long offset, String timestamp, String key, String value, boolean valueTruncated,
            Map<String, String> headers) {}

    /** Every answer says when Kafka was asked, so a figure kept for a while cannot be taken for a current one. */
    public record TopicList(String asOf, List<TopicInfo> topics) {}

    public record MessageList(String asOf, String topic, List<TopicMessage> messages) {}

    /**
     * @param lag messages not read yet, over all partitions of the topic. A partition the group has no committed
     *        position on counts in full: nothing of it is known to have been read.
     * @param partitions how many partitions the topic has
     * @param partitionsWithoutPosition how many of them the group has never committed a position on
     */
    public record TopicLag(String topic, long lag, int partitions, int partitionsWithoutPosition) {}

    public record GroupLag(String group, List<TopicLag> topics) {}

    /**
     * @param groups the groups whose position is known for at least one partition, with their lag per topic
     * @param groupsWithoutOffsets groups that have committed no offsets, so their lag cannot be told. The SkyFeed
     *        gateway reads this way on purpose (it always starts from the newest record), and so do command-line consumers.
     */
    public record LagReport(String asOf, List<GroupLag> groups, List<String> groupsWithoutOffsets) {}

    /** A request that cannot be answered because of what was asked; the text is for the caller. */
    public static final class BadRequestException extends RuntimeException {
        BadRequestException(String message) {
            super(message);
        }
    }

    private final String bootstrapServers;
    private final Admin admin;

    public KafkaReader(@Value("${skyfeed.kafka.bootstrap-servers}") String bootstrapServers) {
        this.bootstrapServers = bootstrapServers;
        this.admin = Admin.create(Map.of(
                "bootstrap.servers", bootstrapServers,
                "client.id", "skyfeed-mcp-admin",
                "request.timeout.ms", 10_000,
                "default.api.timeout.ms", 10_000));
    }

    /** The topics that hold SkyFeed data (Kafka's own internal topics are left out), with their size. */
    public TopicList listTopics() {
        String asOf = Instant.now().toString();
        Set<String> names = await(admin.listTopics().names());
        Map<String, TopicDescription> descriptions = await(admin.describeTopics(names).allTopicNames());
        List<TopicPartition> partitions = new ArrayList<>();
        descriptions.values().forEach(topic -> topic.partitions().forEach(p -> partitions.add(new TopicPartition(topic.name(), p.partition()))));
        Map<TopicPartition, Long> earliest = offsets(partitions, OffsetSpec.earliest());
        Map<TopicPartition, Long> latest = offsets(partitions, OffsetSpec.latest());

        return new TopicList(asOf, descriptions.values().stream()
                .sorted(Comparator.comparing(TopicDescription::name))
                .map(topic -> new TopicInfo(topic.name(), topic.partitions().size(), topic.partitions().stream()
                        .map(p -> new TopicPartition(topic.name(), p.partition()))
                        .mapToLong(tp -> latest.get(tp) - earliest.get(tp))
                        .sum()))
                .toList());
    }

    /** The newest records of a topic across all its partitions, newest first. */
    public MessageList recentMessages(String topic, int limit) {
        String asOf = Instant.now().toString();
        if (topic == null || !TOPIC_NAME.matcher(topic).matches()) {
            throw new BadRequestException("topic must be a Kafka topic name (letters, digits, dot, underscore, hyphen)");
        }
        if (limit < 1 || limit > MAX_MESSAGES) {
            throw new BadRequestException("limit must be between 1 and " + MAX_MESSAGES);
        }
        if (!await(admin.listTopics().names()).contains(topic)) {
            throw new BadRequestException("there is no topic named " + topic + "; use list_topics to see the topics");
        }

        // No group id: this consumer cannot commit, and assigning partitions by hand joins no consumer group.
        try (KafkaConsumer<byte[], byte[]> consumer = new KafkaConsumer<>(Map.of(
                "bootstrap.servers", bootstrapServers,
                "client.id", "skyfeed-mcp-reader",
                "enable.auto.commit", false,
                "allow.auto.create.topics", false,
                // If a position asked for is gone (retention removed it), fail instead of quietly jumping elsewhere.
                "auto.offset.reset", "none",
                "key.deserializer", ByteArrayDeserializer.class.getName(),
                "value.deserializer", ByteArrayDeserializer.class.getName()))) {
            List<TopicPartition> partitions = consumer.partitionsFor(topic, TIMEOUT).stream()
                    .map(info -> new TopicPartition(topic, info.partition()))
                    .toList();
            consumer.assign(partitions);
            Map<TopicPartition, Long> begin = consumer.beginningOffsets(partitions, TIMEOUT);
            Map<TopicPartition, Long> end = consumer.endOffsets(partitions, TIMEOUT);
            partitions.forEach(tp -> consumer.seek(tp, Math.max(begin.get(tp), end.get(tp) - limit)));

            List<ConsumerRecord<byte[], byte[]>> records = new ArrayList<>();
            long deadline = System.nanoTime() + TIMEOUT.toNanos();
            while (System.nanoTime() < deadline && partitions.stream().anyMatch(tp -> consumer.position(tp) < end.get(tp))) {
                consumer.poll(Duration.ofMillis(300)).forEach(record -> {
                    if (record.offset() < end.get(new TopicPartition(record.topic(), record.partition()))) {
                        records.add(record);
                    }
                });
            }
            // Out of time with a partition not read to its end: say so. A partial list would pass for the newest
            // messages, and an empty one for "the topic is empty".
            List<TopicPartition> unread = partitions.stream().filter(tp -> consumer.position(tp) < end.get(tp)).toList();
            if (!unread.isEmpty()) {
                throw new IllegalStateException("could not read " + topic + " completely within " + TIMEOUT.toSeconds()
                        + " s (partitions not finished: " + unread.stream().map(TopicPartition::partition).toList() + "); no messages are returned rather than some");
            }
            return new MessageList(asOf, topic, records.stream()
                    .sorted(Comparator.comparingLong((ConsumerRecord<byte[], byte[]> r) -> r.timestamp()).reversed()
                            .thenComparing(Comparator.comparingLong((ConsumerRecord<byte[], byte[]> r) -> r.offset()).reversed()))
                    .limit(limit)
                    .map(KafkaReader::toMessage)
                    .toList());
        }
    }

    /** For each consumer group, how many records of each topic it has still to read. */
    public LagReport consumerLag() {
        String asOf = Instant.now().toString();
        List<String> groups = await(admin.listGroups().all()).stream().map(group -> group.groupId()).sorted().toList();
        List<GroupLag> result = new ArrayList<>();
        List<String> withoutOffsets = new ArrayList<>();
        for (String group : groups) {
            Map<TopicPartition, OffsetAndMetadata> committed = await(admin.listConsumerGroupOffsets(group).partitionsToOffsetAndMetadata());
            committed.values().removeIf(offset -> offset == null);
            if (committed.isEmpty()) {
                withoutOffsets.add(group);
                continue;
            }
            // Every partition of every topic the group has a position on, not only the partitions it committed:
            // a partition it never got to must show as backlog, not vanish from the sum.
            Set<String> topics = new java.util.TreeSet<>();
            committed.keySet().forEach(partition -> topics.add(partition.topic()));
            List<TopicPartition> all = new ArrayList<>();
            await(admin.describeTopics(topics).allTopicNames()).values()
                    .forEach(topic -> topic.partitions().forEach(p -> all.add(new TopicPartition(topic.name(), p.partition()))));
            Map<TopicPartition, Long> earliest = offsets(all, OffsetSpec.earliest());
            Map<TopicPartition, Long> latest = offsets(all, OffsetSpec.latest());
            Map<String, long[]> perTopic = new TreeMap<>(); // topic -> {lag, partitions, partitions without a position}
            for (TopicPartition partition : all) {
                long[] sum = perTopic.computeIfAbsent(partition.topic(), name -> new long[3]);
                OffsetAndMetadata position = committed.get(partition);
                sum[0] += Math.max(0, latest.get(partition) - (position != null ? position.offset() : earliest.get(partition)));
                sum[1]++;
                sum[2] += position == null ? 1 : 0;
            }
            result.add(new GroupLag(group, perTopic.entrySet().stream()
                    .map(entry -> new TopicLag(entry.getKey(), entry.getValue()[0], (int) entry.getValue()[1], (int) entry.getValue()[2]))
                    .toList()));
        }
        return new LagReport(asOf, result, withoutOffsets);
    }

    private Map<TopicPartition, Long> offsets(List<TopicPartition> partitions, OffsetSpec spec) {
        Map<TopicPartition, OffsetSpec> request = new LinkedHashMap<>();
        partitions.forEach(partition -> request.put(partition, spec));
        Map<TopicPartition, Long> result = new LinkedHashMap<>();
        await(admin.listOffsets(request).all()).forEach((partition, info) -> result.put(partition, info.offset()));
        return result;
    }

    private static TopicMessage toMessage(ConsumerRecord<byte[], byte[]> record) {
        String value = record.value() == null ? null : new String(record.value(), StandardCharsets.UTF_8);
        boolean truncated = value != null && value.length() > MAX_VALUE_CHARS;
        Map<String, String> headers = new LinkedHashMap<>();
        for (Header header : record.headers()) {
            headers.put(header.key(), header.value() == null ? null : new String(header.value(), StandardCharsets.UTF_8));
        }
        return new TopicMessage(record.partition(), record.offset(), Instant.ofEpochMilli(record.timestamp()).toString(),
                record.key() == null ? null : new String(record.key(), StandardCharsets.UTF_8),
                truncated ? value.substring(0, MAX_VALUE_CHARS) : value, truncated, headers);
    }

    private static <T> T await(org.apache.kafka.common.KafkaFuture<T> future) {
        try {
            return future.get(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while asking Kafka");
        } catch (ExecutionException | TimeoutException e) {
            throw new IllegalStateException("Kafka did not answer: " + (e.getCause() != null ? e.getCause().getMessage() : e.getMessage()));
        }
    }

    @Override
    public void close() {
        admin.close(Duration.ofSeconds(5));
    }
}
