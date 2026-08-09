plugins {
	kotlin("jvm") version "2.3.21"
	kotlin("plugin.spring") version "2.3.21"
	id("org.springframework.boot") version "4.1.0"
	id("io.spring.dependency-management") version "1.1.7"
}

val exposedVersion = "1.4.0"
val testcontainersVersion = "2.0.5"

group = "dev.kanso"
version = "0.1.0"
description = "Kanso API — Postgres is the source of truth, Notion is an async mirror"

java {
	toolchain {
		languageVersion = JavaLanguageVersion.of(21)
	}
}

repositories {
	mavenCentral()
}

dependencies {
	implementation("org.springframework.boot:spring-boot-starter-actuator")
	implementation("org.springframework.boot:spring-boot-starter-flyway")
	implementation("org.springframework.boot:spring-boot-starter-jdbc")
	implementation("org.springframework.boot:spring-boot-starter-security-oauth2-client")
	implementation("org.springframework.boot:spring-boot-starter-validation")
	implementation("org.springframework.boot:spring-boot-starter-webmvc")
	implementation("org.springframework.boot:spring-boot-starter-websocket")
	implementation("org.flywaydb:flyway-database-postgresql")
	implementation("org.jetbrains.kotlin:kotlin-reflect")
	implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core")

	// Exposed owns the CRUD. Flyway owns the schema, so no DDL generation here.
	// A few statements Exposed cannot express (recursive CTE, UPDATE ... FROM
	// with SKIP LOCKED, ON CONFLICT on a partial index) go through Spring's
	// JdbcClient on the same connection, inside the same Spring transaction.
	implementation("org.jetbrains.exposed:exposed-core:$exposedVersion")
	implementation("org.jetbrains.exposed:exposed-jdbc:$exposedVersion")
	implementation("org.jetbrains.exposed:exposed-java-time:$exposedVersion")
	implementation("org.jetbrains.exposed:spring-transaction:$exposedVersion")
	implementation("tools.jackson.module:jackson-module-kotlin")
	// Not runtimeOnly: the LISTEN/NOTIFY listener needs org.postgresql.PGConnection.
	implementation("org.postgresql:postgresql")
	annotationProcessor("org.springframework.boot:spring-boot-configuration-processor")

	testImplementation("org.springframework.boot:spring-boot-starter-flyway-test")
	testImplementation("org.springframework.boot:spring-boot-starter-jdbc-test")
	testImplementation("org.springframework.boot:spring-boot-starter-security-oauth2-client-test")
	testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
	testImplementation("org.springframework.boot:spring-boot-testcontainers")
	// The Spring Boot BOM does not pin Testcontainers, so import its own BOM
	// rather than pinning each module by hand.
	testImplementation(platform("org.testcontainers:testcontainers-bom:$testcontainersVersion"))
	// Testcontainers 2.x renamed its modules to testcontainers-*.
	testImplementation("org.testcontainers:testcontainers-junit-jupiter")
	testImplementation("org.testcontainers:testcontainers-postgresql")
	testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
	testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test")
	testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
	compilerOptions {
		freeCompilerArgs.addAll("-Xjsr305=strict", "-Xannotation-default-target=param-property")
	}
}

tasks.withType<Test> {
	useJUnitPlatform()
}

// Spring exposes this as a BuildProperties bean; without it there is no version to
// report but the one hard-coded somewhere, which is the failure mode this avoids.
springBoot {
	buildInfo()
}
