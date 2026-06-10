# ── build ────────────────────────────────────────────────────────────────────
FROM gradle:8.14-jdk21 AS build
WORKDIR /app
COPY settings.gradle.kts build.gradle.kts ./
COPY src ./src
RUN gradle installDist --no-daemon

# Bake the dataset into the image at build time: the generator is seeded, so
# this reproduces exactly the data behind the README/benchmark numbers, and
# the container cold-starts instantly. Override for smaller images/instances:
#   docker build --build-arg SCALE=0.25 .
ARG SCALE=1.0
RUN build/install/brinson/bin/brinson generate --scale ${SCALE} --out /tmp/pq \
 && build/install/brinson/bin/brinson load --parquet /tmp/pq --db /app/data/brinson.duckdb \
 && rm -rf /tmp/pq

# ── runtime ──────────────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/build/install/brinson ./brinson
COPY --from=build /app/data/brinson.duckdb ./data/brinson.duckdb
ENV PORT=8080
EXPOSE 8080
# Railway injects $PORT; `brinson serve` reads it as its default.
CMD ["sh", "-c", "./brinson/bin/brinson serve --db data/brinson.duckdb"]
