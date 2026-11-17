package io.github.trapaeureka.skyfeed.api.outbox;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Sends the outbox to Kafka at a short interval, and keeps trying while Kafka is away. */
@Component
class OutboxPublisher {

    private static final Logger LOG = LoggerFactory.getLogger(OutboxPublisher.class);

    private final Outbox outbox;
    private final Counter published;
    private boolean failing;

    OutboxPublisher(Outbox outbox, MeterRegistry meters) {
        this.outbox = outbox;
        this.published = meters.counter("skyfeed.outbox.published");
        Gauge.builder("skyfeed.outbox.waiting", outbox, o -> {
            try {
                return o.waiting();
            } catch (RuntimeException e) {
                return Double.NaN; // the database is away; the gauge has no value rather than a wrong one
            }
        }).description("Events committed but not yet sent to Kafka").register(meters);
    }

    @Scheduled(fixedDelayString = "${skyfeed.outbox.poll-interval}")
    void publish() {
        try {
            int sent;
            do {
                sent = outbox.publishWaiting();
                published.increment(sent);
            } while (sent > 0);
            if (failing) {
                LOG.info("Outbox is being published again");
                failing = false;
            }
        } catch (Exception e) {
            if (!failing) { // say it once, not twice a second
                LOG.warn("Outbox could not be published, will keep trying: {}", e.getMessage());
                failing = true;
            }
        }
    }
}
