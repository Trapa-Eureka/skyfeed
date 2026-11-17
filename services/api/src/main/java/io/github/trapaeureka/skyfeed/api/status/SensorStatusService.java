package io.github.trapaeureka.skyfeed.api.status;

import io.github.trapaeureka.skyfeed.api.outbox.Outbox;
import io.github.trapaeureka.skyfeed.api.telemetry.Sensor;
import io.github.trapaeureka.skyfeed.api.telemetry.SensorRepository;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
public class SensorStatusService {

    static final String TOPIC = "sensor.status.v1";

    private final SensorRepository sensors;
    private final EntityManager entityManager;
    private final Outbox outbox;
    private final ObjectMapper json;

    SensorStatusService(SensorRepository sensors, EntityManager entityManager, Outbox outbox, ObjectMapper json) {
        this.sensors = sensors;
        this.entityManager = entityManager;
        this.outbox = outbox;
        this.json = json;
    }

    /**
     * Records that the sensor went from the status it was seen in to a new one, and queues the event that reports
     * it (R-OPS-01). The change, the record of it and the queued event are one transaction: all happen or none.
     *
     * @return false when the sensor was no longer in the status the caller saw, so nothing was done
     */
    @Transactional
    public boolean recordChange(Sensor seen, String next, Instant at) {
        if (sensors.changeStatus(seen.getSensorId(), seen.getStatus(), next, at) != 1) {
            return false;
        }
        SensorStatusChange change = new SensorStatusChange(UUID.randomUUID(), seen.getSensorId(), next, seen.getStatus(), at, seen.getLastTs());
        entityManager.persist(change);
        outbox.add(change.getId(), TOPIC, seen.getSensorId(), json.writeValueAsString(SensorStatusEvent.of(change)));
        return true;
    }
}
