plugins {
    kotlin("jvm") version "1.9.25"
    kotlin("plugin.spring") version "1.9.25"
    id("org.springframework.boot") version "3.2.4"
    id("io.spring.dependency-management") version "1.1.6"
    kotlin("plugin.jpa") version "1.9.25"
    kotlin("kapt") version "1.9.25"
    id("com.google.protobuf") version "0.9.4"
}

apply(plugin = "org.jetbrains.kotlin.plugin.jpa")
apply(plugin = "org.jetbrains.kotlin.plugin.spring")

group = "com.park"
version = "0.0.1-SNAPSHOT"
val grpcVersion = "1.63.0"
java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(17)
    }
}

repositories {
    mavenCentral()
    maven {
        name = "GitHubPackages"
        url = uri("https://maven.pkg.github.com/PARKPARKWOO/common-module")
        credentials {
            username = project.findProperty("gpr.user")?.toString() ?: System.getenv("GITHUB_USERNAME")
            password = project.findProperty("gpr.key")?.toString() ?: System.getenv("GITHUB_TOKEN")
        }
    }
}

dependencies {
    implementation("org.woo:domain-auth:+")
    implementation("org.woo:http:0.1.1")
    implementation("org.woo:mapper:+")
    implementation("org.woo:apm:+")

    implementation("org.springframework.boot:spring-boot-starter-webflux")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    runtimeOnly("com.mysql:mysql-connector-j")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    // jwt
    implementation("io.jsonwebtoken:jjwt-api:0.11.5")
    runtimeOnly("io.jsonwebtoken:jjwt-impl:0.11.5")
    runtimeOnly("io.jsonwebtoken:jjwt-jackson:0.11.5")

    implementation("org.springframework.boot:spring-boot-starter-websocket")

    // swagger-ui
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:2.6.0")

    // feign
    implementation("org.springframework.cloud:spring-cloud-starter-openfeign")
//    implementation("io.github.openfeign:feign-httpclient:13.2")

    // jackson
    implementation("com.fasterxml.jackson.core:jackson-core:2.15.2")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.15.2")
    implementation("com.fasterxml.jackson.core:jackson-annotations:2.15.2")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.15.2")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.15.2")

    // uuid generator
    implementation("com.fasterxml.uuid:java-uuid-generator:5.0.0")

    // query dsl
    implementation("com.querydsl:querydsl-jpa:5.0.0:jakarta")
    kapt("com.querydsl:querydsl-apt:5.0.0:jakarta")

    // s3
    implementation("com.amazonaws:aws-java-sdk-s3:1.12.666")

    // coroutine
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
    runtimeOnly("org.jetbrains.kotlinx:kotlinx-coroutines-reactive:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-reactor:1.6.4")

    // redis
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
    implementation("org.redisson:redisson:3.26.0")

    // for metric
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    runtimeOnly("io.micrometer:micrometer-registry-prometheus")

    // log-loki
    implementation("com.github.loki4j:loki-logback-appender:1.5.1")

    implementation("org.springframework.cloud:spring-cloud-starter-netflix-eureka-client")

    // grpc
    implementation("net.devh:grpc-client-spring-boot-starter:3.1.0.RELEASE") {
        exclude(group = "io.grpc", module = "grpc-netty-shaded")
        exclude(group = "io.grpc", module = "grpc-protobuf")
//        exclude(group = "io.grpc", module = "grpc-")
    }
    implementation("io.grpc:grpc-netty-shaded:$grpcVersion")
    implementation("org.woo:grpc:+")

    // kafka
    implementation("org.springframework.kafka:spring-kafka")
    testImplementation("org.springframework.kafka:spring-kafka-test")

    // flyway for db migration
    implementation("org.flywaydb:flyway-core:10.19.0")
    implementation("org.flywaydb:flyway-mysql:10.19.0")
}

kotlin {
    compilerOptions {
        freeCompilerArgs.addAll("-Xjsr305=strict")
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
}

extra["springCloudVersion"] = "2023.0.0"

dependencyManagement {
    imports {
        mavenBom("org.springframework.cloud:spring-cloud-dependencies:${property("springCloudVersion")}")
    }
}

kapt {
    arguments {
        arg("querydsl.sourceDir", "${project.layout.buildDirectory}/generated/querydsl")
    }
}
