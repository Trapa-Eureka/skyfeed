package io.github.trapaeureka.skyfeed.api.aircraft;

import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AircraftPositionRepository extends JpaRepository<AircraftPosition, Long> {

    /** Stores the state unless the same (aircraft, last contact) is already there. Returns 1 if it was new, 0 if not. */
    @Modifying
    @Query(nativeQuery = true, value = """
            insert into aircraft_position (icao24, ts, last_contact, lat, lon, baro_altitude_m, geo_altitude_m,
                                           on_ground, velocity_ms, heading_deg, vertical_rate_ms)
            values (:#{#m.icao24}, :#{#m.ts}, :#{#m.lastContact}, :#{#m.lat}, :#{#m.lon},
                    cast(:#{#m.baroAltitudeM} as double precision), cast(:#{#m.geoAltitudeM} as double precision),
                    :#{#m.onGround}, cast(:#{#m.velocityMs} as double precision),
                    cast(:#{#m.headingDeg} as double precision), cast(:#{#m.verticalRateMs} as double precision))
            on conflict (icao24, last_contact) do nothing
            """)
    int insertIfAbsent(@Param("m") AdsbStateMessage message);

    /** The newest positions since the given time, newest first, at most {@code limit}. */
    List<AircraftPosition> findByIcao24AndTsGreaterThanEqualOrderByTsDesc(String icao24, Instant since, Limit limit);

    long countByIcao24(String icao24);
}
