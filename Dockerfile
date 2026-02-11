FROM gradle:8.8.0-jdk21 AS build
WORKDIR /app
COPY . .
RUN ./gradlew clean build

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/build/libs/*.jar /app/stravahooks.jar

ENV STRAVAHOOKS_CONFIG=/app/stravahooks.json
EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/app/stravahooks.jar", "run"]
