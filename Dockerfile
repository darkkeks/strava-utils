FROM gradle:8.8.0-jdk21 AS build
WORKDIR /app
COPY gradle gradle
COPY gradlew gradlew
COPY gradlew.bat gradlew.bat
COPY build.gradle.kts build.gradle.kts
COPY settings.gradle.kts settings.gradle.kts
RUN ./gradlew --no-daemon clean installDist -x test
COPY . .
RUN ./gradlew --no-daemon installDist -x test

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/build/install/strava-utils /app

ENV STRAVAHOOKS_CONFIG=/app/stravahooks.json
EXPOSE 8080

ENTRYPOINT ["/app/bin/strava-utils", "run"]
