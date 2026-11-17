# syntax=docker/dockerfile:1
# Build context is services/api.
FROM eclipse-temurin:21-jdk AS build
WORKDIR /app
COPY .mvn .mvn
COPY mvnw pom.xml ./
# Tests are run by scripts/verify.sh, not while building the image.
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -q dependency:go-offline -DskipTests || true
COPY src src
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -q package -DskipTests && cp target/api-*.jar app.jar

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/app.jar app.jar
USER 1001
EXPOSE 8080
CMD ["java", "-jar", "app.jar"]
