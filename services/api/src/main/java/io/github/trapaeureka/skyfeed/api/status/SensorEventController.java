package io.github.trapaeureka.skyfeed.api.status;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.data.domain.Limit;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/sensor-events")
@Tag(name = "Sensors")
class SensorEventController {

    private final SensorStatusChangeRepository changes;

    SensorEventController(SensorStatusChangeRepository changes) {
        this.changes = changes;
    }

    @GetMapping
    @Operation(summary = "The most recent changes of sensor status, newest first")
    List<SensorStatusEvent> recent(@Parameter(description = "Most events to return, 1 to 200.") @RequestParam(defaultValue = "50") int limit) {
        if (limit < 1 || limit > 200) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "limit must be between 1 and 200");
        }
        return changes.findAllByOrderByChangedAtDesc(Limit.of(limit)).stream().map(SensorStatusEvent::of).toList();
    }
}
