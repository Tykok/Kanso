# ---- build ----
FROM eclipse-temurin:21-jdk AS build
WORKDIR /src

# Warm the Gradle + dependency cache on its own layer so source edits don't refetch.
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle ./gradle
RUN ./gradlew --no-daemon dependencies || true

COPY src ./src

# The commit the image was built from, stamped into build-info.properties and reported
# by /api/me. Declared here rather than at the top so it cannot invalidate the
# dependency-cache layer above. Docker exports build args as environment variables to
# RUN, and `build.gradle.kts` reads KANSO_COMMIT from there; unset, it stays `dev`.
ARG KANSO_COMMIT=dev
RUN ./gradlew --no-daemon clean bootJar -x test

# ---- run ----
FROM eclipse-temurin:21-jre
WORKDIR /app
RUN useradd --system --uid 10001 kanso
COPY --from=build /src/build/libs/*.jar app.jar

# The encryption key for stored secrets lives here. Created with the right owner
# before the volume is mounted, otherwise Docker initialises it as root and the
# unprivileged process cannot write its own key.
RUN mkdir -p /app/data && chown -R kanso:kanso /app/data
VOLUME ["/app/data"]

USER kanso
EXPOSE 8080
ENV JAVA_OPTS=""
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
