# syntax=docker/dockerfile:1
# Build context is the repository root: the build packages the message contracts from /contracts.
FROM eclipse-temurin:21-jdk AS build
# The Maven wrapper checks the Maven download against a checksum of the .zip. Without unzip it quietly fetches
# the .tar.gz instead, and the check fails. The base image does not promise to ship unzip, so install it.
RUN apt-get update && apt-get install -y --no-install-recommends unzip
WORKDIR /repo
COPY contracts contracts
COPY services/ingest/.mvn services/ingest/.mvn
COPY services/ingest/mvnw services/ingest/pom.xml services/ingest/
WORKDIR /repo/services/ingest
# Tests are run by scripts/verify.sh, not while building the image.
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -q dependency:go-offline -DskipTests || true
COPY services/ingest/src src
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -q package -DskipTests

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /repo/services/ingest/target/quarkus-app/ ./
USER 1001
EXPOSE 8081
ENV QUARKUS_HTTP_HOST=0.0.0.0
CMD ["java", "-jar", "quarkus-run.jar"]
