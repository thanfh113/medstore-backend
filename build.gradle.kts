val ktor_version: String by project
val kotlin_version: String by project
val logback_version: String by project
val exposed_version: String by project

plugins {
    kotlin("jvm") version "2.0.21"
    id("io.ktor.plugin") version "3.1.1"
    kotlin("plugin.serialization") version "2.0.21"
}

group = "com.example.nhathuoc"
version = "0.0.1"

kotlin {
    jvmToolchain(17)
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
    kotlinOptions {
        jvmTarget = "17"
    }
}

application {
    mainClass = "com.example.nhathuoc.ApplicationKt"
    applicationDefaultJvmArgs = listOf("-Dio.ktor.development=true")
}

repositories {
    mavenCentral()
}

dependencies {
    // Ktor server
    implementation("io.ktor:ktor-server-core-jvm:$ktor_version")
    implementation("io.ktor:ktor-server-netty-jvm:$ktor_version")
    implementation("io.ktor:ktor-server-content-negotiation-jvm:$ktor_version")
    implementation("io.ktor:ktor-server-call-logging-jvm:$ktor_version")
    implementation("io.ktor:ktor-server-auth-jvm:$ktor_version")
    implementation("io.ktor:ktor-server-auth-jwt-jvm:$ktor_version")
    implementation("io.ktor:ktor-server-cors-jvm:$ktor_version")
    implementation("io.ktor:ktor-server-status-pages-jvm:$ktor_version")
    implementation("io.ktor:ktor-server-websockets-jvm:$ktor_version")
    implementation("io.ktor:ktor-server-request-validation:$ktor_version")
    implementation("io.ktor:ktor-server-rate-limit:$ktor_version")

    // Serialization
    implementation("io.ktor:ktor-serialization-kotlinx-json-jvm:$ktor_version")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    // Exposed ORM
    implementation("org.jetbrains.exposed:exposed-core:$exposed_version")
    implementation("org.jetbrains.exposed:exposed-dao:$exposed_version")
    implementation("org.jetbrains.exposed:exposed-jdbc:$exposed_version")
    implementation("org.jetbrains.exposed:exposed-kotlin-datetime:$exposed_version")

    // MySQL
    implementation("com.mysql:mysql-connector-j:9.1.0")
    implementation("io.ktor:ktor-server-config-yaml:${ktor_version}")

    // Connection pool
    implementation("com.zaxxer:HikariCP:6.2.1")

    // Password hashing
    implementation("org.mindrot:jbcrypt:0.4")

    // Logging
    implementation("ch.qos.logback:logback-classic:$logback_version")

    // Datetime
    implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.6.1")

    // UUID
    implementation("com.fasterxml.uuid:java-uuid-generator:5.1.0")

    // Cloudinary (upload ảnh/video)
    implementation("com.cloudinary:cloudinary-http5:2.0.0")

    // Ktor HTTP Client (dùng nội bộ để gọi API nếu cần)
    implementation("io.ktor:ktor-client-core-jvm:$ktor_version")
    implementation("io.ktor:ktor-client-cio-jvm:$ktor_version")

    // Multipart file upload support
    implementation("io.ktor:ktor-server-double-receive:$ktor_version")


    // Testing
    testImplementation("io.ktor:ktor-server-test-host-jvm:$ktor_version")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit:$kotlin_version")

}
