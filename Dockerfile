FROM eclipse-temurin:17-jdk@sha256:5465815640e10433422217335e15a523a3e57021a1f2ae0fc3849380d237979f AS builder

WORKDIR /app
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle gradle
COPY src src
RUN ./gradlew bootJar --no-daemon

FROM eclipse-temurin:17-jre@sha256:0b6521287f850ec1c4b31c9074c5a1ae1d67c5922c3fefd21065c7c32e244d4a

WORKDIR /app
RUN apt-get update \
    && apt-get upgrade -y --no-install-recommends \
    && rm -rf /var/lib/apt/lists/* \
    && addgroup --system spring && adduser --system --ingroup spring spring
COPY --from=builder /app/build/libs/*.jar app.jar
USER spring:spring

EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=3s --start-period=30s --retries=3 \
  CMD bash -c 'exec 3<>/dev/tcp/127.0.0.1/8080 && printf "GET /actuator/health/liveness HTTP/1.0\r\n\r\n" >&3 && head -n1 <&3 | grep -q " 200 "' || exit 1

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
