package io.github.trapaeureka.skyfeed.gateway;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.Readiness;

/** Shows how many clients are connected, how much was sent, and how much a slow client was spared. */
@Readiness
@ApplicationScoped
public class LiveFeedHealthCheck implements HealthCheck {

    @Inject
    LiveFeed feed;

    @Override
    public HealthCheckResponse call() {
        long sent = 0;
        long replaced = 0;
        long waiting = 0;
        for (ClientSession session : feed.sessions()) {
            sent += session.sent();
            replaced += session.replaced();
            waiting += session.pending();
        }
        return HealthCheckResponse.named("live-feed")
                .up()
                .withData("clients", feed.sessions().size())
                .withData("sentToCurrentClients", sent)
                .withData("replacedByNewer", replaced)
                .withData("waiting", waiting)
                .withData("undeliverableRecords", feed.undeliverable())
                .build();
    }
}
