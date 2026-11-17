package io.github.trapaeureka.skyfeed.ingest.adsb;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * One round of reading aircraft states and publishing them (R-ING-03), with the credit budget applied
 * when the source is the live API (R-ING-05). Plain Java: the source, the publisher and the clock are passed in.
 */
public final class PollCycle {

    public enum Status { STARTING, POLLING, BUDGET_EXHAUSTED, SOURCE_ERROR }

    /** Thrown by the source when the API answers that the rate limit is reached (HTTP 429). */
    public static final class RateLimitedException extends RuntimeException {
        public RateLimitedException(String message) {
            super(message);
        }
    }

    private static final Duration FORGET_AFTER = Duration.ofMinutes(10);

    private final Supplier<JsonNode> source;
    private final Consumer<AdsbState> publisher;
    private final StateVectorMapper mapper;
    private final CreditBudget budget; // null when the source costs nothing (replay)
    private final int creditsPerRequest;
    private final Clock clock;

    /** icao24 -> last_contact of the state last published, so an unchanged state is not published again. */
    private final Map<String, Seen> seen = new HashMap<>();

    private volatile Status status = Status.STARTING;
    private volatile Instant lastSuccess;
    private volatile String lastError;
    private volatile long published;

    private record Seen(String lastContact, Instant at) {}

    public PollCycle(Supplier<JsonNode> source, Consumer<AdsbState> publisher, StateVectorMapper mapper,
            CreditBudget budget, int creditsPerRequest, Clock clock) {
        this.source = source;
        this.publisher = publisher;
        this.mapper = mapper;
        this.budget = budget;
        this.creditsPerRequest = creditsPerRequest;
        this.clock = clock;
    }

    /** Never throws: a failed round is recorded in the status and the next round tries again. */
    public synchronized void run() {
        if (budget != null && !budget.trySpend(creditsPerRequest)) {
            status = Status.BUDGET_EXHAUSTED;
            return;
        }

        JsonNode response;
        try {
            response = source.get();
        } catch (RateLimitedException e) {
            if (budget != null) {
                budget.exhaust();
            }
            status = Status.BUDGET_EXHAUSTED;
            lastError = e.getMessage();
            return;
        } catch (RuntimeException e) {
            status = Status.SOURCE_ERROR;
            lastError = String.valueOf(e.getMessage());
            return;
        }

        Instant now = clock.instant();
        try {
            JsonNode states = response == null ? null : response.get("states");
            if (states != null && states.isArray()) {
                for (JsonNode state : states) {
                    mapper.map(state).ifPresent(mapped -> {
                        Seen before = seen.get(mapped.icao24());
                        if (before == null || !before.lastContact().equals(mapped.lastContact())) {
                            publisher.accept(mapped);
                            seen.put(mapped.icao24(), new Seen(mapped.lastContact(), now));
                            published++;
                        }
                    });
                }
            }
        } catch (RuntimeException e) {
            status = Status.SOURCE_ERROR;
            lastError = "publishing failed: " + e.getMessage();
            return;
        }
        seen.values().removeIf(entry -> entry.at().isBefore(now.minus(FORGET_AFTER)));
        status = Status.POLLING;
        lastSuccess = now;
        lastError = null;
    }

    public Status status() {
        return status;
    }

    public Instant lastSuccess() {
        return lastSuccess;
    }

    public String lastError() {
        return lastError;
    }

    public long published() {
        return published;
    }
}
