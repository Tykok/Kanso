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
volumes rather than reusing another checkout's. See the root `docker-compose.yml`:
its named volumes carry no fixed name, so they are namespaced by the compose project
automatically.

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

## The five scenarios

| File                     | What it holds                                                               |
| ------------------------ | ---------------------------------------------------------------------------- |
| `crud.spec.ts`           | 1. Create team, sub-team, project, team-less project, ticket. 2. Scopes.      |
| `permissions.spec.ts`    | 3. A member's team `⋯` holds only project creation; an admin's holds all five.|
| `disposition.spec.ts`    | 4. Delete a team keeping everything: re-homing and renumbering.               |
| `keyboard.spec.ts`       | 5. Keyboard non-regression.                                                  |
| `archive.spec.ts`        | 6. Archive a team: counts follow the plan; Show archived and unarchive.       |
|                          | 7. Archive a project; a failed unarchive in the topbar error line.            |

Scenario 5 is the point of the whole thing. The action registry rewrites the keyboard
path; this test is what says whether behaviour moved with it. When an assertion is in
doubt, the reference is what the key did before the switch, not what one would like it
to do.

## Stamping a version

`docker compose build` reads `KANSO_COMMIT`; without it the web bundle reports `dev`:

```bash
KANSO_COMMIT=$(git rev-parse --short HEAD) docker compose build web
```

