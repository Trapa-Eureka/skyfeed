package io.github.trapaeureka.skyfeed.api.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.util.backoff.ExponentialBackOff;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
@EnableConfigurationProperties(SkyfeedProperties.class)
public class AppConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    /**
     * When storing a record fails (for example the database is away), the same record is tried again, waiting
     * longer each time up to 10 seconds, for as long as it takes. Nothing is skipped, and because storing is
     * idempotent a record that was in fact stored before the failure does no harm when it is tried again.
     * Records that can never be stored (not JSON, fields missing) are not errors here: the listeners drop and count them.
     */
    @Bean
    DefaultErrorHandler kafkaErrorHandler() {
        ExponentialBackOff backOff = new ExponentialBackOff(500, 2.0);
        backOff.setMaxInterval(10_000);
        return new DefaultErrorHandler(backOff);
    }

    @Bean
    OpenAPI openApi() {
        return new OpenAPI().info(new Info()
                .title("SkyFeed API")
                .version("v1")
                .description("Sensors with their telemetry, and aircraft with their tracks, as stored from the Kafka topics telemetry.v1 and adsb.state.v1."));
    }
}
