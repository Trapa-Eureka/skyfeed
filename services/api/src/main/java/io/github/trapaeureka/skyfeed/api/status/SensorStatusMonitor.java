package io.github.trapaeureka.skyfeed.api.status;

import io.github.trapaeureka.skyfeed.api.telemetry.Sensor;
import io.github.trapaeureka.skyfeed.api.telemetry.SensorRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Looks at every sensor at a fixed interval and reports each change of status: a sensor that has sent nothing for
 * the configured time becomes degraded and then offline, and online again with its next reading (R-OPS-01).
 */
@Component
class SensorStatusMonitor {

    private static final Logger LOG = LoggerFactory.getLogger(SensorStatusMonitor.class);

    private final SensorRepository sensors;
    private final SensorStatusRule rule;
    private final SensorStatusService service;
    private final Clock clock;

    SensorStatusMonitor(SensorRepository sensors, SensorStatusRule rule, SensorStatusService service, Clock clock) {
        this.sensors = sensors;
        this.rule = rule;
        this.service = service;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${skyfeed.sensor.check-interval}")
    void check() {
        try {
            Instant now = clock.instant();
            for (Sensor sensor : sensors.findAll()) {
                String status = rule.statusFor(Duration.between(sensor.getLastTs(), now));
                if (!status.equals(sensor.getStatus()) && service.recordChange(sensor, status, now)) {
                    LOG.info("Sensor {} is now {} (was {})", sensor.getSensorId(), status, sensor.getStatus());
                }
            }
        } catch (RuntimeException e) {
            // The database being away must not end the schedule: the next round tries again.
            LOG.warn("Sensor status check failed, will try again: {}", e.getMessage());
        }
    }
}
