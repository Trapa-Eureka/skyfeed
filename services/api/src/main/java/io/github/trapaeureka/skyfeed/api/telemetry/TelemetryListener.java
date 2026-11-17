package io.github.trapaeureka.skyfeed.api.telemetry;

import io.github.trapaeureka.skyfeed.api.config.FeedAge;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/** Reads telemetry.v1 and stores each reading. */
@Component
class TelemetryListener {

    private static final Logger LOG = LoggerFactory.getLogger(TelemetryListener.class);

    private final TelemetryStore store;
    private final ObjectMapper json;
    private final Counter stored;
    private final Counter duplicate;
    private final Counter invalid;
    private final FeedAge feedAge;

    TelemetryListener(TelemetryStore store, ObjectMapper json, MeterRegistry meters, FeedAge feedAge) {
        this.store = store;
        this.feedAge = feedAge;
        this.json = json;
        this.stored = meters.counter("skyfeed.telemetry.records", "result", "stored");
        this.duplicate = meters.counter("skyfeed.telemetry.records", "result", "duplicate");
        this.invalid = meters.counter("skyfeed.telemetry.records", "result", "invalid");
    }

    @KafkaListener(topics = "telemetry.v1")
    void onRecord(String value) {
        feedAge.seen("telemetry.v1");
        TelemetryMessage message;
        try {
            message = json.readValue(value, TelemetryMessage.class);
        } catch (JacksonException e) {
            message = null;
        }
        if (message == null || !message.isComplete()) {
            // ingest validates before it publishes, so this should not happen. Trying again would not help: drop it.
            invalid.increment();
            LOG.warn("Dropped a telemetry.v1 record that is not a complete message");
            return;
        }
        // A database failure here is thrown on purpose: the error handler tries the record again.
        if (store.store(message)) {
            stored.increment();
        } else {
            duplicate.increment();
        }
    }
}
