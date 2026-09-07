# Multi-stage build for Kotlin Spring Boot application

# Stage 1: Build the application
FROM eclipse-temurin:17-jdk-jammy AS builder

WORKDIR /app

# Copy gradle files first for optimal caching
COPY gradlew settings.gradle.kts build.gradle.kts /app/
COPY gradle /app/gradle

# Download dependencies
RUN ./gradlew dependencies --no-daemon

# Copy source code and build executable jar
COPY src /app/src
RUN ./gradlew bootJar --no-daemon -x test

# Stage 2: Minimal runtime image
FROM eclipse-temurin:17-jre-jammy

WORKDIR /app

# Run as non-root user for security
RUN addgroup --system spring && adduser --system --ingroup spring spring
USER spring:spring

COPY --from=builder /app/build/libs/*.jar app.jar

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
