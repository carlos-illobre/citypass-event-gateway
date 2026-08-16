plugins {
    kotlin("jvm") version "2.3.21"
    kotlin("plugin.spring") version "2.3.21"
    id("org.springframework.boot") version "4.1.0"
    id("io.spring.dependency-management") version "1.1.7"
    id("jacoco")
}

group = "com.citypass.gateway"
version = "0.0.1-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-kafka")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("tools.jackson.module:jackson-module-kotlin")
    implementation("org.apache.avro:avro:1.12.0")
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:2.8.9")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server")
    // Métricas: actuator expone los endpoints, micrometer los traduce a formato Prometheus.
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("io.micrometer:micrometer-registry-prometheus")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.mockito.kotlin:mockito-kotlin:5.4.0")
    testImplementation("org.springframework.kafka:spring-kafka-test")
    testImplementation("org.springframework.security:spring-security-test")
}

kotlin {
    compilerOptions {
        freeCompilerArgs.addAll("-Xjsr305=strict", "-Xannotation-default-target=param-property")
    }
}

// El plugin de Spring Boot genera dos JARs: el fat JAR ejecutable y un plain JAR sin Main-Class.
// Deshabilitamos el plain JAR para que build/libs/ tenga siempre un único artefacto.
tasks.named<Jar>("jar") { enabled = false }

// Genera META-INF/build-info.properties con la versión de este bloque de arriba.
// Spring lo expone como bean BuildProperties, que EventController publica en
// metadata.gatewayVersion — así la versión sale del build y no de una env var
// que hay que acordarse de actualizar.
springBoot {
    buildInfo()
}

// ── Clases excluidas de cobertura (adaptadores de infraestructura) ────────────
// GatewayApplicationKt : función main de Spring Boot — no tiene lógica propia.
// DlqController        : crea un KafkaConsumer directamente, requiere broker real.
// EventsController     : ídem. Su lógica de selección vive en EventSelection, que sí se mide.
// SecurityConfig       : configura el builder de Spring Security, requiere contexto.
// KafkaTopicAdmin      : una llamada al AdminClient de Kafka, que es un cliente real.
val jacocoExclusions = listOf(
    "**/GatewayApplicationKt*",
    "**/DlqController*",
    "**/EventsController*",
    "**/SecurityConfig*",
    "**/KafkaTopicAdmin*"
)

// ── Tests unitarios (tarea 'test') ───────────────────────────────────────────
tasks.test {
    useJUnitPlatform {
        excludeTags("integration")
    }
    finalizedBy(tasks.jacocoTestReport)
}

// ── Tests de integración (tarea 'integrationTest') ───────────────────────────
val integrationTest by tasks.registering(Test::class) {
    description = "Ejecuta los tests de integración (happy path de los endpoints REST)."
    group = "verification"
    useJUnitPlatform {
        includeTags("integration")
    }
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
}

// Sin esto la tarea existe pero no la corre nadie: `build` no la ejecuta, así que un test
// de integración roto pasaría desapercibido hasta que alguien se acordara de invocarla a
// mano. No necesitan broker ni Schema Registry, así que no hay motivo para dejarlos afuera.
tasks.check {
    dependsOn(integrationTest)
    // Ídem: el umbral del 100% no sirve de nada si hay que acordarse de pedirlo aparte.
    dependsOn(tasks.jacocoTestCoverageVerification)
}

// ── JaCoCo ───────────────────────────────────────────────────────────────────
jacoco {
    toolVersion = "0.8.12"
}

tasks.jacocoTestReport {
    dependsOn(tasks.test)
    classDirectories.setFrom(files(classDirectories.files.map {
        fileTree(it) { exclude(jacocoExclusions) }
    }))
    reports {
        html.required = true
        xml.required = true
        csv.required = false
    }
}

tasks.jacocoTestCoverageVerification {
    classDirectories.setFrom(files(classDirectories.files.map {
        fileTree(it) { exclude(jacocoExclusions) }
    }))
    violationRules {
        rule {
            limit {
                counter = "INSTRUCTION"
                minimum = "1.00".toBigDecimal()
            }
        }
        rule {
            limit {
                counter = "BRANCH"
                minimum = "1.00".toBigDecimal()
            }
        }
    }
}
