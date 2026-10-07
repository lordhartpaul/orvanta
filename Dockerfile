# syntax=docker/dockerfile:1

# ---- build: compile and package the server jar (tests run in the pipeline, not here) ----
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /src
COPY pom.xml ./
COPY orvanta-core/pom.xml orvanta-core/
COPY orvanta-forge/pom.xml orvanta-forge/
COPY orvanta-pay/pom.xml orvanta-pay/
COPY orvanta-dist/pom.xml orvanta-dist/
COPY orvanta-core/src orvanta-core/src
COPY orvanta-forge/src orvanta-forge/src
COPY orvanta-pay/src orvanta-pay/src
RUN --mount=type=cache,target=/root/.m2 mvn -B -q -DskipTests -pl orvanta-pay -am package

# ---- run: a JDK is needed at run time, because Forge compiles the models when they are deployed ----
FROM eclipse-temurin:17-jdk-jammy
RUN groupadd --system --gid 10001 orvanta \
    && useradd --system --uid 10001 --gid orvanta --home-dir /app --shell /usr/sbin/nologin orvanta \
    && mkdir -p /app/data && chown -R orvanta:orvanta /app
WORKDIR /app
COPY --from=build /src/orvanta-pay/target/orvanta-pay-*-all.jar lib/orvanta-pay-all.jar
COPY config/orvanta.yaml config/orvanta.yaml
COPY workspace workspace

# Settings come from the environment (ORVANTA_<SETTING>, dots as underscores). The image itself is
# safe by default: no simulated external systems, and approved models are not written to the image.
ENV ORVANTA_SIMULATOR_ENABLED=false \
    ORVANTA_WORKSPACE_WRITEBACK=false

USER orvanta
VOLUME /app/data
EXPOSE 8480
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "lib/orvanta-pay-all.jar", "--config=config/orvanta.yaml"]
