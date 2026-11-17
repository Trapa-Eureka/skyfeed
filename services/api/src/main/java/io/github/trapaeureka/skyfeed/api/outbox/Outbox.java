package io.github.trapaeureka.skyfeed.api.outbox;

import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Transactional outbox. {@link #add} is called inside the transaction that makes a change; {@link #publishWaiting}
 * sends what was committed to Kafka. Delivery is at least once: if the service stops between sending an event and
 * marking it sent, the event is sent again, with the same id, so a consumer can recognise the repeat.
 */
@Service
public class Outbox {

    private final OutboxEventRepository events;
    private final EntityManager entityManager;
    private final KafkaTemplate<String, String> kafka;
    private final Clock clock;

    Outbox(OutboxEventRepository events, EntityManager entityManager, KafkaTemplate<String, String> kafka, Clock clock) {
        this.events = events;
        this.entityManager = entityManager;
        this.kafka = kafka;
        this.clock = clock;
    }

    /** Queues an event. Must run inside the caller's transaction, so the event exists exactly when the change does. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void add(UUID id, String topic, String key, String payload) {
        entityManager.persist(new OutboxEvent(id, topic, key, payload, clock.instant()));
    }

    /**
     * Sends waiting events in the order they were queued and marks each as sent once Kafka has confirmed it.
     * If Kafka does not confirm, the exception rolls the marks back and the events are tried again next time.
     *
     * @return how many events were sent
     */
    @Transactional
    public int publishWaiting() throws Exception {
        var waiting = events.lockUnpublished();
        for (OutboxEvent event : waiting) {
            kafka.send(event.getTopic(), event.getMessageKey(), event.getPayload()).get(10, TimeUnit.SECONDS);
            event.markPublished(clock.instant());
        }
        return waiting.size();
    }

    public long waiting() {
        return events.countByPublishedAtIsNull();
    }
}
