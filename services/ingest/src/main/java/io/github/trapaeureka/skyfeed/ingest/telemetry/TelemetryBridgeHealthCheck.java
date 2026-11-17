package io.github.trapaeureka.skyfeed.ingest.telemetry;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.Readiness;

/** Shows how many readings were forwarded, rejected to the dead-letter topic, or could not be written. */
@Readiness
@ApplicationScoped
public class TelemetryBridgeHealthCheck implements HealthCheck {

    @Inject
    TelemetryBridge bridge;

    @Override
    public HealthCheckResponse call() {
        return HealthCheckResponse.named("telemetry-bridge")
                .up()
                .withData("forwarded", bridge.forwarded())
                .withData("rejected", bridge.rejected())
                .withData("failed", bridge.failed())
                .build();
    }
}
