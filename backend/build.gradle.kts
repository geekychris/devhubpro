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

    // CLI / SSH server: picocli for command parsing, JLine for the readline loop,
    // Apache Mina SSHD as the SSH transport. See io.devportal.cli.
    implementation("info.picocli:picocli:4.7.6")
    implementation("info.picocli:picocli-shell-jline3:4.7.6")
    implementation("org.apache.sshd:sshd-core:2.13.2")
    implementation("org.jline:jline:3.27.1")
    // Mina SSHD's Ed25519 support routes through one of two impls — net.i2p's eddsa
    // library or BouncyCastle. We pull both: net.i2p satisfies Mina's "EdDSA provider"
    // check at the SSH layer, BouncyCastle covers KeyEntry resolution and other ECC.
    implementation("net.i2p.crypto:eddsa:0.3.0")
    implementation("org.bouncycastle:bcprov-jdk18on:1.78.1")
    implementation("org.bouncycastle:bcpkix-jdk18on:1.78.1")

    // Telegram bot — long-polling client for the bot API. Bridges into the same picocli
    // command tree the SSH CLI uses (see io.devportal.telegram).
    implementation("com.github.pengrad:java-telegram-bot-api:7.11.0")

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
