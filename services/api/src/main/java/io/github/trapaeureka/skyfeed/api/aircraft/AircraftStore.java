package io.github.trapaeureka.skyfeed.api.aircraft;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AircraftStore {

    private final AircraftPositionRepository positions;
    private final AircraftRepository aircraft;

    AircraftStore(AircraftPositionRepository positions, AircraftRepository aircraft) {
        this.positions = positions;
        this.aircraft = aircraft;
    }

    /**
     * Stores one aircraft state and brings the aircraft's latest state up to date, in one transaction (R-API-01).
     * Storing the same state again changes nothing (R-API-02).
     *
     * @return true if the state was new, false if it was already stored
     */
    @Transactional
    public boolean store(AdsbStateMessage message) {
        boolean isNew = positions.insertIfAbsent(message) == 1;
        aircraft.upsertLatest(message);
        return isNew;
    }
}
