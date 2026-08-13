plugins {
    kotlin("jvm") version "2.3.21"
    id("jacoco")
}

group = "com.citypass.kafka"
version = "1.0.0"

java {
    toolchain { languageVersion = JavaLanguageVersion.of(21) }
}

// El jar lo carga el broker, no nosotros: tiene que hablar el bytecode de la JVM que
// corre adentro de confluentinc/cp-kafka:7.7.1, que es Java 17. Compilado a 21 el broker
// muere al arrancar con UnsupportedClassVersionError, antes de escuchar en ningún puerto.
kotlin {
    compilerOptions { jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17 }
}
tasks.withType<JavaCompile>().configureEach { options.release = 17 }

repositories { mavenCentral() }

// Kafka 3.7.x, la versión que trae confluentinc/cp-kafka:7.7.1.
val kafkaVersion = "3.7.1"

dependencies {
    // `compileOnly`: estas clases ya están en el classpath del broker. Empaquetarlas
    // en el jar sería duplicarlas y arriesgar un choque de versiones.
    compileOnly("org.apache.kafka:kafka-clients:$kafkaVersion")
    compileOnly("org.apache.kafka:kafka-server-common:$kafkaVersion")

    testImplementation("org.apache.kafka:kafka-clients:$kafkaVersion")
    testImplementation("org.apache.kafka:kafka-server-common:$kafkaVersion")
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// El broker carga este jar en su propia JVM, cuyo classpath no tiene la stdlib de Kotlin:
// sin empaquetarla, arranca y muere con NoClassDefFoundError en kotlin.jvm.internal.
// Las dependencias de Kafka son `compileOnly`, así que lo único que entra acá es la
// stdlib — no hay riesgo de duplicar clases del broker.
tasks.jar {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from(configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) }) {
        exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "META-INF/versions/**/module-info.class")
    }
}

tasks.test {
    useJUnitPlatform()
    finalizedBy(tasks.jacocoTestReport)
}

tasks.jacocoTestReport {
    dependsOn(tasks.test)
    reports { xml.required = true; html.required = true }
}

// Sin esto el umbral existe pero `build` no lo evalúa: hay que acordarse de pedir la
// tarea a mano, y una regresión de cobertura pasa igual.
tasks.check {
    dependsOn(tasks.jacocoTestCoverageVerification)
}

tasks.jacocoTestCoverageVerification {
    violationRules {
        rule { limit { counter = "INSTRUCTION"; minimum = "1.00".toBigDecimal() } }
        rule { limit { counter = "BRANCH";      minimum = "1.00".toBigDecimal() } }
    }
}
