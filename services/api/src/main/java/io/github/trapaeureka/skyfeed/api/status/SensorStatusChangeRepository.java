package io.github.trapaeureka.skyfeed.api.status;

import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SensorStatusChangeRepository extends JpaRepository<SensorStatusChange, UUID> {

    List<SensorStatusChange> findAllByOrderByChangedAtDesc(Limit limit);

    List<SensorStatusChange> findBySensorIdOrderByChangedAtAsc(String sensorId);
}
