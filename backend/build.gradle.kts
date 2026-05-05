plugins {
    java
    id("org.springframework.boot") version "3.4.5"
    id("io.spring.dependency-management") version "1.1.6"
}

group = "io.devportal"
version = "0.1.0-SNAPSHOT"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-websocket")

    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")

    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310")
    implementation("com.networknt:json-schema-validator:1.5.4")

    implementation("org.kohsuke:github-api:1.327")
    implementation("org.eclipse.jgit:org.eclipse.jgit:7.1.0.202411261347-r")

    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:2.7.0")

    developmentOnly("org.springframework.boot:spring-boot-devtools")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test> {
    useJUnitPlatform()
}

// Sync the canonical schema and doc-skeleton from the monorepo root into resources
// so they ship inside the bootJar. Single source of truth lives at <repo>/schema/.
val syncSchemaResources by tasks.registering(Copy::class) {
    from("../schema") {
        include("devportal-asset.schema.json")
        include("doc-skeleton/**")
    }
    into(layout.buildDirectory.dir("generated-resources/schema"))
}

sourceSets.named("main") {
    resources.srcDir(layout.buildDirectory.dir("generated-resources"))
}

tasks.named("processResources") {
    dependsOn(syncSchemaResources)
}
