# Imagen del broker Kafka con el autorizador de CityPass+.
#
# Existe porque la autorización nativa de Kafka es por ACLs: reglas estáticas guardadas
# en el cluster, que no saben leer los claims de un JWT. Eso obligaría a mantener una
# lista de grupos sincronizada con el servicio de identidad. El autorizador de este
# proyecto deriva la política del token en cada conexión, así que no hay ninguna lista
# ni estado que pueda quedar desactualizado.
#
# Como corre dentro de la JVM del broker, no se puede usar la imagen estándar.
#
# El jar se compila acá y no se versiona, para que la imagen y el fuente no puedan
# quedar desfasados: cada `docker compose build` recompila.

FROM gradle:8.10-jdk21-alpine AS builder
WORKDIR /build
COPY . ./
RUN gradle jar --no-daemon

FROM confluentinc/cp-kafka:7.7.1
# Este directorio ya está en el classpath del broker.
COPY --from=builder /build/build/libs/*.jar /usr/share/java/kafka/
