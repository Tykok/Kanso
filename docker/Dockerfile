# The distribution image: `ghcr.io/tykok/kanso`, built from the repository root because it
# needs both applications. `apps/api/Dockerfile` and `apps/web/Dockerfile` are unchanged
# and keep building the development stack; this file is the deployment artefact.
#
# The stages, and which of them the release actually runs:
#
#   api-build      Gradle -> /app.jar                 local only, on the builder
#   web-build      pnpm build -> /dist/web            local only, on the builder
#   api-prebuilt   COPY dist/app.jar -> /app.jar      release job two
#   web-prebuilt   COPY dist/web -> /dist/web         release job two
#   s6             s6-overlay -> /s6root              always, on the builder
#   runtime        the image                          always, per platform
#
# `release.yml` builds the jar and the Next bundle once, on native amd64, in a job of their
# own, downloads them into `dist/`, and then runs `buildx` for both platforms with
# API_SOURCE=api-prebuilt and WEB_SOURCE=web-prebuilt. Both of those stages are `FROM
# scratch` plus a COPY, so nothing architecture-dependent executes — the arm64 image never
# runs Gradle or Next under QEMU, which is twenty minutes to produce bytes identical to the
# amd64 ones. BuildKit builds only the stages the target depends on, so `dist/` does not
# have to exist for the default, end-to-end local build.
#
# The three producing stages are pinned to `$BUILDPLATFORM` as well, which means a plain
# two-platform `buildx` of this file also runs Gradle and Next exactly once. The prebuilt
# stages remain the release's path because they let one job's artefacts be signed, kept and
# reused; they are no longer what keeps QEMU out of the build.

ARG API_SOURCE=api-build
ARG WEB_SOURCE=web-build

# ---- api-build ----
# Pinned to the *builder's* platform. A jar is bytecode, so the arm64 image wants exactly
# the bytes the amd64 builder produced, and this is what keeps a two-platform `buildx`
# from starting a second Gradle under QEMU even when the prebuilt stages are not used.
FROM --platform=$BUILDPLATFORM eclipse-temurin:21-jdk AS api-build
WORKDIR /src

# Same layer ordering as `apps/api/Dockerfile`, and for the same reason: the dependency
# fetch is minutes and the sources change every commit, so they must not share a layer.
COPY apps/api/gradlew apps/api/settings.gradle.kts apps/api/build.gradle.kts ./
COPY apps/api/gradle ./gradle
RUN ./gradlew --no-daemon dependencies || true

COPY apps/api/src ./src

# The commit the image was built from, reported by /api/me. Declared here and not at the
# top so it cannot invalidate the dependency-cache layer above. `build.gradle.kts` reads
# it from the environment, where Docker puts build args for RUN; unset, it stays `dev`.
ARG KANSO_COMMIT=dev
RUN ./gradlew --no-daemon clean bootJar -x test

# One name for the artefact, so the runtime stage's COPY is the same line whether the jar
# was built above or handed over by the release. `-plain.jar` is the library jar the Boot
# plugin can also emit; it has no manifest main class and starting it is a bare stack trace.
RUN set -eu; \
	jar=$(find build/libs -name '*.jar' ! -name '*-plain.jar' | head -n 1); \
	test -n "$jar"; \
	cp "$jar" /app.jar

# ---- web-build ----
# `apps/web` is its own pnpm project with its own lockfile and its own workspace file — it
# is not a member of a root workspace, and the root `package.json` here is the Playwright
# suite. So the three files are copied from `apps/web/` and installed with `apps/web` as
# the root, exactly as `apps/web/Dockerfile` does with its own context.
#
# On the builder's platform for the same reason as api-build, with one caveat the runtime
# stage has to clean up after: the bundle is *almost* architecture-independent — see the
# sharp prune below.
FROM --platform=$BUILDPLATFORM node:20-bookworm-slim AS web-build
WORKDIR /web
RUN corepack enable
COPY apps/web/package.json apps/web/pnpm-lock.yaml apps/web/pnpm-workspace.yaml ./
RUN pnpm install --frozen-lockfile

COPY apps/web/ ./

# NEXT_PUBLIC_API_URL is deliberately absent. Next inlines it at build time, and a
# published image with an API origin welded in is the thing one origin exists to abolish:
# the front end asks for `/api/...` and the browser resolves it against the page. The
# commit is passed the same way the API's is, so the footer and /api/me are comparable.
ARG KANSO_COMMIT=dev
ENV NEXT_PUBLIC_KANSO_COMMIT=$KANSO_COMMIT
RUN pnpm build

# The three pieces `output: "standalone"` leaves in three places, assembled into the one
# directory the runtime stage copies — and the one the release uploads as its artefact.
RUN set -eu; \
	mkdir -p /dist/web/.next; \
	cp -r .next/standalone/. /dist/web/; \
	cp -r .next/static /dist/web/.next/static; \
	cp -r public /dist/web/public

# ---- api-prebuilt / web-prebuilt ----
# The release's side of the split. `FROM scratch` because these stages must not run a
# single instruction under emulation — a COPY is metadata, so the arm64 pass costs nothing.
FROM scratch AS api-prebuilt
COPY dist/app.jar /app.jar

FROM scratch AS web-prebuilt
COPY dist/web /dist/web

# The indirection that lets one Dockerfile serve both: `--build-arg API_SOURCE=api-prebuilt`
# swaps the source without a second file to keep in step with this one.
FROM ${API_SOURCE} AS api-dist
FROM ${WEB_SOURCE} AS web-dist

# ---- s6 ----
# Unpacked in a stage with `curl` and `xz` so the runtime image needs neither. The tarballs
# want to land at `/`, so they land at `/s6root` here and the runtime stage copies that
# over its own root.
FROM --platform=$BUILDPLATFORM debian:bookworm-slim AS s6
ARG S6_OVERLAY_VERSION=3.2.1.0
ARG TARGETARCH
RUN apt-get update && apt-get install -y --no-install-recommends ca-certificates curl xz-utils \
	&& rm -rf /var/lib/apt/lists/*
RUN set -eu; \
	case "$TARGETARCH" in \
		amd64) s6arch=x86_64 ;; \
		arm64) s6arch=aarch64 ;; \
		*) echo "unsupported TARGETARCH: $TARGETARCH" >&2; exit 1 ;; \
	esac; \
	base=https://github.com/just-containers/s6-overlay/releases/download/v${S6_OVERLAY_VERSION}; \
	mkdir -p /s6root; \
	curl -fsSL "$base/s6-overlay-noarch.tar.xz" | tar -C /s6root -Jxpf -; \
	curl -fsSL "$base/s6-overlay-${s6arch}.tar.xz" | tar -C /s6root -Jxpf -

# ---- runtime ----
# Node is the base rather than a copied binary because the standalone bundle expects a
# whole Node installation; the JRE and Caddy are single self-contained trees and travel
# well. The JRE is the `jammy` build on purpose: its glibc is older than bookworm's, and
# the default `21-jre` tag is noble, whose glibc is newer — the wrong direction.
FROM node:20-bookworm-slim AS runtime

COPY --from=eclipse-temurin:21-jre-jammy /opt/java/openjdk /opt/java/openjdk
ENV JAVA_HOME=/opt/java/openjdk
ENV PATH=$JAVA_HOME/bin:$PATH

# Caddy is a static Go binary, so one file is the whole install.
COPY --from=caddy:2.10 /usr/bin/caddy /usr/bin/caddy

COPY --from=s6 /s6root /

# Both applications run unprivileged and share one account, because they share one volume:
# the API writes the encryption key into /data and nothing else needs to read it.
RUN groupadd --system --gid 10001 kanso \
	&& useradd --system --uid 10001 --gid 10001 --no-create-home kanso

COPY --from=api-dist /app.jar /app/app.jar
COPY --from=web-dist /dist/web /app/web

# The one thing in the Next bundle that is not JavaScript. `next` depends optionally on
# `sharp`, whose prebuilt libvips is per-architecture, and Next traces it into the
# standalone output — so a bundle built on amd64 carries an amd64 binary into the arm64
# image, and "the standalone bundle is architecture-independent" is true only after this
# line. It is here rather than in web-build because both sources of that bundle, the stage
# and the release artefact, arrive through the COPY above.
#
# Removing rather than replacing it because nothing imports `next/image`: sharp is loaded
# only by the image optimiser, which nothing reaches. The day a component does import it,
# this line has to become a per-platform install instead of a delete.
#
# The whole package and not only the two `@img+sharp-linux-*` trees: pnpm's layout links
# them from `sharp@…` and from `node_modules/`, and a half-removal leaves dangling symlinks
# that still name an architecture.
RUN rm -rf /app/web/node_modules/.pnpm/@img+* \
	/app/web/node_modules/.pnpm/sharp@* \
	/app/web/node_modules/.pnpm/node_modules/@img \
	/app/web/node_modules/.pnpm/node_modules/sharp

COPY docker/Caddyfile /etc/caddy/Caddyfile
COPY docker/rootfs /

# The two things that must outlive the container. Created with the right owner before the
# volume is mounted, otherwise Docker initialises it as root and the unprivileged API
# cannot write its own key — `kanso-init` repeats the fix for bind mounts, which do not
# inherit anything from the image.
RUN mkdir -p /data && chown kanso:kanso /data
VOLUME ["/data"]

# `image.source` is not decoration: it is the one label GHCR reads, and without it a
# pushed package is attached to the *account* and to no repository. `v0.1.0` shipped
# without it and the result was an image that had demonstrably been pushed — three
# manifests, no errors — and appeared nowhere anyone would look for it, because the
# Packages panel on the repository page lists only packages that name it here. Being
# private hid the other half. The label also gives the package page its README and its
# link back, which is the whole reason a stranger trusts the image at all.
#
# In the Dockerfile rather than as `labels:` on the push action, so a locally built image
# answers `docker inspect` the same way the released one does.
LABEL org.opencontainers.image.source="https://github.com/Tykok/Kanso"
LABEL org.opencontainers.image.description="Kanso — projects, tickets and the documents that explain them, in one image."
LABEL org.opencontainers.image.licenses="AGPL-3.0-or-later"

ENV NODE_ENV=production
# Loopback, so the API is unreachable except through Caddy and the routing table.
ENV SERVER_ADDRESS=127.0.0.1
# `application.yml` defaults this to ./data/secret.key, which inside the image would be a
# path in a layer and would be gone on the next `docker run`.
ENV KANSO_KEY_FILE=/data/secret.key
# Caddy's ACME state, which is $XDG_DATA_HOME/caddy. Without it every restart asks Let's
# Encrypt for a new certificate and the rate limit arrives inside a week.
ENV XDG_DATA_HOME=/data
ENV JAVA_OPTS=""
# Turns a failed oneshot into a stopped container, which is what makes `kanso-init`'s
# refusals refusals rather than warnings.
ENV S6_BEHAVIOUR_IF_STAGE2_FAILS=2

# 80 and 443 under KANSO_TLS=auto; 80 alone under `off`. Not the 8080 the design's process
# table names for `off`, because the API holds 127.0.0.1:8080 and a second bind on the same
# port is EADDRINUSE — Caddy runs as root here, so :80 costs nothing and leaves one port to
# point a proxy at in either mode. Caddy is the only process either line refers to.
EXPOSE 80 443

# Flyway plus a Spring context is slow enough that a short start period would report a
# healthy-then-unhealthy flap on a cold database.
HEALTHCHECK --interval=15s --timeout=5s --start-period=120s --retries=3 \
	CMD ["/usr/local/bin/kanso-healthcheck"]

ENTRYPOINT ["/init"]
