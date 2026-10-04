plugins {
	java
	id("org.springframework.boot") version "4.1.1"
	id("io.spring.dependency-management") version "1.1.7"
}

group = "dev.chirag45"
version = "0.0.1-SNAPSHOT"

java {
	toolchain {
		languageVersion = JavaLanguageVersion.of(21)
	}
}

repositories {
	mavenCentral()
}

extra["springAiVersion"] = "2.0.1"

dependencies {
	implementation("org.springframework.boot:spring-boot-starter-actuator")
	implementation("org.springframework.boot:spring-boot-starter-data-jpa")
	implementation("org.springframework.boot:spring-boot-starter-flyway")
	implementation("org.springframework.boot:spring-boot-starter-jackson")
	implementation("org.springframework.boot:spring-boot-starter-security")
	implementation("org.springframework.boot:spring-boot-starter-security-oauth2-resource-server")
	implementation("org.springframework.boot:spring-boot-starter-validation")
	implementation("org.springframework.boot:spring-boot-starter-webmvc")
	implementation("com.clerk:backend-api:6.1.0")
	implementation("org.flywaydb:flyway-database-postgresql")
	implementation("com.github.f4b6a3:uuid-creator:6.1.1")
	implementation("io.github.resilience4j:resilience4j-bulkhead:2.4.0")
	implementation("me.paulschwarz:spring-dotenv:4.0.0")
	implementation("io.temporal:temporal-sdk:1.40.0")
	compileOnly("org.projectlombok:lombok")
	runtimeOnly("org.postgresql:postgresql")
	annotationProcessor("org.projectlombok:lombok")
	testImplementation("org.springframework.boot:spring-boot-starter-actuator-test")
	testImplementation("org.springframework.boot:spring-boot-starter-data-jpa-test")
	testImplementation("org.springframework.boot:spring-boot-starter-flyway-test")
	testImplementation("org.springframework.boot:spring-boot-starter-security-oauth2-resource-server-test")
	testImplementation("org.springframework.boot:spring-boot-starter-security-test")
	testImplementation("org.springframework.boot:spring-boot-starter-validation-test")
	testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
	testCompileOnly("org.projectlombok:lombok")
	testRuntimeOnly("org.junit.platform:junit-platform-launcher")
	testAnnotationProcessor("org.projectlombok:lombok")
}

dependencyManagement {
	imports {
		mavenBom("org.springframework.ai:spring-ai-bom:${property("springAiVersion")}")
	}
}

tasks.withType<Test> {
	useJUnitPlatform()
}

fun localEnvironmentValue(name: String): String? {
	val environmentFile = file(".env")

	if (!environmentFile.isFile) {
		return null
	}

	return environmentFile.useLines { lines ->
		lines.map(String::trim)
			.firstOrNull { it.startsWith("$name=") }
			?.substringAfter('=')
			?.trim()
			?.takeIf(String::isNotBlank)
	}
}

tasks.named<org.springframework.boot.gradle.tasks.run.BootRun>("bootRun") {
	val newRelicAgentJar = layout.projectDirectory.file("observability/newrelic/newrelic.jar").asFile
	val newRelicConfiguration = layout.projectDirectory.file("observability/newrelic/newrelic.yml").asFile

	if (newRelicAgentJar.isFile && newRelicConfiguration.isFile) {
		jvmArgs(
			"-javaagent:${newRelicAgentJar.absolutePath}",
			"-Dnewrelic.config.file=${newRelicConfiguration.absolutePath}"
		)
	}

	localEnvironmentValue("NEW_RELIC_LICENSE_KEY")?.let { licenseKey ->
		environment("NEW_RELIC_LICENSE_KEY", licenseKey)
	}
}
