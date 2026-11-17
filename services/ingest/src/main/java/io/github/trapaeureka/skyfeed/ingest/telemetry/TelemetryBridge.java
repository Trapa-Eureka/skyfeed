package io.github.trapaeureka.skyfeed.ingest.telemetry;

import io.github.trapaeureka.skyfeed.ingest.telemetry.TelemetryCheck.Accepted;
import io.github.trapaeureka.skyfeed.ingest.telemetry.TelemetryCheck.Rejected;
import io.smallrye.mutiny.Uni;
import io.smallrye.reactive.messaging.MutinyEmitter;
import io.smallrye.reactive.messaging.kafka.api.OutgoingKafkaRecordMetadata;
import io.smallrye.reactive.messaging.mqtt.MqttMessage;
import jakarta.enterprise.context.ApplicationScoped;
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

    @Channel("telemetry-out")
    MutinyEmitter<String> telemetry;

    @Channel("telemetry-dlq")
    MutinyEmitter<byte[]> deadLetters;

    @Incoming("telemetry-mqtt")
    public Uni<Void> onMessage(Message<byte[]> message) {
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
