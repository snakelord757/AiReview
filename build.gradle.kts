plugins {
    kotlin("jvm") version "2.4.10"
    application
}

group = "ru.aireview"
version = "1.0.0"

repositories { mavenCentral() }

val ktorVersion = "3.5.1"

dependencies {
    implementation("io.ktor:ktor-server-core-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-netty-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-content-negotiation-jvm:$ktorVersion")
    implementation("io.ktor:ktor-serialization-jackson-jvm:$ktorVersion")
    implementation("io.ktor:ktor-client-core-jvm:$ktorVersion")
    implementation("io.ktor:ktor-client-cio-jvm:$ktorVersion")
    implementation("io.ktor:ktor-client-content-negotiation-jvm:$ktorVersion")
    implementation("io.ktor:ktor-client-logging-jvm:$ktorVersion")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.20.1")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.20.1")
    implementation("com.auth0:java-jwt:4.5.0")
    implementation("org.bouncycastle:bcpkix-jdk18on:1.83")
    implementation("ch.qos.logback:logback-classic:1.5.25")
    testImplementation(kotlin("test"))
    testImplementation("io.ktor:ktor-server-test-host-jvm:$ktorVersion")
}

application { mainClass.set("ru.aireview.ApplicationKt") }

kotlin { jvmToolchain(21) }

tasks.test { useJUnitPlatform() }
