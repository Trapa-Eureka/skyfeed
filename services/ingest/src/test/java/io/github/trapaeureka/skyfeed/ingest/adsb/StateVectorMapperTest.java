package io.github.trapaeureka.skyfeed.ingest.adsb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.trapaeureka.skyfeed.ingest.contract.ContractSchemas;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class StateVectorMapperTest {

    static final Path RECORDING = Path.of("../../tools/simulator/fixtures/opensky-states.jsonl");
    private static final ObjectMapper JSON = new ObjectMapper();

    private final StateVectorMapper mapper = new StateVectorMapper("replay");

    private static JsonNode state(String json) throws IOException {
        return JSON.readTree(json);
    }

    @Test
    @DisplayName("R-ING-03 a state vector becomes an adsb.state.v1 message")
    void mapsAllFields() throws IOException {
        AdsbState mapped = mapper.map(state("[\"89611A\",\"UAE332  \",\"United Arab Emirates\",1794895931,1794895932,"
                + "120.5891,14.4193,2308.86,false,123.86,237.04,-2.28,null,2491.74,\"6201\",false,0]")).orElseThrow();

        assertEquals(new AdsbState("89611a", "UAE332", "United Arab Emirates", "2026-11-17T06:12:11Z", "2026-11-17T06:12:12Z",
                14.4193, 120.5891, 2308.86, 2491.74, false, 123.86, 237.04, -2.28, "6201", "replay"), mapped);
    }

    @Test
    @DisplayName("R-ING-03 a state without a position, a position time or a usable address is not published")
    void skipsUnusableStates() throws IOException {
        assertTrue(mapper.map(state("[\"abc123\",null,\"X\",null,1,null,null,null,false,null,null,null,null,null,null,false,0]")).isEmpty());
        assertTrue(mapper.map(state("[\"abc123\",null,\"X\",null,1,120.0,14.0,null,false,null,null,null,null,null,null,false,0]")).isEmpty());
        assertTrue(mapper.map(state("[\"nothex\",null,\"X\",1,1,120.0,14.0,null,false,null,null,null,null,null,null,false,0]")).isEmpty());
        assertTrue(mapper.map(state("[\"abc123\",null,\"X\",1,1,120.0,95.0,null,false,null,null,null,null,null,null,false,0]")).isEmpty());
        assertTrue(mapper.map(state("[\"abc123\"]")).isEmpty());
    }

    @Test
    @DisplayName("R-ING-03 an optional value that is out of range is dropped and the state is kept")
    void dropsBadOptionalValues() throws IOException {
        AdsbState mapped = mapper.map(state("[\"abc123\",\"        \",null,1,1,120.0,14.0,null,true,-5,400,null,null,null,\"9999\",false,0]")).orElseThrow();
        assertNull(mapped.callsign());
        assertNull(mapped.velocityMs());
        assertNull(mapped.headingDeg());
        assertNull(mapped.squawk());
        assertTrue(mapped.onGround());
    }

    @Test
    @DisplayName("R-ING-03 every aircraft in the recording maps to a message that is valid against the contract")
    void recordingMapsToValidMessages() throws IOException {
        int mapped = 0;
        for (String line : Files.readAllLines(RECORDING)) {
            for (JsonNode state : JSON.readTree(line).get("states")) {
                var message = mapper.map(state);
                if (message.isPresent()) {
                    List<String> errors = ContractSchemas.ADSB_STATE_V1.errors(JSON.valueToTree(message.get()));
                    assertTrue(errors.isEmpty(), message.get() + " -> " + errors);
                    mapped++;
                }
            }
        }
        assertTrue(mapped > 500, "only " + mapped + " states were mapped");
    }
}
