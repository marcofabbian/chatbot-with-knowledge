FROM eclipse-temurin:22-jdk-jammy AS builder

WORKDIR /app
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle gradle
COPY src src
RUN ./gradlew bootJar --no-daemon

FROM eclipse-temurin:22-jre-jammy

WORKDIR /app
RUN apt-get update \
    && apt-get -y upgrade \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && addgroup --system spring && adduser --system --ingroup spring spring
COPY --from=builder /app/build/libs/*.jar app.jar
USER spring:spring

EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=3s --start-period=30s --retries=3 \
  CMD curl -fs http://localhost:8080/actuator/health || exit 1

ENTRYPOINT ["java", "-jar", "/app/app.jar"]