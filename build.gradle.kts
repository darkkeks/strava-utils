plugins {
    kotlin("jvm") version "2.3.10"
    application
}

group = "stravautils"
version = "0.1.0"

repositories {
    mavenCentral()
}

dependencies {
    implementation("io.ktor:ktor-server-core:3.3.0")
    implementation("io.ktor:ktor-server-netty:3.3.0")
    implementation("io.ktor:ktor-server-call-logging:3.3.0")
    implementation("io.ktor:ktor-server-status-pages:3.3.0")
    implementation("io.ktor:ktor-client-core:3.3.0")
    implementation("io.ktor:ktor-client-cio:3.3.0")
    implementation("io.ktor:ktor-client-content-negotiation:3.3.0")
    implementation("io.ktor:ktor-serialization-jackson:3.3.0")
    implementation("org.mozilla:rhino:1.7.14")
    implementation("org.telegram:telegrambots-longpolling:9.2.1")
    implementation("org.telegram:telegrambots-client:9.2.1")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.21.0")
    implementation("ch.qos.logback:logback-classic:1.5.18")
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.13.4")
}

tasks.test {
    useJUnitPlatform()
}

kotlin {
    jvmToolchain(24)
}

application {
    mainClass.set("stravahooks.MainKt")
}
