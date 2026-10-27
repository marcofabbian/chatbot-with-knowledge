FROM mcr.microsoft.com/devcontainers/java:1-17-bookworm AS builder

WORKDIR /app
COPY . .
RUN ./gradlew bootJar --no-daemon

FROM mcr.microsoft.com/devcontainers/java:1-17-bookworm

WORKDIR /app
RUN addgroup --system spring && adduser --system --ingroup spring spring
COPY --from=builder /app/build/libs/*.jar app.jar
USER spring:spring

EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=3s --start-period=30s --retries=3 \
  CMD curl -fs http://localhost:8080/actuator/health || exit 1

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
