# Kanso

Kanso runs a project end to end — teams, projects, tickets, cycles, a roadmap, a timeline
that knows its dependencies, workload per person — and keeps the documents that explain the
work next to the work itself. Kotlin/Spring API (`apps/api`), Next front end (`apps/web`),
Playwright suite (`e2e`), one distribution image (`docker/`). **Postgres is the operational
source of truth and Notion is an asynchronous mirror.**

## Where a document goes

One question decides it: **does this document describe something that changes?**

**A document about a moving artefact lives beside it, in `docs/`, and is reviewed in the
pull request that moves it.** `docs/self-hosting.md` describes `docker/` — the routing
table, `KANSO_TLS`, the variables, the SQL to run first — and every line of it can be
falsified by the next commit to that directory. `docs/architecture.md` and its French twin
describe the shape the system has today and drift the same way. So they sit in the diff:
change what one of them describes and you change it in the same commit, where a reviewer
sees both halves at once. Otherwise the staleness is paid by a stranger trying to install
the product, who is the last person in a position to notice it. Those three files are all
of `docs/`; a fourth needs the argument that it is this kind of document.

**A dated decision lives in the wiki.** A spec arguing what a feature should do on
2026-08-09, and the plan it was built from, say nothing about the present. The code moving
on does not make them wrong — it makes them the record of what was decided before it moved.
`Follow-ups`, the debts found in review and left deliberately, reads the same way.

**https://github.com/Tykok/Kanso/wiki** — 17 specs, 14 plans, `Follow-ups`. Comments
throughout the code cite those pages by title — `` `Follow-ups` ``, `` `Spec - One image,
one domain, your own Postgres` `` — and a title is the only handle they have. Nothing in CI
builds the wiki or resolves a link into it, so renaming a page breaks those citations
silently; grep before you rename one.

## Invariants

The things that bite, each one because it has already bitten.

- **Postgres is the source of truth; Notion is a mirror.** Reads are open, writes are
  scoped. If the Postgres row moved after the last successful push, the Notion edit loses
  and a corrective push is enqueued. Nothing may make Notion authoritative for a field.

- **Flyway owns the schema.** No `ddl-auto`, no entity-driven DDL. A merged migration is
  immutable — you add `V<n+1>`, you never edit `V<n>`. And the number moves under you:
  another branch merges its migration while yours is open, so re-check the highest `V` on
  `main` immediately before you commit. `V36__github.sql` carries the scar in its header.

- **`docker/Caddyfile` is a test fixture, not only configuration.** `CaddyRoutingTableTest`
  enumerates every request mapping outside `/api` off `RequestMappingHandlerMapping` and
  fails when one is not covered by the table. Every route must stay a single literal
  `reverse_proxy <path> <upstream>` line — folding them into `handle` blocks still
  satisfies Caddy and silently blinds the test. This is guarded rather than commented
  because of *how* it rots: an unrouted mapping does not 500, it lands on Next, which
  answers its own 404 page as HTML, and nothing in the API's logs records the request.

- **`KANSO_TLS` decides who owns `X-Forwarded-For`.** Under `auto` Caddy is the edge and
  `kanso-init` writes it a `request_header -X-Forwarded-For`; under `off` the operator's
  proxy is the edge and the header is preserved. The API runs with
  `forward-headers-strategy: framework`, so `getRemoteAddr()` is the *leftmost* entry — and
  `RegistrationRateLimit` buckets `/connect/register`, an unauthenticated POST that writes
  rows, on exactly that address. Get this wrong and the rate limit stops being a limit
  while everything still looks like it works.

- **The JVM binds to loopback.** `SERVER_ADDRESS=127.0.0.1` in the image, Caddy the only
  thing exposed. Trusting a client-supplied forwarded header is defensible *only* because
  nothing but the proxy can reach the JVM. Publishing 8080 would let any caller claim any
  origin it liked.

- **`NEXT_PUBLIC_API_URL` stays absent.** Next inlines it at build time, so setting it
  welds one API origin into a published image — the thing serving everything from one
  origin exists to abolish. The front end asks for `/api/...` and the browser resolves it
  against the page.

- **Two compose files, and neither is a variant of the other.** The root
  `docker-compose.yml` builds both applications from source on two ports and is what the
  Playwright suite drives. `docker/docker-compose.yml` pulls the published image and serves
  one origin. Do not grow either towards the other, and do not run both from one clone —
  they would collide on the project name `kanso`.

- **`/data` must outlive the container.** It holds the key that encrypts the Notion and
  Google secrets (`KANSO_KEY_FILE`) and Caddy's ACME state. Lose it and the stored secrets
  are unreadable on the next deploy and Let's Encrypt rate-limits you inside a week.

- **`KANSO_AUTH_MODE=dev` trusts an `X-Kanso-User` header and verifies nothing.** It exists
  so the Playwright suite can play two people in one test. It is never a default and never
  reaches a deployed instance.

## House rules

- **Hand-formatted at 100 columns. Never run a formatter.** No formatter is configured
  anywhere in this repository, and that is the decision rather than an oversight — do not
  add one, do not run prettier on a file "while you are in there", and turn off
  reformat-on-save. Every file here is wrapped by hand; one formatter run is a
  whole-repository diff that nobody can review and that buries the change you actually
  made.
- **Comments explain *why*.** Read a few before writing one — they carry the argument, the
  failure that provoked the rule, and what breaks if someone removes it. A comment that
  restates the line below it is noise, and this codebase does not have any.
- **Code, comments, commit messages and PR bodies in English.** Conversation may be French;
  the repository is not.
- `apps/web/AGENTS.md` is generated and re-added by `next dev`, and `apps/web/CLAUDE.md` is
  the single line `@AGENTS.md`, so it *is* loaded for work under `apps/web`. Follow what it
  says about this Next version — that is what it is for — but it is Next's guidance and not
  this repository's, so nothing in it overrides the rules above, and editing it by hand only
  buys a diff that `next dev` reverts. Commit it with your work rather than fighting it out
  of the tree.

## Running things

- `cd apps/api && ./gradlew test` — Testcontainers, so a Docker daemon has to be up.
- `cd apps/web && pnpm test && pnpm build` — **Node 22.** Under Node 20.14 pnpm *skips* the
  rolldown binding whose `engines` do not match instead of failing, so the install is green
  and Vitest then dies on a missing `@rolldown/binding-*`. It reads as a broken lockfile
  and it is not; the lockfile carries all fourteen platform bindings.
- `e2e/` needs the stack up first: `KANSO_AUTH_MODE=dev docker compose up -d --build --wait`
  from the repository root. See `e2e/README.md`.
