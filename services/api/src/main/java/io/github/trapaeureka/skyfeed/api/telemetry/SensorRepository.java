package io.github.trapaeureka.skyfeed.api.telemetry;

import java.time.Instant;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SensorRepository extends JpaRepository<Sensor, String> {

    /** Creates the sensor, or replaces its latest reading when the given one is newer. An older reading changes nothing. */
    @Modifying
    @Query(nativeQuery = true, value = """
            insert into sensor (sensor_id, lat, lon, last_ts, last_seq, temperature_c, signal_dbm, message_rate)
            values (:sensorId, :lat, :lon, :ts, :seq, :temperatureC, :signalDbm, :messageRate)
            on conflict (sensor_id) do update
               set lat = excluded.lat, lon = excluded.lon, last_ts = excluded.last_ts, last_seq = excluded.last_seq,
                   temperature_c = excluded.temperature_c, signal_dbm = excluded.signal_dbm,
                   message_rate = excluded.message_rate, updated_at = now()
             where excluded.last_ts > sensor.last_ts
                or (excluded.last_ts = sensor.last_ts and excluded.last_seq > sensor.last_seq)
            """)
    int upsertLatest(@Param("sensorId") String sensorId, @Param("lat") double lat, @Param("lon") double lon,
            @Param("ts") Instant ts, @Param("seq") long seq, @Param("temperatureC") double temperatureC,
            @Param("signalDbm") double signalDbm, @Param("messageRate") double messageRate);

    List<Sensor> findAllByOrderBySensorIdAsc();
}
