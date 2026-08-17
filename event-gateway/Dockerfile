FROM gradle:8.10-jdk21-alpine AS builder
WORKDIR /app
COPY . ./
# `sed` quita los CR antes de ejecutar el wrapper. Con un checkout hecho en Windows sin
# .gitattributes, `gradlew` llega con CRLF y el kernel busca un intérprete llamado
# `/bin/sh\r`: falla con «not found» sobre un archivo que está ahí.
#
# El .gitattributes de la raíz ya evita que eso pase, pero esto hace que la imagen se
# construya igual si el código llegó de otra forma —un ZIP descargado, un clon viejo, un
# Git mal configurado— en vez de depender de la máquina de quien construye.
RUN sed -i 's/\r$//' gradlew && chmod +x gradlew && ./gradlew bootJar -x test --no-daemon

# El jar de Spring Boot se abre en las cuatro capas que él mismo define, en vez de
# copiarse entero.
#
# El motivo es el tamaño de lo que viaja en cada despliegue. El jar pesa 67 MB, de los
# cuales el 99,6 % son dependencias y el 0,2 % es código nuestro: copiarlo de una pieza
# hace que cambiar una línea de Kotlin invalide una capa de 70 MB y haya que transferirla
# entera. Separado, las dependencias quedan en una capa que no cambia entre commits y
# sólo viaja la capa `application`, de unos cientos de KB.
#
# El orden de los COPY va de lo más estable a lo más volátil: si `dependencies` fuera
# después de `application`, cambiaría de posición en cada build y perdería la caché igual.
RUN java -Djarmode=tools -jar build/libs/*.jar extract --layers --launcher --destination extracted

FROM eclipse-temurin:21-jre-alpine

# Usuario sin privilegios. El uid es fijo para que el contenedor se comporte igual en
# cualquier máquina, y porque los volúmenes guardan el número: si cambiara entre
# versiones, el gateway dejaría de poder escribir sus schemas sin ninguna explicación.
RUN addgroup -S -g 1001 gateway && adduser -S -u 1001 -G gateway gateway

WORKDIR /app

# Las cuatro capas del jar, de la que menos cambia a la que más.
COPY --from=builder /app/extracted/dependencies/ ./
COPY --from=builder /app/extracted/spring-boot-loader/ ./
COPY --from=builder /app/extracted/snapshot-dependencies/ ./
COPY --from=builder /app/extracted/application/ ./

# Los dos directorios que el gateway escribe en runtime —los .avsc de los event types y
# subscriptions.json— van montados como volúmenes. Docker copia el contenido de la imagen
# al volumen nuevo CONSERVANDO el propietario, así que crearlos acá con el dueño correcto
# es lo que hace que el volumen nazca escribible.
#
# Un volumen que ya existía de una versión anterior sigue siendo de root: hay que
# recrearlo, o cambiarle el dueño a mano.
RUN mkdir -p /app/schemas /app/data && chown -R gateway:gateway /app/schemas /app/data

USER gateway

EXPOSE 8080

# Se arranca por el launcher y no con `-jar`: el jar ya no existe como archivo único.
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
