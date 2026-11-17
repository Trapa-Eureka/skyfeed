package io.github.trapaeureka.skyfeed.api.outbox;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** An event waiting to be sent to Kafka, or already sent ({@code publishedAt} set). */
@Entity
@Table(name = "outbox_event")
public class OutboxEvent {

    @Id
    private UUID id;
    private String topic;
    private String messageKey;
    private String payload;
    private Instant createdAt;
    private Instant publishedAt;

    protected OutboxEvent() {}

    OutboxEvent(UUID id, String topic, String messageKey, String payload, Instant createdAt) {
        this.id = id;
        this.topic = topic;
        this.messageKey = messageKey;
        this.payload = payload;
        this.createdAt = createdAt;
    }

    public UUID getId() {
        return id;
    }

    public String getTopic() {
        return topic;
    }

    public String getMessageKey() {
        return messageKey;
    }

    public String getPayload() {
        return payload;
    }

    public Instant getPublishedAt() {
        return publishedAt;
    }

    void markPublished(Instant at) {
        this.publishedAt = at;
    }
}
