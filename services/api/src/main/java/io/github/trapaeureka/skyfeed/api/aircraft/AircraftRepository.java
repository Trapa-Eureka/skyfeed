package io.github.trapaeureka.skyfeed.api.aircraft;

import java.time.Instant;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AircraftRepository extends JpaRepository<Aircraft, String> {

    /** Creates the aircraft, or replaces its latest state when the given one is newer. An older state changes nothing. */
    @Modifying
    @Query(nativeQuery = true, value = """
            insert into aircraft (icao24, callsign, origin_country, ts, last_contact, lat, lon, baro_altitude_m,
                                  geo_altitude_m, on_ground, velocity_ms, heading_deg, vertical_rate_ms, squawk, source)
            values (:#{#m.icao24}, cast(:#{#m.callsign} as text), cast(:#{#m.originCountry} as text), :#{#m.ts},
                    :#{#m.lastContact}, :#{#m.lat}, :#{#m.lon},
                    cast(:#{#m.baroAltitudeM} as double precision), cast(:#{#m.geoAltitudeM} as double precision),
                    :#{#m.onGround}, cast(:#{#m.velocityMs} as double precision),
                    cast(:#{#m.headingDeg} as double precision), cast(:#{#m.verticalRateMs} as double precision),
                    cast(:#{#m.squawk} as text), :#{#m.source})
            on conflict (icao24) do update
               set callsign = excluded.callsign, origin_country = excluded.origin_country, ts = excluded.ts,
                   last_contact = excluded.last_contact, lat = excluded.lat, lon = excluded.lon,
                   baro_altitude_m = excluded.baro_altitude_m, geo_altitude_m = excluded.geo_altitude_m,
                   on_ground = excluded.on_ground, velocity_ms = excluded.velocity_ms,
                   heading_deg = excluded.heading_deg, vertical_rate_ms = excluded.vertical_rate_ms,
                   squawk = excluded.squawk, source = excluded.source, updated_at = now()
             where excluded.last_contact > aircraft.last_contact
            """)
    int upsertLatest(@Param("m") AdsbStateMessage message);

    List<Aircraft> findByLastContactGreaterThanEqualOrderByIcao24Asc(Instant since);
}
