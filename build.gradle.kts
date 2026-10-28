plugins {
    id("org.springframework.boot") version "3.3.3"
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

    // Logback Logging Framework
    implementation("ch.qos.logback:logback-classic:1.5.7")

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
