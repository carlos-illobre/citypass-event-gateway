plugins {
    kotlin("jvm") version "2.3.21"
    kotlin("plugin.spring") version "2.3.21"
    id("org.springframework.boot") version "4.1.0"
    id("io.spring.dependency-management") version "1.1.7"
    id("jacoco")
}

group = "com.citypass.webhooks"
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

// Genera META-INF/build-info.properties con la versión declarada arriba, que el health
// endpoint expone: así la versión sale del build y no de una variable que hay que
// acordarse de actualizar.
springBoot {
    buildInfo()
}

// ── Clases excluidas de cobertura (adaptadores de infraestructura) ────────────
// DispatcherApplicationKt : función main de Spring Boot — no tiene lógica propia.
// DlqReader               : crea un KafkaConsumer directamente, requiere broker real.
// SecurityConfig          : configura el builder de Spring Security, requiere contexto.
val jacocoExclusions = listOf(
    "**/DispatcherApplicationKt*",
    "**/DispatcherApplication*",
    "**/DlqReader*",
    "**/SecurityConfig*"
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

// Las clases que PIT no muta: las mismas que JaCoCo no mide, escritas como patrones de
// nombre de clase en vez de rutas de archivo. Si divergieran, el informe de mutación se
// llenaría de sobrevivientes en adaptadores que se decidió deliberadamente no probar.
val PITEST_EXCLUIDAS = listOf(
    "*DispatcherApplicationKt*",
    "*DispatcherApplication*",
    "*DlqReader*",
    "*SecurityConfig*"
)

// ── Mutation testing (tarea 'pitest') ────────────────────────────────────────
//
// La cobertura mide qué líneas se ejecutaron; el mutation score mide si la aserción
// importaba. Con el gate al 100 % la cobertura ya está saturada —no puede distinguir un
// test que verifica de uno decorativo— así que este es el instrumento que queda.
//
// Corre en el CI —después del build, reutilizando las clases ya compiladas— y también a
// mano con `tests/mutation.sh`. **No tiene umbral**: informa, no reprueba. Existen los
// mutantes equivalentes, que producen código con el mismo comportamiento y que ningún
// test puede matar; un umbral castigaría código correcto. El informe se publica en la
// GitHub Page, al lado de la cobertura.
//
// Se invoca la CLI en vez del plugin de Gradle porque `gradle-pitest-plugin` quedó en
// 1.15.0 y usa `reporting.baseDir`, que Gradle 9 eliminó: aplicarlo falla al configurar.
// La CLI es una interfaz estable y acá se ve exactamente qué se le pasa.
val pitestClasspath: Configuration by configurations.creating

dependencies {
    pitestClasspath("org.pitest:pitest-command-line:1.19.6")
    // Sin excluir sus transitivas, el plugin arrastra una junit-platform vieja que queda
    // delante de la del proyecto y el minion de cobertura de PIT muere con UNKNOWN_ERROR.
    pitestClasspath("org.pitest:pitest-junit5-plugin:1.2.3") { isTransitive = false }
    // No hace falta el `pitest-kotlin-plugin` de arcmutate —que además exige licencia—:
    // PIT 1.19.6 ya trae la feature `fkotlin` activada por defecto, que filtra el
    // bytecode sintético del compilador de Kotlin.
}

tasks.register<JavaExec>("pitest") {
    group = "verification"
    description = "Mutation testing con PIT. Informa, no reprueba: no tiene umbral."
    dependsOn(tasks.named("testClasses"))

    val salida = layout.buildDirectory.dir("reports/pitest").get().asFile
    val clasesApp = sourceSets.main.get().output.classesDirs
    val classpathCompleto = sourceSets.test.get().runtimeClasspath

    mainClass = "org.pitest.mutationtest.commandline.MutationCoverageReport"
    classpath = pitestClasspath + classpathCompleto

    argumentProviders.add(CommandLineArgumentProvider {
        listOf(
            "--reportDir", salida.absolutePath,
            "--sourceDirs", file("src/main/kotlin").absolutePath,
            // Sólo las clases de la app: el classpath trae cientos de clases de terceros
            // que no tiene sentido mutar.
            "--targetClasses", "com.citypass.*",
            "--targetTests", "com.citypass.*",
            // Estas listas van separadas por COMA, no por el separador de path del
            // sistema: con `:` PIT las lee como una sola ruta y no encuentra nada.
            "--mutableCodePaths", clasesApp.joinToString(","),
            "--classPath", classpathCompleto.joinToString(","),
            // Las mismas exclusiones que JaCoCo: son adaptadores de infraestructura que se
            // decidió no medir, y sin esto el informe se llena de mutantes vivos ahí.
            "--excludedClasses", PITEST_EXCLUIDAS.joinToString(","),
            // Los tests de integración no sirven de verdugos: necesitan infraestructura y
            // PIT los correría una vez por mutante.
            "--excludedGroups", "integration",
            // Sin esto, cuatro de cada cinco sobrevivientes son PIT borrando las
            // comprobaciones de nulidad que **inserta el compilador de Kotlin**
            // (`Intrinsics.checkNotNull*`). Son mutantes equivalentes: no hay test que
            // pueda matarlos, y ahogan a los sobrevivientes que sí importan.
            //
            // El precio: PIT tampoco muta el resto de la línea donde aparece esa llamada.
            // Se aceptó porque esas líneas son interop con Java, y perder alguna mutación
            // real ahí cuesta menos que un informe que nadie lee.
            // Ojo: esta opción **reemplaza** la lista por defecto, no se suma a ella.
            // Con sólo `Intrinsics`, la feature `flogcall` de PIT se queda sin las clases
            // de logging y el informe se llena de "borré tu llamada al logger": 53 de 98
            // sobrevivientes en la primera medición. Nadie asserta sobre los logs.
            "--avoidCallsTo",
            "java.util.logging,org.apache.log4j,org.slf4j,org.apache.commons.logging," +
                "kotlin.jvm.internal.Intrinsics",
            "--outputFormats", "HTML,XML",
            "--threads", Runtime.getRuntime().availableProcessors().coerceAtMost(4).toString(),
            // Sin marca de tiempo el informe queda siempre en la misma ruta, que es lo que
            // permite que el script lo enlace y parsee el resultado.
            "--timestampedReports", "false",
            "--verbose", "false",
        )
    })
}
