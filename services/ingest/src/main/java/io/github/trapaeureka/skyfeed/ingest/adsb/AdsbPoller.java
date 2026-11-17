package io.github.trapaeureka.skyfeed.ingest.adsb;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.rest.client.reactive.QuarkusRestClientBuilder;
import io.quarkus.scheduler.Scheduled;
import io.smallrye.reactive.messaging.MutinyEmitter;
import io.smallrye.reactive.messaging.kafka.api.OutgoingKafkaRecordMetadata;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.WebApplicationException;
import java.net.URI;
import java.time.Clock;
import java.util.concurrent.TimeUnit;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.reactive.messaging.Channel;
import org.eclipse.microprofile.reactive.messaging.Message;
import org.jboss.logging.Logger;

/**
 * Polls the ADS-B source on a schedule and publishes each aircraft state to Kafka, keyed by ICAO address.
 * The source is chosen by {@code skyfeed.adsb.source} (R-ING-04): {@code replay} reads the local replay server,
 * {@code opensky} reads the live API under a daily credit budget.
 */
@ApplicationScoped
public class AdsbPoller {

    private static final Logger LOG = Logger.getLogger(AdsbPoller.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    @ConfigProperty(name = "skyfeed.adsb.source")
    String source;

    @ConfigProperty(name = "skyfeed.adsb.replay.url")
    String replayUrl;

    @ConfigProperty(name = "skyfeed.adsb.opensky.url")
    String openSkyUrl;

    @ConfigProperty(name = "skyfeed.adsb.opensky.daily-credit-budget")
    int dailyCreditBudget;

    @ConfigProperty(name = "skyfeed.adsb.area.lamin")
    double lamin;

    @ConfigProperty(name = "skyfeed.adsb.area.lomin")
    double lomin;

    @ConfigProperty(name = "skyfeed.adsb.area.lamax")
    double lamax;

    @ConfigProperty(name = "skyfeed.adsb.area.lomax")
    double lomax;

    @Channel("adsb-out")
    MutinyEmitter<String> states;

    private PollCycle cycle;
    private CreditBudget budget;
    private PollCycle.Status logged;

    @PostConstruct
    void init() {
        boolean live = switch (source) {
            case "replay" -> false;
            case "opensky" -> true;
            default -> throw new IllegalStateException("skyfeed.adsb.source must be 'replay' or 'opensky', not '" + source + "'");
        };
        OpenSkyClient client = QuarkusRestClientBuilder.newBuilder()
                .baseUri(URI.create(live ? openSkyUrl : replayUrl))
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .build(OpenSkyClient.class);
        budget = live ? new CreditBudget(dailyCreditBudget, Clock.systemUTC()) : null;
        cycle = new PollCycle(() -> fetch(client), this::publish, new StateVectorMapper(source), budget,
                CreditBudget.creditsFor(lamin, lomin, lamax, lomax), Clock.systemUTC());
        LOG.infof("ADS-B source: %s (%s)", source, live ? openSkyUrl : replayUrl);
    }

    @Scheduled(every = "${skyfeed.adsb.poll-interval}", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void poll() {
        cycle.run();
        PollCycle.Status status = cycle.status();
        if (status != logged) { // log a change of state once, not every round
            if (status == PollCycle.Status.POLLING) {
                LOG.info("ADS-B polling is working");
            } else {
                LOG.warnf("ADS-B polling: %s%s", status, cycle.lastError() == null ? "" : " (" + cycle.lastError() + ")");
            }
            logged = status;
        }
    }

    private JsonNode fetch(OpenSkyClient client) {
        try {
            return client.states(lamin, lomin, lamax, lomax);
        } catch (WebApplicationException e) {
            if (e.getResponse() != null && e.getResponse().getStatus() == 429) {
                throw new PollCycle.RateLimitedException("the API answered 429 Too Many Requests");
            }
            throw e;
        }
    }

    private void publish(AdsbState state) {
        try {
            states.sendMessageAndAwait(Message.of(JSON.writeValueAsString(state))
                    .addMetadata(OutgoingKafkaRecordMetadata.<String>builder().withKey(state.icao24()).build()));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    PollCycle cycle() {
        return cycle;
    }

    CreditBudget budget() {
        return budget;
    }

    String source() {
        return source;
    }
}
