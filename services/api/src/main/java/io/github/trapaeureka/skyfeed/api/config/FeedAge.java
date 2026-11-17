package io.github.trapaeureka.skyfeed.api.config;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;

/**
 * Tracks when the last record of each Kafka topic was consumed, and offers it as the gauge
 * {@code skyfeed.kafka.last.record.age.seconds{topic}} (R-OPS-04). A feed that has gone quiet shows as a growing age.
 */
@Component
public class FeedAge {

    private final Map<String, AtomicLong> lastSeen = new ConcurrentHashMap<>();
    private final MeterRegistry meters;
    private final Clock clock;

    FeedAge(MeterRegistry meters, Clock clock) {
        this.meters = meters;
        this.clock = clock;
    }

    /** Call for every record consumed from the topic. */
    public void seen(String topic) {
        lastSeen.computeIfAbsent(topic, name -> {
            AtomicLong at = new AtomicLong();
            Gauge.builder("skyfeed.kafka.last.record.age.seconds", at, value -> (clock.millis() - value.get()) / 1000.0)
                    .tag("topic", name)
                    .description("Seconds since the last record of the topic was consumed")
                    .register(meters);
            return at;
        }).set(clock.millis());
    }
}
