# syntax=docker/dockerfile:1
# Build context is the repository root, like the other services (the tests read the contracts in /contracts).
FROM eclipse-temurin:21-jdk AS build
WORKDIR /repo
COPY contracts contracts
COPY services/gateway/.mvn services/gateway/.mvn
COPY services/gateway/mvnw services/gateway/pom.xml services/gateway/
WORKDIR /repo/services/gateway
# Tests are run by scripts/verify.sh, not while building the image.
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -q dependency:go-offline -DskipTests || true
COPY services/gateway/src src
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -q package -DskipTests

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /repo/services/gateway/target/quarkus-app/ ./
USER 1001
EXPOSE 8082
ENV QUARKUS_HTTP_HOST=0.0.0.0
CMD ["java", "-jar", "quarkus-run.jar"]
