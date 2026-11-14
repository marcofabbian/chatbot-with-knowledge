plugins {
    id("org.springframework.boot") version "3.5.14"
    id("io.spring.dependency-management") version "1.1.6"
    kotlin("jvm") version "2.4.21"
    kotlin("plugin.spring") version "2.4.21"
}

group = "com.marcofabbian.chatbot"
version = "0.0.1-SNAPSHOT"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

repositories {
    mavenCentral()
}

extra["tomcat.version"] = "10.1.61"

dependencies {
    // Spring Boot Starter
    implementation("org.springframework.boot:spring-boot-starter")

    // Spring Boot Web Starter (serves the chatbot page)
    implementation("org.springframework.boot:spring-boot-starter-web")

    // Actuator: /actuator/health for liveness/readiness checks
    implementation("org.springframework.boot:spring-boot-starter-actuator")

    // JSR-380 bean validation for request DTOs
    implementation("org.springframework.boot:spring-boot-starter-validation")

    // Kotlin reflection support
    implementation("org.jetbrains.kotlin:kotlin-reflect")

    // Parse the Project Gutenberg HTML into page-aware knowledge passages.
    implementation("org.jsoup:jsoup:1.23.2")

    // Bounded, expiring per-client rate limit buckets (version managed by Spring Boot).
    implementation("com.github.ben-manes.caffeine:caffeine")

    // Logback Logging Framework
    implementation("ch.qos.logback:logback-classic")

    // Jackson and Micrometer dependencies
    constraints {
        implementation("com.fasterxml.jackson.core:jackson-core:2.21.7")
        implementation("com.fasterxml.jackson.core:jackson-databind:2.21.7")
        implementation("io.micrometer:micrometer-core:1.15.12")
        implementation("org.springframework:spring-expression:6.2.19")
        implementation("org.springframework:spring-webmvc:6.2.19")
    }

    // Test dependencies with JUnit Jupiter
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test> {
    useJUnitPlatform()
}

// Only the executable boot jar is needed; disabling the plain jar keeps build/libs/*.jar to one file for the Dockerfile COPY.
tasks.named<Jar>("jar") {
    enabled = false
}
