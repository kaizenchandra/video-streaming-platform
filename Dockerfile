FROM maven:3.9.11-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
COPY src src
RUN --mount=type=cache,target=/root/.m2,sharing=locked mvn -B -DskipTests package
FROM eclipse-temurin:21-jre AS api
WORKDIR /app
COPY --from=build /build/target/*.jar app.jar
USER 10001
ENTRYPOINT ["java","-XX:MaxRAMPercentage=70","-jar","/app/app.jar"]
FROM eclipse-temurin:21-jre AS worker-base
USER root
RUN apt-get update && apt-get install -y --no-install-recommends ffmpeg && rm -rf /var/lib/apt/lists/* && mkdir -p /tmp/streaming && chown 10001:10001 /tmp/streaming
FROM worker-base AS worker
WORKDIR /app
COPY --from=build /build/target/*.jar app.jar
USER 10001
ENV WORKER_ENABLED=true
ENTRYPOINT ["java","-XX:MaxRAMPercentage=70","-jar","/app/app.jar"]
