# Self-hosting Kanso

One image, one domain, your own Postgres.

`ghcr.io/tykok/kanso` carries the front end, the API and a reverse proxy that decides
which of the two answers each request. Everything a browser, an agent or a webhook talks
to arrives at **one origin**, on **one port**. There is no second host to configure, no
CORS to allow and no build-time API URL, because the front end asks for `/api/...` and
the browser resolves that against whatever served the page.

The database is not in the image and never will be — [why](#why-postgres-is-not-in-the-image).

`linux/amd64` and `linux/arm64`. Tags are releases: `v1.4.2` pins one, `1.4` follows its
patches, `latest` follows everything.

---

## The two things you configure

```bash
KANSO_PUBLIC_URL=https://kanso.example.com
SPRING_DATASOURCE_URL=jdbc:postgresql://db.internal:5432/kanso?sslmode=require
SPRING_DATASOURCE_USERNAME=kanso
SPRING_DATASOURCE_PASSWORD=…
```

**The container refuses to start without them**, with a sentence naming the variable,
about a second in. That is deliberate: a missing `SPRING_DATASOURCE_URL` would otherwise
become a Hikari stack trace two minutes later, pointed at `localhost:5432`, which inside
this container is nothing at all.

`KANSO_PUBLIC_URL` is the URL **a browser** reaches this instance at, scheme included. It
is the single source of truth for where the instance lives, and everything else is derived
from it:

- `KANSO_WEB_ORIGIN`, which the CORS policy, the WebSocket handshake check and every OAuth
  redirect URI are built from. You are not meant to set it. If you set it anyway, yours
  wins and the container says so on stdout.
- the address Caddy binds, and the name it obtains a certificate for.

Get it wrong and nothing fails at boot. The failure is at sign-in: Google refuses a
redirect URI it was not registered with, or the WebSocket handshake is rejected because
the `Origin` header does not match a string compared exactly. **A trailing slash, a port
that is the container's rather than the browser's, or `http` where the browser sees
`https` are all the same bug.**

One optional variable, `KANSO_TLS` — [its own section](#tls-and-who-the-edge-is).

Everything else Kanso reads (`KANSO_SECRET_KEY`, `NOTION_*`, `GOOGLE_*`, `GITHUB_*`,
`KANSO_API_TOKEN_RATE_LIMIT`, `KANSO_WEBHOOK_*`, `KANSO_AUTH_MODE`) passes through
unchanged. The repository's `.env.example` documents them.

---

## Prepare the database

Kanso creates its own tables — Flyway runs at boot, every boot, and one application
container means there is nothing to coordinate. What it cannot create is the role, the
database and one extension:

```sql
CREATE DATABASE kanso;
CREATE USER kanso WITH PASSWORD '…';
\c kanso
CREATE EXTENSION IF NOT EXISTS pg_trgm;   -- superuser
GRANT ALL ON SCHEMA public TO kanso;      -- Flyway creates the tables
```

**`pg_trgm` is the line that fails on a managed instance.** `V10__cycles_and_views.sql`
runs `CREATE EXTENSION IF NOT EXISTS pg_trgm` itself, so on a Postgres where the `kanso`
role is a superuser you can skip it. On a managed one the role usually is not, and the
extension needs privileges the provider may withhold: RDS, Supabase and Neon all allow it,
a locked-down corporate cluster generally needs a DBA to run that one line for you. The
symptom is the container exiting during Flyway with `permission denied to create
extension "pg_trgm"`, and it is the first thing to check when a first boot fails.

Postgres 16 is what is tested. Anything with `pg_trgm` and a recent enough JDBC dialect
will work; nothing in the schema is exotic.

---

## Running it

### You already have Postgres

```bash
docker run -d --name kanso \
  -p 80:80 -p 443:443 \
  -v kanso-data:/data \
  -e KANSO_PUBLIC_URL=https://kanso.example.com \
  -e SPRING_DATASOURCE_URL='jdbc:postgresql://db.internal:5432/kanso?sslmode=require' \
  -e SPRING_DATASOURCE_USERNAME=kanso \
  -e SPRING_DATASOURCE_PASSWORD=… \
  ghcr.io/tykok/kanso
```

Quote the JDBC URL. `?sslmode=require` contains a `?` and `&` separates further
parameters, both of which a shell will happily eat.

**If Postgres runs on this machine but outside Docker, the host is not `localhost`.**
Inside the container `localhost` is the container, so a URL naming it reaches nothing and
the boot ends in `connection refused`. It is the commonest way this step fails, because
the address that works from your shell is the one address that cannot work from in here.
Name the host explicitly instead:

```bash
docker run -d --name kanso \
  --add-host=host.docker.internal:host-gateway \
  -e SPRING_DATASOURCE_URL='jdbc:postgresql://host.docker.internal:5432/kanso' \
  … ghcr.io/tykok/kanso
```

`--add-host` is what makes that name resolve on Linux, where Docker does not define it on
its own; on Docker Desktop it already exists and the flag is harmless. Postgres also has
to be listening on an address the container can reach — `listen_addresses` covering more
than `localhost`, and a `pg_hba.conf` line admitting the Docker bridge network — which is
a change to your Postgres, not to Kanso.

### You do not

Copy [`docker/docker-compose.yml`](../docker/docker-compose.yml) out of the repository —
it ships a `postgres:16-alpine` beside the application, with a health check and a
`depends_on` so the API does not meet an initdb still creating the cluster:

```bash
curl -O https://raw.githubusercontent.com/Tykok/Kanso/main/docker/docker-compose.yml
$EDITOR docker-compose.yml     # KANSO_PUBLIC_URL, and both copies of the password
docker compose up -d
```

That file is not the repository's root `docker-compose.yml`, which builds both
applications from source on two ports and is the development stack.

### Then

Open `KANSO_PUBLIC_URL`. The first launch is a **setup wizard**: you create the owner
account, then optionally link Notion, enable Google sign-in and pick a theme. Everything
after the account is skippable and reopenable from settings. Whoever comes next arrives
through an invitation link the owner generates.

Upgrading is `docker compose pull && docker compose up -d`, or the same `docker run` with
a newer tag. Flyway migrates the schema on the way up. There is no downgrade: take a
`pg_dump` before a major version if the data matters, which it does.

---

## TLS, and who the edge is

`KANSO_TLS` has two values. It defaults to `auto` when `KANSO_PUBLIC_URL` is `https:` and
to `off` when it is `http:`, which is almost always the right guess.

### `auto` — Caddy gets a certificate

Caddy obtains and renews a Let's Encrypt certificate for the host in `KANSO_PUBLIC_URL`.
It needs **ports 80 and 443 reachable from the public internet** — 80 is where the ACME
challenge is answered, and it redirects to 443 the rest of the time. It also needs the
`/data` volume, or every restart requests a new certificate and Let's Encrypt's rate limit
arrives within a week.

### `off` — your proxy gets it

Caddy serves plain HTTP and accepts any host name, because whatever terminates TLS in
front of it already checked the one the browser used.

**It listens on port 80, not 8080.** Both modes, one port: the API holds `127.0.0.1:8080`
inside the container, so a second listener there would be `EADDRINUSE`. Publish it
wherever your proxy expects — `-p 8443:80` and so on. The container side is always 80.

`KANSO_PUBLIC_URL` still says `https://…` under `off`. It describes what the *browser*
sees, and that is what OAuth redirect URIs and the WebSocket origin check have to agree
with. Your proxy must forward `X-Forwarded-Proto: https` — Caddy passes it through and the
API is configured to trust it, which is what keeps
`https://kanso.example.com/login/oauth2/code/google` from being built as `http://…` and
refused by Google.

---

## Under `KANSO_TLS=off`, `X-Forwarded-For` becomes your job

Read this before you deploy behind your own proxy. It is not a footnote.

The API takes the client's address from the **leftmost** entry of `X-Forwarded-For`.
`/connect/register` — the unauthenticated endpoint an MCP agent calls to register itself,
which **writes a row** — is rate-limited at five per address per hour on exactly that
value.

Under `KANSO_TLS=auto`, Caddy is the edge: it discards any inbound `X-Forwarded-For` and
writes the peer address it actually accepted the connection from. The header cannot be
forged, and the limit is real.

**Under `KANSO_TLS=off`, Caddy preserves the header verbatim**, because your proxy is the
edge and its `X-Forwarded-For` carries the only client address anything downstream will
ever see. Replacing it would collapse every visitor on the internet into one bucket. So
the header arrives exactly as your proxy left it, and what it contains is your decision.

**If your proxy appends to `X-Forwarded-For` instead of replacing it** — which is the
default in nginx's common `proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;`
recipe, in Traefik, and in most cloud load balancers — then a caller who sends its own
`X-Forwarded-For: 1.2.3.4` puts that value on the left, and Kanso believes it. A script
rotating that header gets a fresh bucket on every request, which is not a rate limit; it
can register unlimited OAuth clients until the instance-wide ceiling of 200 is reached,
at which point registration is refused **for everybody**, agents included. Two other
places read the same value and become similarly cheap to defeat: the sign-in endpoint's
per-address throttle (a per-account limit is unaffected, so that one is a nuisance rather
than a way in), and the public roadmap's one-vote-per-visitor-per-day check.

The fix is one line in your proxy, and it is to **replace, not append**:

```nginx
# nginx
proxy_set_header X-Forwarded-For $remote_addr;    # not $proxy_add_x_forwarded_for
proxy_set_header X-Forwarded-Proto $scheme;
proxy_set_header X-Forwarded-Host $host;
```

```caddyfile
# Caddy as your own edge. `reverse_proxy` appends rather than replaces, exactly as the
# one inside the image does, so the inbound header has to be dropped by hand first.
request_header -X-Forwarded-For
reverse_proxy kanso:80
```

Traefik: set `forwardedHeaders.insecure = false` and list only your own ingress in
`forwardedHeaders.trustedIPs`. Cloudflare, ALB and Google's load balancers append, so read
the rightmost-trusted entry and rewrite the header before it reaches Kanso.

If none of this is under your control, `KANSO_TLS=auto` and letting Caddy terminate TLS is
the configuration where Kanso can answer the question itself.

---

## What `/data` holds

Two things that must survive a restart:

| | |
|---|---|
| `/data/secret.key` | The AES key encrypting the Notion token and the OAuth client secrets the setup wizard stored. Generated on first boot unless `KANSO_SECRET_KEY` is set. Lose it and those secrets have to be entered again. |
| Caddy's ACME state | The certificate and account key, under `KANSO_TLS=auto`. Lose it and every restart asks Let's Encrypt for a new certificate. |

Nothing else. Tickets, documents, attachments and sessions are all in Postgres.

**The container is stateless** — no volume at all — when you set `KANSO_SECRET_KEY`
explicitly *and* run `KANSO_TLS=off`. Both reasons for `/data` are gone, and what is left
is an image and two environment variables, which is the shape a platform like ECS, Fly or
a Kubernetes `Deployment` wants. That is the configuration to aim for if you have one.

A **bind mount** (`-v /srv/kanso:/data`) arrives owned by root rather than inheriting the
image's ownership; the container fixes that at boot, so it works, but a named volume is
one fewer thing to get wrong.

### One container, not two

Kanso does not scale horizontally today, and running a second replica breaks three things
quietly rather than loudly: the rate limiters count in one heap, the realtime broker is
in-memory, and Flyway runs at boot with no coordination. One container. It is a real
ceiling and it is written down rather than discovered.

---

## Putting your own proxy in front

You do not need to know this to deploy — the image's own Caddy already routes everything.
It matters if you are terminating TLS elsewhere and want to understand what you are
forwarding, or if you are tempted to route paths yourself.

**Do not split these paths across two upstreams.** The API owns several that look like the
front end's, and the failures are silent: a wrong guess sends a working OAuth callback to
Next, which answers 404.

| Path | Served by | Not obvious because |
|---|---|---|
| `/api/*` | API | includes `/api/mcp` and `/api/github/webhook` |
| `/ws` | API | a native WebSocket — your proxy must forward the upgrade |
| `/oauth2/*` | API | `authorize`, `token`, `revoke` |
| `/login/oauth2/*` | API | **the front end serves `/login`** — longest match wins |
| `/oauth/consent` | API | the page an agent's authorisation lands on |
| `/connect/register` | API | dynamic client registration |
| `/.well-known/oauth-*` | API | MCP discovery — outside `/api`, and required |
| everything else | web | |

Forward the whole origin to port 80 of the container and this table is already handled.

`/actuator/*` is deliberately **not** routed and is unreachable from outside. There is no
health URL to point a load balancer at; use the container's own `HEALTHCHECK`, which asks
the API on the loopback. `docker inspect -f '{{.State.Health.Status}}' kanso` reads it.

Its start period is two minutes, because a Spring context plus Flyway against a cold
database is slow enough that a shorter one reports a healthy-then-unhealthy flap.

---

## Why Postgres is not in the image

Because Postgres does not migrate its on-disk format across major versions. The day this
image moved from `postgres:16` to `postgres:17`, every bundled data directory would become
unreadable at boot, and the fix would be a `pg_upgrade` inside a container that has just
crashed, on your production data. Separate, you choose when to move.

Two smaller reasons point the same way. A `docker run` without the right `-v` loses a
bundled database silently on `docker rm`, where a missing volume on a visibly separate
database container does not. And bundling would make *connecting an existing database* —
the thing self-hosting is usually for — the second branch in the entrypoint, and therefore
the less tested one.

---

## When it does not come up

| Symptom | Cause |
|---|---|
| Exits in a second, `kanso: KANSO_PUBLIC_URL is not set…` | It says what to set. |
| Exits during Flyway, `permission denied to create extension "pg_trgm"` | The role is not a superuser. [Above](#prepare-the-database). |
| Exits during Flyway, connection refused | `SPRING_DATASOURCE_URL` names a host this container cannot reach. On Compose that is the service name; for a Postgres on this machine but outside Docker it is `host.docker.internal`, never `localhost`. [Above](#you-already-have-postgres). |
| Health never goes green, no error in the log | Give it two minutes on a cold database before believing it. |
| Sign-in with Google returns `redirect_uri_mismatch` | `KANSO_PUBLIC_URL` disagrees with what the browser used, or your proxy is not sending `X-Forwarded-Proto: https`. |
| Signed in, but the board never updates by itself | The WebSocket handshake was rejected. Same cause: the `Origin` header is compared to `KANSO_PUBLIC_URL` exactly. |
| No certificate under `KANSO_TLS=auto` | Port 80 must be reachable from the public internet for the ACME challenge, not only 443. |
| An agent's `claude mcp add` gets a 404 | `/.well-known/oauth-authorization-server` is being routed to the front end. Forward the whole origin. |

`docker logs` is the first thing to read; the entrypoint prints the origin it derived, the
address Caddy bound and the `KANSO_TLS` it resolved, on one line, before anything else
starts.
