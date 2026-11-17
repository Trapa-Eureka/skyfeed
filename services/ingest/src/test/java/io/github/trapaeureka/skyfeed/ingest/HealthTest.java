package io.github.trapaeureka.skyfeed.ingest;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.is;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

@QuarkusTest
class HealthTest {

    @Test
    void readinessReportsUp() {
        given().when().get("/q/health/ready").then().statusCode(200).body("status", is("UP"));
    }
}
