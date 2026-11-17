package io.github.trapaeureka.skyfeed.ingest.contract;

import com.fasterxml.jackson.databind.JsonNode;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.List;

/**
 * The message contracts from the repository's {@code /contracts} folder, packaged into this service at build time.
 */
public final class ContractSchemas {

    public static final ContractSchemas TELEMETRY_V1 = new ContractSchemas("telemetry.v1.schema.json");
    public static final ContractSchemas ADSB_STATE_V1 = new ContractSchemas("adsb.state.v1.schema.json");

    private final Schema schema;

    private ContractSchemas(String file) {
        String resource = "/contracts/" + file;
        try (InputStream in = ContractSchemas.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("Contract schema is missing from the classpath: " + resource);
            }
            this.schema = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12).getSchema(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Returns what is wrong with the message; an empty list means it is valid. Formats such as date-time are enforced. */
    public List<String> errors(JsonNode message) {
        return schema.validate(message, context -> context.executionConfig(config -> config.formatAssertionsEnabled(true)))
                .stream()
                .map(error -> error.getInstanceLocation() + ": " + error.getMessage())
                .toList();
    }
}
