import java.io.File

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

// Las clases que PIT no muta, alineadas con las exclusiones de JaCoCo.
// Acá no hay ninguna: el authorizer es una sola clase y toda es lógica.
val PITEST_EXCLUIDAS = listOf<String>("__ninguna__")

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
    // Sin excluir sus transitivas, el plugin arrastra junit-platform 1.9.2 y queda
    // delante de la 1.11.4 del proyecto: el minion de cobertura de PIT muere con
    // UNKNOWN_ERROR. Se usa la versión que ya tienen los tests.
    pitestClasspath("org.pitest:pitest-junit5-plugin:1.2.3") { isTransitive = false }
    // No se usa el `pitest-kotlin-plugin` de groupcdg/arcmutate, que filtraría el
    // bytecode sintético de Kotlin: exige licencia comercial y falla con "No licence
    // found". En su lugar se le dice a PIT que no mute las líneas que llaman a
    // `Intrinsics`, que es de donde sale la mayor parte de ese ruido.
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
            "--mutableCodePaths", clasesApp.joinToString(","),
            "--classPath", classpathCompleto.joinToString(","),
            // Las mismas exclusiones que JaCoCo: son adaptadores de infraestructura que se
            // decidió no medir, y sin esto el informe se llena de mutantes vivos ahí.
            "--excludedClasses", PITEST_EXCLUIDAS.joinToString(","),
            // Los tests de integración no sirven de verdugos: necesitan infraestructura y
            // PIT los correría una vez por mutante.
            "--excludedGroups", "integration",
            // Las comprobaciones de nulidad que inserta el compilador de Kotlin no son
            // código nuestro y sus mutantes no se pueden matar. Sin esto, cada parámetro
            // no nulo aporta un mutante equivalente al informe.
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
