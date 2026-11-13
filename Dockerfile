# Container image for the OpenFedNow gateway.
#
# Two stages. Maven with JDK 17 builds and repackages the Spring Boot jar; a
# JRE 17 runtime carries only the extracted application. No Maven, no compiler
# and no JDK reach the runtime layer. Both base images are pinned by digest so
# this file resolves the same bytes on every build; Dependabot raises them.
#
# The non-production fixture gates stay off. application.yml defaults
# RELIABILITY_FIXTURE_API_ENABLED and RELIABILITY_LEGACY_ENTRY_BRIDGE_ENABLED
# to false and this image sets neither, so enabling them stays an explicit,
# local-only override (see demo/payment-integration.sh). This image is the
# gateway only; it makes no claim about live rail or core integration.
#
# Build:
#   docker build -t openfednow:dev .
#
# Run, with Postgres, Redis and RabbitMQ reachable by name on the same network:
#   docker run --rm -p 127.0.0.1:8080:8080 \
#     -e DB_URL=jdbc:postgresql://postgres:5432/openfednow \
#     -e REDIS_HOST=redis -e RABBITMQ_HOST=rabbitmq openfednow:dev

# maven:3.9-eclipse-temurin-17
FROM maven@sha256:1a352420f7aba21f5ad08df31bab55f74c013fb491f1ae8ab1dd7ff9ed698584 AS build

WORKDIR /build

# Dependencies resolve from pom.xml alone, so this layer survives changes that
# only touch src/.
COPY pom.xml ./
RUN mvn -B -ntp -DskipTests dependency:go-offline

COPY src ./src
RUN mvn -B -ntp -DskipTests package

# Split the jar into layers that change at different rates. The repackaged jar
# is the only *.jar in target/; Spring Boot renames the plain one to
# .jar.original, which this glob does not match.
RUN jar="$(ls target/openfednow-*.jar)" \
    && java -Djarmode=tools -jar "$jar" extract --layers --launcher --destination extracted


# eclipse-temurin:17-jre
FROM eclipse-temurin@sha256:0b6521287f850ec1c4b31c9074c5a1ae1d67c5922c3fefd21065c7c32e244d4a AS runtime

ARG VERSION=0.0.0-dev
ARG REVISION=unknown

LABEL org.opencontainers.image.title="OpenFedNow" \
      org.opencontainers.image.description="Middleware connecting legacy core banking systems to the FedNow Service (ISO 20022 over HTTPS)." \
      org.opencontainers.image.source="https://github.com/danielsmori/open-fednow" \
      org.opencontainers.image.url="https://github.com/danielsmori/open-fednow" \
      org.opencontainers.image.documentation="https://github.com/danielsmori/open-fednow/blob/main/README.md" \
      org.opencontainers.image.licenses="Apache-2.0" \
      org.opencontainers.image.version="${VERSION}" \
      org.opencontainers.image.revision="${REVISION}"

# The Helm chart runs the pod as runAsUser 1000 with fsGroup 1000, so the image
# matches that UID. Ubuntu already defines UID 1000, so the numeric form avoids
# creating a second user for the same id.
WORKDIR /app
RUN chown 1000:1000 /app
USER 1000:1000

# Keep anything that writes to a home directory inside the owned workdir.
ENV HOME=/app
# Size the heap from the container's memory limit, not the host's total.
# Deployers can replace this; the Helm chart sets its own resource limits.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75.0"

# Slowest-changing layer first.
COPY --from=build --chown=1000:1000 /build/extracted/dependencies/ ./
COPY --from=build --chown=1000:1000 /build/extracted/spring-boot-loader/ ./
COPY --from=build --chown=1000:1000 /build/extracted/snapshot-dependencies/ ./
COPY --from=build --chown=1000:1000 /build/extracted/application/ ./

EXPOSE 8080

# /fednow/health returns 200 once the context is serving. It does not test rail
# connectivity or certificates. Kubernetes ignores this HEALTHCHECK and uses the
# actuator liveness and readiness probes the Helm chart declares; this one is
# what Compose and `docker run` wait on. curl ships in the base image.
HEALTHCHECK --interval=15s --timeout=5s --start-period=90s --retries=3 \
    CMD curl -fsS "http://127.0.0.1:${SERVER_PORT:-8080}/fednow/health" || exit 1

ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
