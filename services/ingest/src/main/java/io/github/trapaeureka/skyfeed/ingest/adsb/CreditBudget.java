package io.github.trapaeureka.skyfeed.ingest.adsb;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;

/**
 * Counts the API credits spent per UTC day and refuses to go over the configured budget (R-ING-05).
 * The count lives in memory: it starts at zero when the service starts.
 */
public final class CreditBudget {

    private final int dailyBudget;
    private final Clock clock;
    private LocalDate day;
    private int used;

    public CreditBudget(int dailyBudget, Clock clock) {
        if (dailyBudget < 0) {
            throw new IllegalArgumentException("daily credit budget must not be negative");
        }
        this.dailyBudget = dailyBudget;
        this.clock = clock;
        this.day = today();
    }

    /**
     * What one request for the given area costs, in credits, by the area in square degrees:
     * up to 25 costs 1, up to 100 costs 2, up to 400 costs 3, larger costs 4 (OpenSky REST API documentation).
     */
    public static int creditsFor(double lamin, double lomin, double lamax, double lomax) {
        double squareDegrees = Math.abs(lamax - lamin) * Math.abs(lomax - lomin);
        if (squareDegrees <= 25) {
            return 1;
        }
        if (squareDegrees <= 100) {
            return 2;
        }
        return squareDegrees <= 400 ? 3 : 4;
    }

    /** Spends the credits and returns true, or returns false and spends nothing when that would exceed the budget. */
    public synchronized boolean trySpend(int credits) {
        rollOver();
        if (used + credits > dailyBudget) {
            return false;
        }
        used += credits;
        return true;
    }

    /** Called when the API itself says the limit is reached: nothing more is spent until the next UTC day. */
    public synchronized void exhaust() {
        rollOver();
        used = Math.max(used, dailyBudget);
    }

    public synchronized int used() {
        rollOver();
        return used;
    }

    public int dailyBudget() {
        return dailyBudget;
    }

    private void rollOver() {
        LocalDate today = today();
        if (!today.equals(day)) {
            day = today;
            used = 0;
        }
    }

    private LocalDate today() {
        return LocalDate.now(clock.withZone(ZoneOffset.UTC));
    }
}
