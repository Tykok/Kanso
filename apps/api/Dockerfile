# ---- build ----
FROM eclipse-temurin:21-jdk AS build
WORKDIR /src

# Warm the Gradle + dependency cache on its own layer so source edits don't refetch.
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle ./gradle
RUN ./gradlew --no-daemon dependencies || true

COPY src ./src
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
