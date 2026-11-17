package io.github.trapaeureka.skyfeed.ingest.adsb;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.QueryParam;

/** The one OpenSky REST call this service uses. The local replay server answers the same call. */
@Path("/api")
public interface OpenSkyClient {

    @GET
    @Path("/states/all")
    JsonNode states(@QueryParam("lamin") double lamin, @QueryParam("lomin") double lomin,
            @QueryParam("lamax") double lamax, @QueryParam("lomax") double lomax);
}
