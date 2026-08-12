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

## Queries

New files query by role and accessible name. `follow-ups.md` holds it against the older
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

