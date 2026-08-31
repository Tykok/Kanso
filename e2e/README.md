# End-to-end tests

Playwright, against the real `docker compose` stack: Postgres, the API and the web
client. What these tests check is largely server behaviour — the disposition plans,
the renumbering, the 403s — so there is nothing to simulate. The configuration has
**no** `webServer`: the stack is assumed to be up already.

## Bringing the stack up

From the repository root:

```bash
KANSO_AUTH_MODE=dev docker compose up -d --build --wait
```

`KANSO_AUTH_MODE=dev` is required. In that mode identity comes from the
`X-Kanso-User` header and nothing is verified: two people are playable in one test,
which the permission scenarios need. Under `oidc`, the default mode, there is no
automatable sign-in path.

The web listens on <http://localhost:3000>, the API on <http://localhost:8080>. Both
addresses are overridable with `KANSO_WEB_URL` and `KANSO_API_URL`.

If this checkout is a worktree sharing the machine with another Kanso checkout, also
set a distinct `COMPOSE_PROJECT_NAME` (and, if the default ports are already taken,
`POSTGRES_PORT` / `API_PORT` / `WEB_PORT`) so this stack gets its own containers and
volumes rather than reusing another checkout's. If you change `WEB_PORT`, also set
`KANSO_WEB_ORIGIN` to match — without it CORS silently makes every visitor render as a
member, which turns scenario 13's permissions test into one that cannot fail. See the
root `docker-compose.yml`: its named volumes carry no fixed name, so they are
namespaced by the compose project automatically.

## Installing and running

```bash
pnpm install
pnpm exec playwright install chromium
pnpm test:e2e
```

One file:

```bash
pnpm exec playwright test e2e/crud.spec.ts
```

One test, watching the browser work:

```bash
pnpm exec playwright test e2e/keyboard.spec.ts --headed --debug
```

The HTML report from the last run:

```bash
pnpm exec playwright show-report
```

## The captures, which are not a scenario

`shots.spec.ts` writes the five PNGs the public site's walk-through shows, into
`site/media/`. It is tagged `@shots` and `playwright.config.ts` keeps it out of every
other run, because it writes files and because it needs a database no `Atlas` has been
created in — ticket identifiers come off a counter on the team row, and a picture
captioned `KAN-1` has to show `KAN-1`. It refuses to run otherwise rather than
photographing `KAN-47`.

```bash
docker compose down -v && KANSO_AUTH_MODE=dev docker compose up -d --build --wait
pnpm shots
```

The files are never committed: they are uploaded to the host named in `site/media.json`.
Its dates are absolute, in September 2026, and the run goes red once they are past —
deliberately, so the site is never handed a walk-through of missed deadlines. Move
`PLAN` forward when that happens.

## The import, which needs a workspace

`import.spec.ts` (scenario 23) walks screen 24's five steps and then asserts the rows the
import wrote. It cannot run against a workspace nobody has, and with no `NOTION_TOKEN` the
dialog's first step prints a sentence and there is nothing to walk — so the suite brings
its own workspace: `notion-workspace.ts` answers Notion's own HTTP API on port 8099, with
three related bases, a status column called `Etat` and options called `En cours`.

The seam is `NOTION_BASE_URL`, which `application.yml` already reads. Pointing the API at
the suite's workspace puts the real `HttpNotionClient` under test — its search-filter
fallback, its cursors, its property parsing — rather than a fake bound inside the
application, which is the whole reason the workspace lives here and not in
`apps/api/src/main`. The stack therefore needs two more variables than the others:

```bash
KANSO_AUTH_MODE=dev NOTION_TOKEN=e2e-stub-token \
  NOTION_BASE_URL=http://host.docker.internal:8099/v1 \
  docker compose up -d --build --wait

KANSO_NOTION_STUB=1 pnpm exec playwright test e2e/import.spec.ts
```

Two variables on the stack, one on the runner, and they are not interchangeable: the first
two are read when the container boots, and `KANSO_NOTION_STUB` is read by Playwright.

`host.docker.internal` is the machine running the suite, seen from inside the container;
`docker-compose.yml` maps it with `extra_hosts` so this holds on Linux as well as on Docker
Desktop. The port is fixed because `NOTION_BASE_URL` is read when the container boots and
the server starts when the spec does — override both together with
`KANSO_NOTION_STUB_PORT`.

Without `KANSO_NOTION_STUB=1` the scenario **skips**, with the command above in the skip's
message, so a default `pnpm test:e2e` stays green and says plainly that one scenario did not
run. That flag is the *whole* decision, on purpose. The tempting guard — ask the API whether
the three bases are there and skip if they are not — makes the precondition the feature under
test: break discovery and the scenario would skip on a correctly configured stack, and a
green suite with one skip looks exactly like "nobody set a workspace up". With the flag set,
the source list is an `expect` at the top of the test and an unreachable or incomplete
workspace fails loudly, naming the command it needs.

Nothing else in the suite minds a configured token: the mirror has no databases to push to,
so the outbound worker fails its jobs against `NotBootstrapped` and reaches no network at
all — which the import spec also asserts, by recording every write its workspace was asked
for and expecting none.

It is re-runnable against a long-lived database: a page is imported exactly once —
`notion_import_origin` has the Notion page id as its primary key — so every id and title
the workspace answers carries a fresh per-run suffix. Without that the second run would be
a test of the skipping path wearing the first run's assertions.

## Identities

Two accounts, created on the spot by `dev` mode:

| Address             | Role    | What it is there to show                        |
| ------------------- | ------- | ----------------------------------------------- |
| `owner@kanso.test`  | owner   | Team writes, which are admin-only               |
| `member@kanso.test` | member  | What a member does not see                      |

`seedInstance()`, called in every file's `beforeAll`, claims the instance for the
owner if it is brand new and marks both accounts as having been through the
preferences step. Without that second point the application redirects to `/setup` and
no test ever sees a list. The operation is idempotent: the stack is long-lived, and
the suite is replayed against it without being reset.

Every page sets its identity with `context.addInitScript`, which writes
`localStorage["kanso.devUser"]` before the page's first script runs and replays on
every navigation.

## Isolation

One worker, no parallelism: the tests share a database. Each test creates its entities
under a unique name (`unique()`, `uniqueKey()`), so re-running the suite against an
already-populated database works — it accumulates, it does not break. To start from
scratch:

```bash
docker compose down -v && KANSO_AUTH_MODE=dev docker compose up -d --build --wait
```

## The twelve scenarios

| File                     | What it holds                                                                |
| ------------------------ | ---------------------------------------------------------------------------- |
| `crud.spec.ts`           | 1. Create team, sub-team, project, team-less project, ticket. 2. Scopes.      |
| `permissions.spec.ts`    | 3. A member's team `⋯` holds only project creation; an admin's holds all five.|
| `disposition.spec.ts`    | 4. Delete a team keeping everything: re-homing and renumbering.               |
| `keyboard.spec.ts`       | 5. Keyboard non-regression.                                                  |
| `archive.spec.ts`        | 6. Archive a team: counts follow the plan; Show archived and unarchive.       |
|                          | 7. Archive a project; a failed unarchive in the topbar error line.            |
|                          | 8. Reparenting refreshes the list; a dialog gives focus back; ⌘K in the composer.|
| `mouse.spec.ts`          | 9. The New menu creates into the scope you are standing in.                   |
|                          | 10. The brand menu: identity, the hinted entries, the version, sign-out.      |
|                          | 11. A ticket row worked entirely by mouse: pills, rename, archive, delete.    |
| `12-timeline.spec.ts`    | 12. An arrow drawn by hand, then a resize the scheduling engine cascades.     |

Scenario 5 is the point of the whole thing. The action registry rewrites the keyboard
path; this test is what says whether behaviour moved with it. When an assertion is in
doubt, the reference is what the key did before the switch, not what one would like it
to do.

Scenario 12 is the only one that reaches the scheduling engine. Everything the timeline
was built against before it was a stub — geometry under Vitest, components under a
harness answering with fixtures — so this is where a gesture in the browser is shown to
reach Kotlin and come back as pixels. It reads its answers twice, off the chart and out
of the API, because a bar that moved and a bar that merely repainted look the same.

Two of its handles carry no accessible name and are therefore pressed by coordinate: the
resize grip, the last six pixels inside a bar's right edge, and the link handle, which
begins one pixel past it. Neither does anything when pressed — only when dragged — and
`bar.tsx` explains why naming them would promise an activation that does not exist.

## The database the suite needs, and the one it will get

Three of these scenarios can only pass against an instance the suite itself claimed.
`seedInstance` calls `POST /api/setup/owner` **only when `needsOwner` is true**, so on a
volume where somebody has already been through the wizard by hand, `owner@kanso.test` is
provisioned as a plain member with its email as its display name — and scenario 10 asserts
the role, scenario 18 asserts the name, and every other scenario dies at
`Could not create the team …` three call frames from the identity that actually caused it.
`users_single_owner` is a unique index, so there is no fixing it by promoting the test
account beside the existing owner.

Rather than wipe a working dev database, give the suite its own stack:

```bash
COMPOSE_PROJECT_NAME=kanso-e2e POSTGRES_PORT=5442 API_PORT=8090 WEB_PORT=3010 \
  KANSO_WEB_ORIGIN=http://localhost:3010 KANSO_AUTH_MODE=dev \
  docker compose up -d --build --wait

KANSO_API_URL=http://localhost:8090 KANSO_WEB_URL=http://localhost:3010 pnpm test:e2e
```

`KANSO_WEB_ORIGIN` is not optional once `WEB_PORT` moves: without it CORS renders every
visitor as a member, which turns scenario 3 into a permissions test that cannot fail.

A long-lived stack is still supported and the suite is replayed against it — which is a
constraint on how a scenario asserts, not only on how it seeds. Anything that counts rows
matching a *name* eventually counts other runs' rows too: pages started from the same
template are all called "Decision", and `getByRole("button", { name: "Close" })` matched
four sidebar teams called `Closed-…` before it matched the button. Find your own row by
its id or its href.

## Nothing is anonymous in dev mode

`DevAuthenticationFilter` authenticates every request, falling back to `dev@kanso.local`
when no `X-Kanso-User` header is attached. So a Playwright context with no credentials is
not an anonymous caller here, and **no assertion made from this suite can prove that a
route requires a session** — a `401` guard written to prove it will read `200` and a
missing guard will look like a pass. The public surfaces' anonymity is proved in process
by `PublicLeakTest`; scenario 22 pins the mode instead, so its content assertions still
mean something.

## Queries

New files query by role and accessible name. A label whose text is an `sr-only` span is
invisible to `getByLabel`, which matches a label's *rendered* text — the property chips on
the ticket page are labelled that way, and `getByRole("combobox", { name: … })` is what
reaches them, through the same accessibility tree a screen reader reads. `follow-ups.md` holds it against the older
scenarios that they reach for private CSS classes — `.row`, `.status`, `.shortcuts` —
which couples the suite to the stylesheet and breaks on refactors that changed nothing a
person can see. Timeline bars are `role="button"` named `${identifier}: ${title}`, tray
chips are named the same way, and a dependency arrow is a focusable path whose `<title>`
reads `${predecessor} → ${successor}`.

## Dates in the fixtures

`seedTicket` takes `start` and `due` as `YYYY-MM-DD` strings and posts them as floating
instants — `hasTime: false`, the shape a Gantt column is. They are seeded over HTTP
rather than typed in because no screen in the application gives a ticket a *start*: the
detail panel offers a due date and nothing else, and the only other path is the timeline
itself, which is the thing under test.

## Stamping a version

`docker compose build` reads `KANSO_COMMIT` and passes it to both halves — the web
bundle inlines it as `NEXT_PUBLIC_KANSO_COMMIT`, and the API's Gradle build stamps it
into `build-info.properties` for `/api/me` to report. Without it both say `dev`, which
is what makes the brand menu's version footer show one line instead of a skew warning:

```bash
KANSO_COMMIT=$(git rev-parse --short HEAD) docker compose build
```

