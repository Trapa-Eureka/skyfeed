package io.github.trapaeureka.skyfeed.api.aircraft;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/** Reads adsb.state.v1 and stores each aircraft state. */
@Component
class AdsbListener {

    private static final Logger LOG = LoggerFactory.getLogger(AdsbListener.class);

    private final AircraftStore store;
    private final ObjectMapper json;
    private final Counter stored;
    private final Counter duplicate;
    private final Counter invalid;

    AdsbListener(AircraftStore store, ObjectMapper json, MeterRegistry meters) {
        this.store = store;
        this.json = json;
        this.stored = meters.counter("skyfeed.adsb.records", "result", "stored");
        this.duplicate = meters.counter("skyfeed.adsb.records", "result", "duplicate");
        this.invalid = meters.counter("skyfeed.adsb.records", "result", "invalid");
    }

    @KafkaListener(topics = "adsb.state.v1")
    void onRecord(String value) {
        AdsbStateMessage message;
        try {
            message = json.readValue(value, AdsbStateMessage.class);
        } catch (JacksonException e) {
            message = null;
        }
        if (message == null || !message.isComplete()) {
            invalid.increment();
            LOG.warn("Dropped an adsb.state.v1 record that is not a complete message");
            return;
        }
        if (store.store(message)) {
            stored.increment();
        } else {
            duplicate.increment();
        }
    }
}
