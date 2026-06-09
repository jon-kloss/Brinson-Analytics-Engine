plugins {
    kotlin("jvm") version "2.1.21"
    application
}

group = "dev.kloss.brinson"
version = "0.1.0"

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.duckdb:duckdb_jdbc:1.3.2.0")
    implementation("com.github.ajalt.clikt:clikt:5.0.3")

    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
    jvmToolchain(21)
}

application {
    mainClass.set("MainKt")
    // DuckDB does the heavy lifting off-heap; the JVM mostly shuttles results.
    applicationDefaultJvmArgs = listOf("-Xmx4g")
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
    }
}
