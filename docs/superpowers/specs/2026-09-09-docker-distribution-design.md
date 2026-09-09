# One image, one domain, your own Postgres

Running Kanso today means cloning the repository, copying `.env.example`, and letting
`docker compose` build a JVM and a Next bundle on your machine. That is a development
workflow wearing a deployment costume. Someone who wants to *use* Kanso should pull an
image and run it.

This design makes that true, and it makes one promise that shapes every decision below:
**the person deploying Kanso configures two things — the URL it lives at, and the
database it writes to.** Everything else has a defensible default or is derived.

The premise worth stating before anything else: **Kanso does not own your data
directory.** Postgres stays outside the image, always. Which is the one place this
design says no to the shortest possible `docker run`, and the reason is in
[Postgres is not in the image](#postgres-is-not-in-the-image).

## What this is not

- **A two-domain deployment.** There is one origin. The API is not separately
  addressable from outside, and `api.example.com` is not a supported topology. This is
  what removes CORS, the `SameSite` cookie question, and the build-time API origin all
  at once — see [One origin](#one-origin-and-what-it-buys).
- **A Postgres appliance.** No bundled database engine, no SQLite, no MySQL. The image
  refuses to start without a `SPRING_DATASOURCE_URL`.
- **Horizontally scalable.** One container. The rate limiters count in one heap
  (`ApiTokenRateLimit`), the STOMP broker is `enableSimpleBroker` and in-memory, and
  Flyway runs at boot with no coordination. A second replica breaks all three quietly.
  That is a real ceiling and it is not this ticket's to raise.
- **A Helm chart, an operator, or a `:edge` channel.** Tags are releases.
- **A change to the existing `docker-compose.yml`.** That file is the development stack
  and the Playwright harness. It keeps building from source and keeps its two ports. The
  distribution artefact is a different file with a different job.

## One origin, and what it buys

The browser talks to one host. Caddy, inside the image, decides whether each request is
the web app's or the API's.

This is the load-bearing decision. Three problems disappear because of it rather than
being solved:

**The build-time API origin.** Next inlines `NEXT_PUBLIC_*` at build time, so a published
image has its API URL welded in. With one origin the front end asks for `/api/...` — a
relative path, resolved by the browser against wherever the page came from. There is
nothing to inline, so there is nothing to configure.

**CORS.** `WebConfig` allows exactly one origin and `WebSocketConfig` checks the same
value on the handshake. Same-origin requests never ask.

**The session cookie.** `application.yml` sets `same-site: lax`, which works in
development only because `localhost:3000` and `localhost:8080` are the same site. Across
`kanso.example.com` and `api.other.com` it would not be, and the failure — a login that
appears to succeed and then does not — is among the worst to diagnose. Same origin, no
question.

### The routing table

The API owns paths outside `/api`, and this is the fact that makes shipping a proxy
non-negotiable: **no user could derive this table**, and getting it wrong yields a 404
from Next where a working OAuth callback should be.

| Path | Served by | Why it is not obvious |
|---|---|---|
| `/api/*` | API | includes `/api/mcp` and `/api/github/webhook` |
| `/ws` | API | native WebSocket, no SockJS fallback — the proxy must upgrade |
| `/oauth2/*` | API | `authorize`, `token`, `revoke`, `authorization/{provider}` |
| `/login/oauth2/*` | API | **the front end serves `/login`** — longest match wins |
| `/oauth/consent` | API | `AuthorizationServerConfig.CONSENT_PAGE` |
| `/connect/register` | API | dynamic client registration, `OAuthRoutes.OPEN_POST` |
| `/.well-known/oauth-*` | API | MCP discovery, `OAuthRoutes.OPEN_GET` |
| everything else | web | |

`/actuator/*` is deliberately absent: it is not routed, so it is not reachable from
outside. The container's `HEALTHCHECK` reaches it on `127.0.0.1:8080` directly.

Caddy matches by specificity, so `/login/oauth2/*` beats the catch-all without anyone
maintaining an order. That is the only reason this table is expressible in twelve lines.

### The guard test

A routing table in a config file rots the moment someone adds a controller. The failure
is silent — a new endpoint outside `/api` simply lands on Next and answers HTML — and it
lands on whoever deployed, not on whoever wrote the controller.

So the Caddyfile becomes a test fixture. A Kotlin test enumerates every request mapping
in the application context, keeps those not under `/api`, and fails if one is not covered
by a route in `docker/Caddyfile`. `OAuthRoutes` already carries a guard test of this
shape, and the reasoning is the same: a set that must stay in step with something else is
worth a test that says so.

## Postgres is not in the image

Bundling Postgres would buy a `docker run` with no compose file and no prerequisites. It
was considered and rejected, for one reason that outlives the convenience.

Postgres does not migrate its on-disk format across major versions. The day this image
moves from `postgres:16` to `postgres:17`, every bundled-mode data directory becomes
unreadable at boot, and the fix is a `pg_upgrade` inside a container that has just
crashed, on someone's production data. A single maintainer would own that migration, for
every user, at every major version. With a separate `postgres:16` container the user
chooses when to move, and it never reaches this repository.

Two smaller reasons point the same way. A `docker run` without the right `-v` loses the
database silently on `docker rm`, where a missing volume on a visibly separate database
container does not. And bundling would make **connecting an existing database** — the
thing this work is for — the second branch in the entrypoint, and therefore the less
tested one.

The distribution compose file ships a `postgres:16-alpine` service *beside* the app for
people who have no database. One command either way; the engine is never inside.

### What the operator must prepare

`V10__cycles_and_views.sql` runs `CREATE EXTENSION IF NOT EXISTS pg_trgm`, which needs
privileges an ordinary role does not have on a managed instance. Available on RDS,
Supabase and Neon; on a locked-down corporate cluster it needs a DBA. That belongs in
the README rather than in a Flyway stack trace:

```sql
CREATE DATABASE kanso;
CREATE USER kanso WITH PASSWORD '…';
\c kanso
CREATE EXTENSION IF NOT EXISTS pg_trgm;   -- superuser
GRANT ALL ON SCHEMA public TO kanso;      -- Flyway creates the tables
```

Flyway keeps running at boot. One application container means no race, and the
alternative — a separate migration job — buys coordination nobody needs yet.

The connection is declared as a raw JDBC URL. `SPRING_DATASOURCE_URL`,
`SPRING_DATASOURCE_USERNAME` and `SPRING_DATASOURCE_PASSWORD` are unchanged and
undocumented today; this design documents them rather than wrapping them. A friendlier
`KANSO_DB_HOST`/`PORT`/`SSLMODE` set was considered and dropped: it is a second surface
to keep in step, and every option it did not anticipate — a client certificate, a
`search_path`, a statement timeout — becomes unreachable. The JDBC URL already says all
of it.

## The image

`ghcr.io/tykok/kanso`, `linux/amd64` and `linux/arm64`, three processes under
s6-overlay:

| Process | Listens | Exposed |
|---|---|---|
| Caddy | `:80`, and `:443` under `KANSO_TLS=auto` | yes — the only one |
| `node server.js` | `127.0.0.1:3000` | no |
| `java -jar app.jar` | `127.0.0.1:8080` | no |

Front end, API and proxy share one image because they share one lifecycle: they are
built from one commit, released under one tag, and there is no version of Kanso where
you want one of them without the others. `/api/me` already reports a single
`KANSO_COMMIT`, and the web bundle already carries the same one so the two halves are
comparable — that constraint is easier to keep when there is nothing to keep in step.

s6-overlay rather than a shell script with `wait -n`: PID 1 must reap orphans, the three
have a start order, and **a dead process must kill the container**. A container that
survives with a dead API answers the health check on a port nobody is listening to. The
cost is a real dependency in the image, and it is accepted for the supervision
semantics, which a twenty-line script gets subtly wrong.

### Configuration

Two things must be declared — where the instance lives, and the database it writes to:

```bash
KANSO_PUBLIC_URL=https://kanso.example.com
SPRING_DATASOURCE_URL=jdbc:postgresql://db:5432/kanso?sslmode=require
SPRING_DATASOURCE_USERNAME=kanso
SPRING_DATASOURCE_PASSWORD=…
```

`KANSO_PUBLIC_URL` is the single source of truth for where this instance lives. The
entrypoint derives from it:

- `KANSO_WEB_ORIGIN` — which `WebConfig`, `WebSocketConfig`, `ReturnUrlSuccessHandler`
  and every OAuth redirect already read. The operator never sets it. If they set it
  anyway, theirs wins and the entrypoint says so on stdout, because an operator who
  overrode a variable deserves to see that it took.
- Caddy's site address — the host, which is what makes automatic HTTPS work.

One optional variable, `KANSO_TLS`:

- `auto` (the default when `KANSO_PUBLIC_URL` is `https:`) — Caddy obtains a Let's
  Encrypt certificate. Requires ports 80 and 443 reachable.
- `off` — Caddy serves plain HTTP on `:80` and the operator terminates TLS in their own
  proxy. Port 80 in both modes, and not `:8080`: the JVM already holds `8080` on the
  loopback, so a second bind there is `EADDRINUSE`. It also leaves an operator's proxy
  one port to point at whichever mode they chose. `KANSO_PUBLIC_URL` still says
  `https://…`, because it describes what the *browser* sees, and that is what OAuth
  redirect URIs and the WebSocket origin check must agree with.

The container refuses to start, with a message naming the variable, when
`KANSO_PUBLIC_URL` is missing or is not an absolute `http(s)` URL, or when
`SPRING_DATASOURCE_URL` is missing. Failing at second one with a sentence beats failing
at minute two with a Hikari stack trace.

Every other variable — `KANSO_SECRET_KEY`, `NOTION_*`, `GITHUB_*`,
`KANSO_API_TOKEN_RATE_LIMIT`, `KANSO_WEBHOOK_*` — passes through unchanged.

### The volume

`/data` holds two things that must survive a restart:

- the generated encryption key, today `./data/secret.key` via `KANSO_KEY_FILE`. Lose it
  without having set `KANSO_SECRET_KEY` and the stored Notion and OAuth secrets are
  unreadable.
- Caddy's ACME state. Without it, every restart requests a new certificate and Let's
  Encrypt's rate limit arrives within a week.

An operator who sets `KANSO_SECRET_KEY` explicitly *and* terminates TLS elsewhere needs
no volume at all; the container is then stateless, which is worth saying in the README
because it is the configuration that fits an existing platform.

## The front end: relative by default

This is the only substantial code change, and it is more delicate than it looks.

`lib/api/core.ts` currently defaults `API_URL` to `"http://localhost:8080"`. It becomes:

```ts
export const API_URL = process.env.NEXT_PUBLIC_API_URL ?? "";
```

Twenty-odd call sites read that constant, and they fall into three groups that need
three different answers.

**Group 1 — `fetch(\`${API_URL}${path}\`)`.** `core.ts`, `publik.ts`, `organise.ts`,
`views.ts`, `inbox.ts`. An empty prefix yields a relative path, which the browser
resolves against the page's origin. **No change.** This group is the whole point.

**Group 2 — `href={\`${API_URL}${provider.authorizeUrl}\`}`.** `login/page.tsx`,
`components/login.tsx`, `account-section.tsx`. A relative `href` is resolved by the
browser identically. **No change.**

**Group 3 — values that must be absolute.** Six sites, and each one breaks in its own
way if left alone:

| Site | What it is | Breaks as |
|---|---|---|
| `connections-section.tsx:19` | Google redirect URI to paste into Google | `/login/oauth2/code/google` |
| `setup/google-step.tsx:15` | same, in the wizard | same |
| `setup/notion-connect.tsx:22` | Notion redirect URI | `/api/setup/notion/callback` |
| `settings/github-section.tsx:246` | GitHub App callback URL | `/api/github/link/callback` |
| `lib/api/oauth.ts:62` | `mcpAddCommand()`'s default | `claude mcp add … kanso /api/mcp` |
| `setup/fields.tsx:27` | "No answer from the API at …" | a sentence ending in nothing |

A seventh site is not a display string and matters more than all of them:

**`login/page.tsx:123` — `safeNext(next, API_URL)`.** `next-url.ts:42` does
`new URL(apiOrigin)`, which **throws on the empty string**, is caught, and returns
`HOME`. Fail-closed, so not a vulnerability — but the MCP consent round trip sends the
member to `/login?next=<absolute consent URL>` (`ConsentController.kt:140`), and this
would silently drop them on the home page instead of the consent screen. Authorising an
agent would appear to do nothing. This is the bug that would have shipped.

The answer for all seven is one helper beside `API_URL`:

```ts
/** The API's absolute origin, for URLs that are shown, pasted, or parsed. */
export const apiOrigin = (): string =>
  API_URL || (typeof window === "undefined" ? "" : window.location.origin);
```

A function rather than a constant, and the `typeof window` guard, because **four of these
sites are module-level constants in client components, and Next prerenders client
components on the server at build time.** `window.location.origin` at module scope is a
build failure. Those four constants move inside the component body or into the handler
that uses them.

Where the value reaches rendered HTML — `connections-section`, `google-step`,
`notion-connect`, `github-section`, `agents-section` — the prerendered pass produces the
empty string and the browser produces the origin, which is a hydration mismatch. One
`useApiOrigin()` hook covers all five.

Not written with `useState` and an effect: `react-hooks/set-state-in-effect` refuses that
shape as an error, and `use-narrow.ts` already solved the identical problem with
`useSyncExternalStore`, arguing beside it that "*a value read in an effect is a value the
first paint did not have*". The server snapshot is `API_URL`, the client snapshot is
`apiOrigin`, and React hydrates with the first and corrects in the same commit. Two hooks
solving one problem two ways would have been the worse outcome regardless of the lint.

`realtime.ts:66` builds the WebSocket URL with `API_URL.replace(/^http/, "ws")`, which on
an empty string yields `"/ws"` — a value `new WebSocket()` rejects. It becomes
`apiOrigin().replace(/^http/, "ws")`, and it runs in a browser by construction, so the
guard is inert there.

Development is unaffected: `apps/web/.env.development` sets
`NEXT_PUBLIC_API_URL=http://localhost:8080`, which Next loads for `next dev` and not for
`next build`. The Playwright suite keeps driving `KANSO_API_URL` and `KANSO_WEB_URL` and
needs no change.

## The API: one line, already specified

```yaml
server:
  forward-headers-strategy: framework
```

`ReturnUrlSuccessHandler` documents its own absence in detail: without it Boot's default
of `NONE` applies, so behind a TLS-terminating proxy Spring builds `http://…` URLs,
downgrading the consent redirect and — more visibly — producing a
`http://kanso.example.com/login/oauth2/code/google` redirect URI that Google refuses.
That comment ends "*setting the strategy fixes both at once, which is why it belongs in
deployment rather than here*". This is that deployment.

Caddy sends `X-Forwarded-Proto` and `X-Forwarded-Host` by default, so nothing else is
needed for the scheme. A test asserts that a request carrying `X-Forwarded-Proto: https`
produces an `https` redirect URI, because the symptom otherwise appears only against a
real Google.

### The address the filter also rewrites

`ForwardedHeaderFilter` does not only rewrite scheme and host. It overrides
`getRemoteAddr()` from the **leftmost** `X-Forwarded-For` entry, and Caddy *appends*
rather than replaces — so the leftmost entry is whatever the caller wrote. Three places
read an address:

| Site | Reads | Posture change |
|---|---|---|
| `LocalAuthController:198` | leftmost `X-Forwarded-For`, by hand | none — already forgeable, and its KDoc says so |
| `PublicController:51` | leftmost `X-Forwarded-For`, by hand | none — same, and it concedes the second vote |
| `ClientRegistrationController:41` | `remoteAddr` | **yes** — see below |

Only the third changes. `RegistrationRateLimit` buckets on `remoteAddr` for
`/connect/register`, an unauthenticated POST that writes rows, and its KDoc states the
old assumption outright: "*this application configures no forwarded-headers strategy, so
Boot's default of `NONE` applies*". Today every caller shares the proxy's bucket — five
an hour for the whole instance, a weak limit but a hard one. Honouring a forgeable
header would turn it into a fresh bucket per request, which is not a limit at all.

The fix belongs in the Caddyfile, not in the limiter, and it closes all three at once:

```
header_up X-Forwarded-For {remote_host}
```

Replacing rather than appending makes the leftmost entry the peer Caddy actually saw.
`ClientRegistrationController` gets a real per-caller bucket instead of a per-instance
one, and the two hand-rolled readers stop being forgeable — an improvement their KDocs
had conceded as permanent. Three KDocs assert the old behaviour as standing fact —
`RegistrationRateLimit`, `LocalAuthController:198`, `PublicController:51` — and are
corrected in unit 4 rather than unit 3, which owns no Kotlin. Whoever takes them must
keep the nuance: the forgery closes **only under `KANSO_TLS=auto`**. Under `off` the old
caveat is still true word for word, because the edge is someone else's.

**Except when Caddy is not the edge.** Under `KANSO_TLS=off` the operator's own proxy is,
and replacing the header would discard the client address it forwarded, collapsing every
visitor into one bucket. So the rule follows the variable that already says who the edge
is: `KANSO_TLS=auto` replaces, `KANSO_TLS=off` preserves and `docs/self-hosting.md` states
that sanitising `X-Forwarded-For` is then the operator's job. Fail-closed where Kanso can
know the answer; documented where it cannot.

## Distribution

```
docker/
  Dockerfile              the single application image
  Caddyfile               the routing table, and a test fixture
  rootfs/                 s6-overlay service definitions
  docker-compose.yml      the distribution stack
.github/workflows/release.yml
docs/self-hosting.md
```

The compose file:

```yaml
services:
  kanso:
    image: ghcr.io/tykok/kanso:latest
    restart: unless-stopped
    ports: ["80:80", "443:443"]
    environment:
      KANSO_PUBLIC_URL: https://kanso.example.com
      SPRING_DATASOURCE_URL: jdbc:postgresql://db:5432/kanso
      SPRING_DATASOURCE_USERNAME: kanso
      SPRING_DATASOURCE_PASSWORD: kanso
    volumes: ["kanso-data:/data"]
    depends_on:
      db: { condition: service_healthy }

  db:
    image: postgres:16-alpine
    restart: unless-stopped
    environment:
      POSTGRES_DB: kanso
      POSTGRES_USER: kanso
      POSTGRES_PASSWORD: kanso
    volumes: ["kanso-pgdata:/var/lib/postgresql/data"]
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U kanso -d kanso"]
      interval: 5s
      timeout: 5s
      retries: 20
```

Someone with their own database deletes the `db` service and its `depends_on`, or skips
compose entirely:

```bash
docker run -p 80:80 -p 443:443 -v kanso-data:/data \
  -e KANSO_PUBLIC_URL=https://kanso.example.com \
  -e SPRING_DATASOURCE_URL='jdbc:postgresql://db.internal:5432/kanso?sslmode=require' \
  -e SPRING_DATASOURCE_USERNAME=kanso -e SPRING_DATASOURCE_PASSWORD=… \
  ghcr.io/tykok/kanso
```

### Release

`release.yml`, triggered on tags matching `v*`, with `permissions: packages: write`.
Tags published: the full `v1.2.3`, the minor `1.2`, and `latest`. The repository is
private; the published packages are made public explicitly, once, in the package
settings — GHCR does not inherit repository visibility.

Two jobs rather than one. Job one builds the `bootJar` and the Next standalone bundle
once on native `amd64` and uploads them; job two runs `buildx` for both platforms against
a Dockerfile whose final stage only *copies* those artefacts, leaving the JRE and Caddy —
the only architecture-dependent bytes — to be resolved per platform by their base images.

This was specified to keep Gradle out of QEMU, and **that is no longer what it buys.**
`docker/Dockerfile` pins its build stages to `$BUILDPLATFORM`, so even a plain
`buildx --platform linux/amd64,linux/arm64` compiles once. What the split still buys is
custody: the artefacts exist as a job output that can be attested, retained and inspected
independently of the image that carries them. Worth keeping for that, and the rationale
in the workflow's comments must say *that* rather than the QEMU story it inherited.

One thing is architecture-dependent and does not look it. Next traces `sharp` into
`.next/standalone`, and libvips is a per-architecture binary — an amd64 job would ship
amd64 `.node` files inside the arm64 image. The runtime stage deletes those trees, which
is safe only because nothing under `apps/web/src` imports `next/image`. **The day
something does, the delete has to become a per-platform install.**

`KANSO_COMMIT` is stamped from the tagged commit, so `/api/me` and the web bundle report
the released version rather than `dev`.

## Testing

Four levels, and the last one is the only one that proves the artefact.

**Unit, web.** `apiOrigin()` returns the configured URL when set and `window.location.origin`
when not; `safeNext` accepts an absolute same-origin `next` when the origin is resolved
rather than empty — the regression named above; `mcpAddCommand()` still produces an
absolute command.

**Unit, API.** `X-Forwarded-Proto: https` yields an `https` redirect URI.

**The guard test.** Every non-`/api` request mapping is covered by a `docker/Caddyfile`
route. Adding a controller outside `/api` fails the build rather than the deployment.

**Smoke, on the built image.** `release.yml` builds the image, runs it against a
throwaway Postgres with `KANSO_TLS=off`, waits for the health check, then asserts on one
origin: `/` serves the app, `/api/auth/mode` serves JSON, `/ws` answers a WebSocket
upgrade, and `/.well-known/oauth-authorization-server` serves discovery. Those four
requests are the routing table's whole claim. Nothing is pushed to the registry until
they pass.

Playwright stays out of this. `ci.yml` explains at length why it is absent and that
reasoning is untouched here.

## Implementation order

Five units. The first three are independent of each other; the last two need the
Caddyfile and the image to exist.

1. **Web relative origin** — `apiOrigin()`, `useApiOrigin()`, the seven Group 3 sites,
   `realtime.ts`, `.env.development`, and their unit tests. Proven by `pnpm build`
   completing, which is what catches a `window` at module scope.
2. **API forwarded headers** — one line of `application.yml` and one test.
3. **The image** — `docker/Dockerfile`, `docker/Caddyfile`, s6 services, the entrypoint's
   derivation and its refusals.
4. **The guard test** — needs (3)'s Caddyfile.
5. **Release and documentation** — `release.yml`, `docker/docker-compose.yml`,
   `docs/self-hosting.md`, the README's quick start. Needs (3).

`docker-compose.yml` at the repository root and `.env.example` are not touched by any of
the five.
