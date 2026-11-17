package io.github.trapaeureka.skyfeed.api.telemetry;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TelemetryStore {

    private final TelemetryReadingRepository readings;
    private final SensorRepository sensors;

    TelemetryStore(TelemetryReadingRepository readings, SensorRepository sensors) {
        this.readings = readings;
        this.sensors = sensors;
    }

    /**
     * Stores one reading and brings the sensor's latest reading up to date, in one transaction (R-API-01).
     * Storing the same reading again changes nothing (R-API-02).
     *
     * @return true if the reading was new, false if it was already stored
     */
    @Transactional
    public boolean store(TelemetryMessage message) {
        boolean isNew = readings.insertIfAbsent(message.sensorId(), message.ts(), message.seq(),
                message.temperatureC(), message.signalDbm(), message.messageRate()) == 1;
        sensors.upsertLatest(message.sensorId(), message.lat(), message.lon(), message.ts(), message.seq(),
                message.temperatureC(), message.signalDbm(), message.messageRate());
        return isNew;
    }
}
