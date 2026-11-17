package io.github.trapaeureka.skyfeed.api.status;

import io.github.trapaeureka.skyfeed.api.config.SkyfeedProperties;
import java.time.Duration;
import org.springframework.stereotype.Component;

/** The one rule for a sensor's status: online, degraded after one threshold without a reading, offline after the other. */
@Component
public class SensorStatusRule {

    private final Duration degradedAfter;
    private final Duration offlineAfter;

    SensorStatusRule(SkyfeedProperties properties) {
        this.degradedAfter = properties.sensor().degradedAfter();
        this.offlineAfter = properties.sensor().offlineAfter();
    }

    /** @param age time since the sensor's latest reading was taken */
    public String statusFor(Duration age) {
        if (age.compareTo(degradedAfter) < 0) {
            return "online";
        }
        return age.compareTo(offlineAfter) < 0 ? "degraded" : "offline";
    }
}
