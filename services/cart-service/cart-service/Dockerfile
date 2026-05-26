FROM eclipse-temurin:17-jre-jammy

# Place the pinned agent JAR in the image.
# Download from:
# https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/download/v2.9.0/opentelemetry-javaagent.jar
# and save as: otel/opentelemetry-javaagent-2.9.0.jar
COPY otel/opentelemetry-javaagent-2.9.0.jar /opt/otel/opentelemetry-javaagent.jar

COPY target/*.jar app.jar

# Picked up automatically by the JVM — no entrypoint script needed.
# OTEL_* configuration is injected via K8s manifest env: or docker-compose environment:.
#
# Heap sizing:
#   MaxRAMPercentage=75  → ~768 MB heap at the 1 Gi K8s limit, leaving ~256 MB for
#                          off-heap (metaspace ~100 MB, thread stacks, OTel agent buffers,
#                          Netty/Kafka direct buffers). Scales automatically if the limit changes.
#   InitialRAMPercentage=50 → JVM starts with ~512 MB to avoid GC ramp-up under first traffic.
ENV JAVA_TOOL_OPTIONS="-javaagent:/opt/otel/opentelemetry-javaagent.jar \
    -XX:MaxRAMPercentage=75.0 \
    -XX:InitialRAMPercentage=50.0"

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]
