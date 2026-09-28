plugins {
    kotlin("jvm") version "2.4.20"
    kotlin("plugin.serialization") version "2.4.20"
    application
}

group = "br.com.brindex"
version = "0.1.0"

repositories {
    mavenCentral()
}

val ktorVersion = "2.3.13"

// Ktor 2.3.x pulls Netty 4.1.111, which has known critical/high CVEs (GHSA-c4c3-7fpv-j4q5 and
// others flagged by the CI dependency review). Align every Netty module on a patched 4.1.x.
val nettyVersion = "4.1.138.Final"

dependencies {
    implementation(platform("io.netty:netty-bom:$nettyVersion"))
    implementation("io.ktor:ktor-server-core:$ktorVersion")
    implementation("io.ktor:ktor-server-netty:$ktorVersion")
    implementation("io.ktor:ktor-server-content-negotiation:$ktorVersion")
    implementation("io.ktor:ktor-server-status-pages:$ktorVersion")
    implementation("io.ktor:ktor-server-cors:$ktorVersion")
    implementation("io.ktor:ktor-server-call-id:$ktorVersion")
    implementation("io.ktor:ktor-server-call-logging:$ktorVersion")
    implementation("io.ktor:ktor-serialization-kotlinx-json:$ktorVersion")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    implementation("org.xerial:sqlite-jdbc:3.53.4.0")
    implementation("ch.qos.logback:logback-classic:1.6.4")

    testImplementation("io.ktor:ktor-server-test-host:$ktorVersion")
    testImplementation(kotlin("test"))
}

kotlin {
    jvmToolchain(25)
}

application {
    mainClass.set("br.com.brindex.api.ApplicationKt")
}

tasks.test {
    useJUnitPlatform()
}
