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

FROM eclipse-temurin:21-jre-alpine
RUN apk add --no-cache curl
WORKDIR /app
COPY --from=builder /app/build/libs/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
