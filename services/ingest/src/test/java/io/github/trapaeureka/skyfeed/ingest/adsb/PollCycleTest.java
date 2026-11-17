package io.github.trapaeureka.skyfeed.ingest.adsb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.trapaeureka.skyfeed.ingest.adsb.CreditBudgetTest.TestClock;
import io.github.trapaeureka.skyfeed.ingest.adsb.PollCycle.Status;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PollCycleTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final TestClock clock = new TestClock("2026-11-17T10:00:00Z");
    private final List<AdsbState> published = new ArrayList<>();
    private int requests;

    private static JsonNode frame(long lastContact) {
        try {
            return JSON.readTree("{\"time\":" + lastContact + ",\"states\":["
                    + "[\"abc123\",\"TEST1\",\"X\"," + lastContact + "," + lastContact + ",120.0,14.0,1000,false,200,90,0,null,1100,\"1200\",false,0],"
                    + "[\"def456\",null,\"X\",null," + lastContact + ",null,null,null,false,null,null,null,null,null,null,false,0]]}");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private PollCycle cycle(Supplier<JsonNode> source, CreditBudget budget) {
        return new PollCycle(() -> {
            requests++;
            return source.get();
        }, published::add, new StateVectorMapper(budget == null ? "replay" : "opensky"), budget, 1, clock);
    }

    @Test
    @DisplayName("R-ING-03 each aircraft with a position is published; one without is not")
    void publishesStates() {
        PollCycle cycle = cycle(() -> frame(1000), null);
        cycle.run();

        assertEquals(1, published.size());
        assertEquals("abc123", published.get(0).icao24());
        assertEquals("replay", published.get(0).source());
        assertEquals(Status.POLLING, cycle.status());
        assertNotNull(cycle.lastSuccess());
    }

    @Test
    @DisplayName("R-ING-03 a state that did not change since the last round is not published again")
    void skipsUnchangedStates() {
        long[] lastContact = {1000};
        PollCycle cycle = cycle(() -> frame(lastContact[0]), null);
        cycle.run();
        cycle.run();
        assertEquals(1, published.size());

        lastContact[0] = 1010;
        cycle.run();
        assertEquals(2, published.size());
    }

    @Test
    @DisplayName("R-ING-05 with the budget used up no request is made and the status says so")
    void stopsRequestingWhenBudgetIsUsedUp() {
        PollCycle cycle = cycle(() -> frame(1000), new CreditBudget(2, clock));
        for (int i = 0; i < 5; i++) {
            cycle.run();
        }
        assertEquals(2, requests);
        assertEquals(Status.BUDGET_EXHAUSTED, cycle.status());

        clock.advance(Duration.ofDays(1));
        cycle.run();
        assertEquals(3, requests);
        assertEquals(Status.POLLING, cycle.status());
    }

    @Test
    @DisplayName("R-ING-05 a 429 from the API stops requests for the rest of the day")
    void rateLimitAnswerExhaustsTheBudget() {
        PollCycle cycle = cycle(() -> {
            throw new PollCycle.RateLimitedException("the API answered 429 Too Many Requests");
        }, new CreditBudget(100, clock));
        cycle.run();
        cycle.run();
        cycle.run();

        assertEquals(1, requests);
        assertEquals(Status.BUDGET_EXHAUSTED, cycle.status());
    }

    @Test
    @DisplayName("R-ING-04 the replay source is not limited by a credit budget")
    void replayHasNoBudget() {
        PollCycle cycle = cycle(() -> frame(1000), null);
        for (int i = 0; i < 1000; i++) {
            cycle.run();
        }
        assertEquals(1000, requests);
    }

    @Test
    @DisplayName("a source error is recorded and the next round recovers")
    void recoversFromSourceError() {
        boolean[] broken = {true};
        PollCycle cycle = cycle(() -> {
            if (broken[0]) {
                throw new IllegalStateException("connection refused");
            }
            return frame(1000);
        }, null);

        cycle.run();
        assertEquals(Status.SOURCE_ERROR, cycle.status());
        assertEquals("connection refused", cycle.lastError());

        broken[0] = false;
        cycle.run();
        assertEquals(Status.POLLING, cycle.status());
        assertNull(cycle.lastError());
        assertEquals(1, published.size());
    }
}
