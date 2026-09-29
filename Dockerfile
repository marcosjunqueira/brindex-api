# syntax=docker/dockerfile:1

# Build stage: the Gradle toolchain is JDK 25, so build with a JDK 25 image (no toolchain download).
FROM eclipse-temurin:25-jdk AS build
WORKDIR /src
# Wrapper and build scripts first, so the Gradle distribution and dependencies stay cached until
# they change.
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle gradle
RUN ./gradlew --no-daemon dependencies > /dev/null
COPY src src
# Tests run in CI before the image is built; this stage only packages the app.
RUN ./gradlew --no-daemon installDist

# Runtime stage: JRE only, no build tools, no source.
FROM eclipse-temurin:25-jre-alpine
# Fixed non-root uid/gid shared by every brindex image, so files on the shared data volume keep one
# owner. Compose can override it with `user:` to match the host user that owns ./data.
RUN addgroup -S -g 10001 brindex && adduser -S -D -H -u 10001 -G brindex brindex \
    && mkdir /data && chown brindex:brindex /data
COPY --from=build /src/build/install/brindex-api /app
# The SQLite files are mounted here at runtime; nothing is baked into the image.
VOLUME /data
# Size the heap from the container memory limit, die (and get restarted) on OOM, and allow the
# SQLite JDBC driver to load its native library without JDK 25 warnings.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError --enable-native-access=ALL-UNNAMED"
ENV BRINDEX_DB_PATH=/data/brindex.sqlite \
    ACCOUNTS_DB_PATH=/data/accounts.sqlite
USER brindex
WORKDIR /data
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=5s --start-period=30s --retries=3 \
    CMD wget -q -O /dev/null "http://127.0.0.1:${PORT:-8080}/ready" || exit 1
ENTRYPOINT ["/app/bin/brindex-api"]
