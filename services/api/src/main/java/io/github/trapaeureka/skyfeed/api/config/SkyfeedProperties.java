package io.github.trapaeureka.skyfeed.api.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("skyfeed")
public record SkyfeedProperties(Sensor sensor, Aircraft aircraft) {

    public record Sensor(Duration degradedAfter, Duration offlineAfter, Duration checkInterval) {}

    public record Aircraft(Duration currentWithin) {}
}
