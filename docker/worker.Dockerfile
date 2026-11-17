FROM eclipse-temurin:21-jdk
USER root
RUN apt-get update && apt-get install -y --no-install-recommends ffmpeg && rm -rf /var/lib/apt/lists/* && mkdir -p /tmp/streaming && chown 10001:10001 /tmp/streaming
COPY target/video-streaming-platform-1.0.0-SNAPSHOT.jar /app.jar
USER 10001
ENV WORKER_ENABLED=true
ENTRYPOINT ["java","-XX:MaxRAMPercentage=70","-jar","/app.jar"]
