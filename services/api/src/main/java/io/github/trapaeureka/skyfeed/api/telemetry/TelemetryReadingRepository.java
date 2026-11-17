package io.github.trapaeureka.skyfeed.api.telemetry;

import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TelemetryReadingRepository extends JpaRepository<TelemetryReading, Long> {

    /** Stores the reading unless the same (sensor, time, sequence number) is already there. Returns 1 if it was new, 0 if not. */
    @Modifying
    @Query(nativeQuery = true, value = """
            insert into telemetry_reading (sensor_id, ts, seq, temperature_c, signal_dbm, message_rate)
            values (:sensorId, :ts, :seq, :temperatureC, :signalDbm, :messageRate)
            on conflict (sensor_id, ts, seq) do nothing
            """)
    int insertIfAbsent(@Param("sensorId") String sensorId, @Param("ts") Instant ts, @Param("seq") long seq,
            @Param("temperatureC") double temperatureC, @Param("signalDbm") double signalDbm,
            @Param("messageRate") double messageRate);

    /** The newest readings in the time range, newest first, at most {@code limit}. */
    List<TelemetryReading> findBySensorIdAndTsBetweenOrderByTsDescSeqDesc(String sensorId, Instant from, Instant to, Limit limit);

    long countBySensorId(String sensorId);
}
