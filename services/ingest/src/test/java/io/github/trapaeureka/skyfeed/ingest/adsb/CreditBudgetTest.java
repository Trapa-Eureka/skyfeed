package io.github.trapaeureka.skyfeed.ingest.adsb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CreditBudgetTest {

    /** A clock the test moves by hand. */
    static final class TestClock extends Clock {
        Instant now;

        TestClock(String start) {
            now = Instant.parse(start);
        }

        void advance(Duration by) {
            now = now.plus(by);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return Clock.fixed(now, zone);
        }
    }

    @Test
    @DisplayName("R-ING-05 spending stops exactly at the daily budget")
    void stopsAtBudget() {
        CreditBudget budget = new CreditBudget(3, new TestClock("2026-11-17T10:00:00Z"));
        assertTrue(budget.trySpend(1));
        assertTrue(budget.trySpend(2));
        assertFalse(budget.trySpend(1));
        assertEquals(3, budget.used());
    }

    @Test
    @DisplayName("R-ING-05 a request that would go over the budget spends nothing")
    void refusesPartialOverrun() {
        CreditBudget budget = new CreditBudget(3, new TestClock("2026-11-17T10:00:00Z"));
        assertTrue(budget.trySpend(2));
        assertFalse(budget.trySpend(2));
        assertEquals(2, budget.used());
    }

    @Test
    @DisplayName("R-ING-05 the budget is available again on the next UTC day")
    void resetsAtUtcMidnight() {
        TestClock clock = new TestClock("2026-11-17T23:59:00Z");
        CreditBudget budget = new CreditBudget(1, clock);
        assertTrue(budget.trySpend(1));
        assertFalse(budget.trySpend(1));
        clock.advance(Duration.ofMinutes(2));
        assertEquals(0, budget.used());
        assertTrue(budget.trySpend(1));
    }

    @Test
    @DisplayName("R-ING-05 when the API reports its limit, nothing more is spent that day")
    void exhaustBlocksTheRestOfTheDay() {
        CreditBudget budget = new CreditBudget(100, new TestClock("2026-11-17T10:00:00Z"));
        assertTrue(budget.trySpend(1));
        budget.exhaust();
        assertFalse(budget.trySpend(1));
    }

    @Test
    @DisplayName("R-ING-05 the cost of a request follows the size of the map area")
    void costByArea() {
        assertEquals(1, CreditBudget.creditsFor(10, 119.5, 15, 124.5)); // 25 square degrees
        assertEquals(2, CreditBudget.creditsFor(5, 115, 15, 125)); // 100
        assertEquals(3, CreditBudget.creditsFor(0, 110, 20, 130)); // 400
        assertEquals(4, CreditBudget.creditsFor(-90, -180, 90, 180));
    }
}
