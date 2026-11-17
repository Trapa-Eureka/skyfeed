package io.github.trapaeureka.skyfeed.ingest.adsb;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.HealthCheckResponseBuilder;
import org.eclipse.microprofile.health.Readiness;

/**
 * Reports the state of ADS-B polling, including a used-up credit budget (R-ING-05). It always answers UP:
 * a paused aircraft feed is not a reason to take the service, and with it the telemetry bridge, out of rotation.
 */
@Readiness
@ApplicationScoped
public class AdsbSourceHealthCheck implements HealthCheck {

    @Inject
    AdsbPoller poller;

    @Override
    public HealthCheckResponse call() {
        PollCycle cycle = poller.cycle();
        HealthCheckResponseBuilder response = HealthCheckResponse.named("adsb-source")
                .up()
                .withData("source", poller.source())
                .withData("status", cycle.status().name())
                .withData("published", cycle.published());
        if (cycle.lastSuccess() != null) {
            response.withData("lastSuccess", cycle.lastSuccess().toString());
        }
        if (cycle.lastError() != null) {
            response.withData("lastError", cycle.lastError());
        }
        if (poller.budget() != null) {
            response.withData("creditsUsedToday", poller.budget().used())
                    .withData("dailyCreditBudget", poller.budget().dailyBudget());
        }
        return response.build();
    }
}
