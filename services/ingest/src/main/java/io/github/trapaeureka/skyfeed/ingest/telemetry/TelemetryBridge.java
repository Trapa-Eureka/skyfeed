package io.github.trapaeureka.skyfeed.ingest.telemetry;

import io.github.trapaeureka.skyfeed.ingest.telemetry.TelemetryCheck.Accepted;
import io.github.trapaeureka.skyfeed.ingest.telemetry.TelemetryCheck.Rejected;
import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.smallrye.mutiny.Uni;
import io.smallrye.reactive.messaging.MutinyEmitter;
import io.smallrye.reactive.messaging.kafka.api.OutgoingKafkaRecordMetadata;
import io.smallrye.reactive.messaging.mqtt.MqttMessage;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.eclipse.microprofile.reactive.messaging.Channel;
import org.eclipse.microprofile.reactive.messaging.Incoming;
import org.eclipse.microprofile.reactive.messaging.Message;
import org.jboss.logging.Logger;

/**
 * Forwards sensor telemetry from MQTT to Kafka (R-ING-01). A message that fails the contract goes to the
 * dead-letter topic with the reason instead (R-ING-02). The MQTT message is acknowledged only after Kafka
 * has accepted the record, so a reading is delivered at least once.
 */
@ApplicationScoped
public class TelemetryBridge {

    private static final Logger LOG = Logger.getLogger(TelemetryBridge.class);

    private final TelemetryCheck check = new TelemetryCheck();
    private final AtomicLong forwarded = new AtomicLong();
    private final AtomicLong rejected = new AtomicLong();
    private final AtomicLong failed = new AtomicLong();
    /** When the last MQTT message arrived, in ms; 0 until the first one. */
    private final AtomicLong lastMessage = new AtomicLong();

    @Inject
    MeterRegistry meters;

    @PostConstruct
    void registerMetrics() {
        for (var counter : java.util.Map.of("forwarded", forwarded, "rejected", rejected, "failed", failed).entrySet()) {
            FunctionCounter.builder("skyfeed.telemetry.messages", counter.getValue(), AtomicLong::get)
                    .tag("result", counter.getKey())
                    .description("MQTT telemetry messages by what happened to them")
                    .register(meters);
        }
        Gauge.builder("skyfeed.source.last.message.age.seconds", lastMessage, at -> at.get() == 0 ? Double.NaN : (System.currentTimeMillis() - at.get()) / 1000.0)
                .tag("source", "mqtt")
                .description("Seconds since the last message arrived from the source")
                .register(meters);
    }

    @Channel("telemetry-out")
    MutinyEmitter<String> telemetry;

    @Channel("telemetry-dlq")
    MutinyEmitter<byte[]> deadLetters;

    @Incoming("telemetry-mqtt")
    public Uni<Void> onMessage(Message<byte[]> message) {
        lastMessage.set(System.currentTimeMillis());
        String topic = message instanceof MqttMessage<?> mqtt ? mqtt.getTopic() : null;

        Uni<Void> sent = switch (check.check(topic, message.getPayload())) {
            case Accepted accepted -> telemetry
                    .sendMessage(Message.of(accepted.json()).addMetadata(
                            OutgoingKafkaRecordMetadata.<String>builder().withKey(accepted.sensorId()).build()))
                    .invoke(forwarded::incrementAndGet);
            case Rejected reject -> {
                LOG.warnf("Telemetry rejected (topic %s): %s", topic, reject.reason());
                RecordHeaders headers = new RecordHeaders();
                headers.add("reason", reject.reason().getBytes(StandardCharsets.UTF_8));
                headers.add("mqtt-topic", String.valueOf(topic).getBytes(StandardCharsets.UTF_8));
                byte[] payload = message.getPayload() == null ? new byte[0] : message.getPayload();
                yield deadLetters
                        .sendMessage(Message.of(payload).addMetadata(OutgoingKafkaRecordMetadata.<String>builder()
                                .withKey(reject.sensorId())
                                .withHeaders(headers)
                                .build()))
                        .invoke(rejected::incrementAndGet);
            }
        };

        return sent.onItemOrFailure().transformToUni((ignored, failure) -> {
            if (failure == null) {
                return Uni.createFrom().completionStage(message.ack());
            }
            failed.incrementAndGet();
            LOG.errorf(failure, "Could not write telemetry from %s to Kafka", topic);
            return Uni.createFrom().completionStage(message.nack(failure));
        });
    }

    public long forwarded() {
        return forwarded.get();
    }

    public long rejected() {
        return rejected.get();
    }

    public long failed() {
        return failed.get();
    }
}
