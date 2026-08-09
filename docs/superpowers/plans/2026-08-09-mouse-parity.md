# Mouse Parity Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give every action in the registry a mouse path, and put the two most frequent
ones — status and priority — one click from where the eye already is.

**Architecture:** Nothing here invents a decision. Every new surface filters the existing
action registry through `when`, and every menu is the `Menu` component that tasks 12 and
17 of the previous branch built and exercised. Two actions are added to the registry
(`ticket.delete`, `app.logout`) because they exist today only inside one component or
nowhere at all. What a new entity inherits from the current scope becomes one pure
function, tested directly, extending the `composerSeed` the previous branch extracted.

**Tech Stack:** Next.js 16.3.0, React 19.2.8, TypeScript 5.9.3 (strict), zustand 5,
TanStack Query 5, Vitest 4.1.10, Playwright 1.62.1; Kotlin/Spring Boot 4.1.0 on the API.

**Spec:** `docs/superpowers/specs/2026-08-09-mouse-parity-design.md`

## Global Constraints

- **pnpm, never npm.** Web commands run from `apps/web`; Playwright from the repo root.
- **Node must be `^20.19.0 || >=22.12.0`.** The machine default is v20.14.0, which is too
  old for vite 8's rolldown binding. Shell state does not persist between commands, so
  every node/pnpm/playwright command carries its own PATH:
  `export PATH="$HOME/.nvm/versions/node/v24.13.0/bin:$PATH"`
- **TypeScript is strict.** No `any`, no unexplained `!`.
- **No React rendering harness.** Do not install jsdom, happy-dom or @testing-library.
  Component behaviour is covered by Playwright, which has a real DOM.
- **Read `apps/web/AGENTS.md` before touching routing, layout or config.** It carries no
  conventions itself; it points at `apps/web/node_modules/next/dist/docs/`. This Next
  version is newer than most training data.
- **Kotlin is indented with tabs.** Raw SQL only through `jdbc.sql(...).param(...)`.
- **No database migration.** Nothing here touches the schema.
- **Docker discipline:** any stack uses its own `COMPOSE_PROJECT_NAME` and its own
  volumes. Never touch `kanso_kanso-data` or `kanso_kanso-pgdata` — they hold
  pre-existing data that is not ours.
- **Never use `screencapture`, `osascript`, or any OS-level screen or UI automation.** In
  this environment they drive the user's real physical desktop. Playwright headless is
  the sanctioned way to see the interface.
- **Every test must fail when the thing it covers is removed.** Each task below names the
  mutation to apply and the test that must go red. Applying it and reporting the result
  is part of the task, not a formality — three features on the previous branch survived
  their own removal before anyone noticed.

## Facts the recon established, so nobody rediscovers them

- `api.logout` **already exists** (`apps/web/src/lib/api.ts:263`). Do not add it.
- `GET /api/me` lives in **`MiscControllers.kt`** (`AuthController`, line 92), *not* in
  `AccountController.kt` — which carries `@RequestMapping("/api/me")` but defines only
  `PUT`s. Adding a `@GetMapping` there would duplicate the route.
- `Menu` returns `null` when `items` is empty (`menu.tsx:51`). That is what already hides
  a `⋯` from someone with no permitted action, for free.
- `sidebar.tsx:69-79` holds a private `menuItems(ctx, ids)` helper. Three more surfaces
  need it; task 1 extracts it rather than copying it a fourth time.
- `apps/api/build.gradle.kts` has **no** `springBoot { }` block yet.
- `apps/web/Dockerfile`'s build stage already does `ARG NEXT_PUBLIC_API_URL` →
  `ENV` → `pnpm build`. The version follows the same path; it is not a new mechanism.
- e2e scenarios are numbered continuously across files, currently 1 through 8. New ones
  start at 9.

## File Structure

| File | Responsibility | Task |
|---|---|---|
| `apps/web/src/lib/actions.ts` | +`ticket.delete`, +`app.logout`, +2 context fields | 1 |
| `apps/web/src/components/menu-items.ts` | shared `menuItems` + the destructive-id set | 1 |
| `apps/web/src/lib/use-action-ctx.ts` | supplies `deleteTicket` and `logout` | 1 |
| `apps/web/src/lib/creation-seed.ts` | what a new entity inherits from the scope | 2 |
| `apps/api/build.gradle.kts` | `springBoot { buildInfo() }` | 3 |
| `apps/api/.../api/Dtos.kt`, `MiscControllers.kt` | `version` on `/api/me` | 3 |
| `apps/web/Dockerfile`, `docker-compose.yml`, `src/lib/version.ts` | web build stamp | 3 |
| `apps/web/src/components/new-menu.tsx` | the top bar's New menu | 4 |
| `apps/web/src/components/brand-menu.tsx` | account, settings, sign out, version | 5 |
| `apps/web/src/components/pills.tsx` | status and priority become menu triggers | 6 |
| `apps/web/src/components/tickets.tsx` | row `⋯`, and the pills wired per row | 6, 7 |
| `e2e/mouse.spec.ts` | scenarios 9, 10, 11 | 8 |

---

### Task 1: Two missing actions, and one shared menu builder

`ticket.delete` exists only inside `DetailPanel`; `app.logout` exists nowhere. Both
become registry actions so every menu in tasks 4-7 lists them the same way it lists
everything else. `menuItems` moves out of `sidebar.tsx` so four surfaces share one
builder rather than four copies drifting apart.

**Files:**
- Create: `apps/web/src/components/menu-items.ts`
- Modify: `apps/web/src/lib/actions.ts` (the `ActionContext` type at 6-23; `ACTIONS`)
- Modify: `apps/web/src/lib/use-action-ctx.ts`
- Modify: `apps/web/src/components/sidebar.tsx:69-79` (delete the local copy, import)
- Modify: `apps/web/src/app/page.tsx` (pass `deleteTicket` through)
- Test: `apps/web/src/lib/actions.test.ts`

**Interfaces:**
- Consumes: nothing from earlier tasks.
- Produces:
  - `ActionContext` gains `deleteTicket: (id: string) => void` and `logout: () => void`.
  - Two actions: `ticket.delete` (label `Delete ticket`, group `ticket`, no shortcut,
    `when: hasSelection`) and `app.logout` (label `Sign out`, group `app`, no shortcut,
    `when: () => true`).
  - `menu-items.ts` exports `menuItems(ctx: ActionContext, ids: string[]): MenuItem[]`
    and `DESTRUCTIVE: ReadonlySet<string>`.

- [ ] **Step 1: Write the failing test**

Append to `apps/web/src/lib/actions.test.ts`, inside the existing `describe("the registry")`:

```ts
  it("offers a delete for the selected ticket, and none without a selection", () => {
    expect(ids(context({ selected: ticket }))).toContain("ticket.delete");
    expect(ids(context({ selected: undefined }))).not.toContain("ticket.delete");
  });

  it("offers sign-out to everyone, member or admin", () => {
    expect(ids(context({ canConfigure: false }))).toContain("app.logout");
    expect(ids(context({ canConfigure: true }))).toContain("app.logout");
  });

  it("runs delete against the selected ticket, and sign-out against the session", () => {
    const deleteTicket = vi.fn();
    const logout = vi.fn();
    actionById("ticket.delete").run(context({ selected: ticket, deleteTicket }));
    actionById("app.logout").run(context({ logout }));
    expect(deleteTicket).toHaveBeenCalledWith(ticket.id);
    expect(logout).toHaveBeenCalledTimes(1);
  });
```

Then add both ids to `REQUIRED_IDS` (the array at `actions.test.ts:74-104`):

```ts
  "ticket.delete",
  "app.logout",
```

And extend the `context()` helper's defaults (around `actions.test.ts:50-69`) with:

```ts
    deleteTicket: vi.fn(),
    logout: vi.fn(),
```

- [ ] **Step 2: Run the test to verify it fails**

Run:
```bash
export PATH="$HOME/.nvm/versions/node/v24.13.0/bin:$PATH"
cd apps/web && pnpm exec vitest run src/lib/actions.test.ts
```
Expected: FAIL. `actionById("ticket.delete")` throws `Unknown action: ticket.delete`, and
the `REQUIRED_IDS` assertion reports both ids missing.

- [ ] **Step 3: Widen `ActionContext`**

In `apps/web/src/lib/actions.ts`, add two fields to the type (after `patchTicket`):

```ts
  deleteTicket: (id: string) => void;
  unarchive: (target: { kind: "team" | "project"; id: string }) => void;
  logout: () => void;
```

(`unarchive` is already there — keep it; the two new lines surround it as shown.)

- [ ] **Step 4: Add the two actions**

In `ACTIONS`, immediately after the `ticket.archive` entry:

```ts
	{
		id: "ticket.delete",
		label: "Delete ticket",
		group: "ticket",
		when: hasSelection,
		run: onSelected((ctx, ticket) => ctx.deleteTicket(ticket.id)),
	},
```

And immediately after the `app.help` entry:

```ts
	{
		id: "app.logout",
		label: "Sign out",
		group: "app",
		when: () => true,
		run: (ctx) => ctx.logout(),
	},
```

- [ ] **Step 5: Create the shared menu builder**

Create `apps/web/src/components/menu-items.ts`:

```ts
import { actionById, type ActionContext } from "@/lib/actions";
import type { MenuItem } from "./menu";

/**
 * Actions that destroy something. They are set apart in every menu that lists them —
 * the rule lives here rather than in each caller, so a menu cannot forget it.
 */
export const DESTRUCTIVE: ReadonlySet<string> = new Set([
  "team.delete",
  "project.delete",
  "ticket.delete",
]);

/**
 * Turns action ids into menu items, dropping the ones this context does not permit.
 * `actionById` throws on an unknown id: a menu naming a dead action is a bug worth a
 * crash at startup, not an item that silently never appears.
 */
export function menuItems(ctx: ActionContext, ids: string[]): MenuItem[] {
  return ids
    .map((id) => actionById(id))
    .filter((action) => action.when(ctx))
    .map((action) => ({
      id: action.id,
      label: action.label,
      danger: DESTRUCTIVE.has(action.id),
      onSelect: () => action.run(ctx),
    }));
}
```

- [ ] **Step 6: Delete the copy in `sidebar.tsx` and import the shared one**

Remove the local `menuItems` function (`sidebar.tsx:69-79`) entirely. Add to the imports
at the top of the file:

```ts
import { menuItems } from "./menu-items";
```

Remove `actionById` from the `@/lib/actions` import if nothing else in the file uses it;
keep `type ActionContext`.

- [ ] **Step 7: Supply the two new context fields**

In `apps/web/src/lib/use-action-ctx.ts`, add to the imports:

```ts
import { api } from "./api";
import { useDeleteTicket } from "./queries";
```

Inside `useActionContext`, before the `useMemo`:

```ts
  const remove = useDeleteTicket();
  const { mutate: deleteTicket } = remove;

  // Signing out is a full page transition, not a cache update: everything on screen
  // belongs to the session that is ending, so a reload is the honest way to drop it.
  const logout = useCallback(() => {
    void api.logout().finally(() => window.location.assign("/"));
  }, []);
```

Add `deleteTicket` and `logout` to the returned object and to the `useMemo` dependency
array. Import `useCallback` from `react`.

- [ ] **Step 8: Run the tests**

Run:
```bash
export PATH="$HOME/.nvm/versions/node/v24.13.0/bin:$PATH"
cd apps/web && pnpm exec vitest run && pnpm exec tsc --noEmit && pnpm lint
```
Expected: vitest PASS (40 tests), tsc silent, lint silent.

- [ ] **Step 9: Mutation check**

Delete the `when: hasSelection` line from `ticket.delete` and replace it with
`when: () => true`. Re-run `pnpm exec vitest run src/lib/actions.test.ts`.
Expected: FAIL on *"offers a delete for the selected ticket, and none without a
selection"*. Restore the line and confirm green. Record both outcomes in your report.

- [ ] **Step 10: Commit**

```bash
git add apps/web/src/lib/actions.ts apps/web/src/lib/actions.test.ts \
        apps/web/src/lib/use-action-ctx.ts apps/web/src/components/menu-items.ts \
        apps/web/src/components/sidebar.tsx
git commit -m "feat(web): add ticket delete and sign-out to the action registry"
```

---

### Task 2: What a new entity inherits from where you are

One pure function answering the spec's table, in the shape of `composerSeed` — which the
previous branch extracted for exactly this reason and whose tests are the model to copy.

**Files:**
- Create: `apps/web/src/lib/creation-seed.ts`
- Test: `apps/web/src/lib/creation-seed.test.ts`

**Interfaces:**
- Consumes: `Scope` from `@/store/ui`; `Team`, `Project` from `@/lib/api`.
- Produces:
  ```ts
  export type CreationSeed = {
    ticket: { teamId: string; projectId: string; blocked: boolean };
    project: { teamId: string | undefined };
    team: { parentTeamId: string | undefined };
  };
  export function creationSeed(
    scope: Scope,
    teams: Pick<Team, "id" | "archived">[],
    projects: Pick<Project, "id" | "teamId" | "archived">[],
  ): CreationSeed;
  ```

- [ ] **Step 1: Write the failing test**

Create `apps/web/src/lib/creation-seed.test.ts`:

```ts
import { describe, expect, it } from "vitest";
import { creationSeed } from "./creation-seed";

const core = { id: "team-core", archived: false };
const legacy = { id: "team-legacy", archived: true };
const refonte = { id: "proj-refonte", teamId: "team-core", archived: false };
const transverse = { id: "proj-transverse", teamId: undefined, archived: false };
const orphaned = { id: "proj-orphaned", teamId: "team-legacy", archived: false };

const teams = [core, legacy];
const projects = [refonte, transverse, orphaned];
const seed = (scope: Parameters<typeof creationSeed>[0]) =>
  creationSeed(scope, teams, projects);

describe("from the all-tickets scope", () => {
  it("blocks a ticket, because a ticket cannot exist without a team", () => {
    expect(seed({ kind: "all" }).ticket).toEqual({ teamId: "", projectId: "", blocked: true });
  });

  it("creates a project with no team and a team at the root", () => {
    expect(seed({ kind: "all" }).project.teamId).toBeUndefined();
    expect(seed({ kind: "all" }).team.parentTeamId).toBeUndefined();
  });
});

describe("from a team scope", () => {
  const scope = { kind: "team", id: core.id } as const;

  it("puts the ticket and the project in that team", () => {
    expect(seed(scope).ticket).toEqual({ teamId: core.id, projectId: "", blocked: false });
    expect(seed(scope).project.teamId).toBe(core.id);
  });

  it("makes a new team a sub-team of it", () => {
    expect(seed(scope).team.parentTeamId).toBe(core.id);
  });

  it("blocks the ticket when that team is archived and off the list", () => {
    expect(seed({ kind: "team", id: legacy.id }).ticket.blocked).toBe(true);
    expect(seed({ kind: "team", id: legacy.id }).team.parentTeamId).toBeUndefined();
  });
});

describe("from a project scope", () => {
  it("reads one level up: the project's team carries the ticket and the new team", () => {
    const s = seed({ kind: "project", id: refonte.id });
    expect(s.ticket).toEqual({ teamId: core.id, projectId: refonte.id, blocked: false });
    expect(s.project.teamId).toBe(core.id);
    expect(s.team.parentTeamId).toBe(core.id);
  });

  it("blocks the ticket from a team-less project, and infers no parent", () => {
    const s = seed({ kind: "project", id: transverse.id });
    expect(s.ticket).toEqual({ teamId: "", projectId: transverse.id, blocked: true });
    expect(s.project.teamId).toBeUndefined();
    expect(s.team.parentTeamId).toBeUndefined();
  });

  it("infers nothing from a project whose team is archived and off the list", () => {
    const s = seed({ kind: "project", id: orphaned.id });
    expect(s.ticket.blocked).toBe(true);
    expect(s.team.parentTeamId).toBeUndefined();
  });
});
```

- [ ] **Step 2: Run the test to verify it fails**

Run:
```bash
export PATH="$HOME/.nvm/versions/node/v24.13.0/bin:$PATH"
cd apps/web && pnpm exec vitest run src/lib/creation-seed.test.ts
```
Expected: FAIL with `Failed to resolve import "./creation-seed"`.

- [ ] **Step 3: Write the implementation**

Create `apps/web/src/lib/creation-seed.ts`:

```ts
import type { Project, Team } from "./api";
import type { Scope } from "@/store/ui";

/**
 * What a new entity inherits from wherever you were standing when you asked for it.
 *
 * Every value here is a *suggestion*: the dialogs pre-fill it and leave the field
 * editable. That is the whole difference between this and the defect it replaces —
 * the composer used to fall back to `teams[0]` invisibly, so a ticket could land in a
 * team nobody chose. These are on screen before anything is submitted.
 *
 * The team a scope resolves to is validated against the teams actually loaded. A scope
 * can point at a team that was archived a moment ago and is no longer on the list;
 * trusting it would file work into something invisible.
 */
export type CreationSeed = {
  ticket: { teamId: string; projectId: string; blocked: boolean };
  project: { teamId: string | undefined };
  team: { parentTeamId: string | undefined };
};

export function creationSeed(
  scope: Scope,
  teams: Pick<Team, "id" | "archived">[],
  projects: Pick<Project, "id" | "teamId" | "archived">[],
): CreationSeed {
  const scopeProject = scope.kind === "project" ? projects.find((p) => p.id === scope.id) : undefined;
  const wanted = scope.kind === "team" ? scope.id : scopeProject?.teamId;
  const teamId = wanted && teams.some((t) => t.id === wanted && !t.archived) ? wanted : "";

  const projectId =
    scopeProject && !scopeProject.archived && (!scopeProject.teamId || scopeProject.teamId === teamId)
      ? scopeProject.id
      : "";

  return {
    // A ticket's team is NOT NULL in the database, so an unresolvable team is not a
    // default to paper over — it is a question the person has to answer.
    ticket: { teamId, projectId, blocked: teamId === "" },
    project: { teamId: teamId || undefined },
    team: { parentTeamId: teamId || undefined },
  };
}
```

- [ ] **Step 4: Run the tests**

Run:
```bash
export PATH="$HOME/.nvm/versions/node/v24.13.0/bin:$PATH"
cd apps/web && pnpm exec vitest run && pnpm exec tsc --noEmit && pnpm lint
```
Expected: all PASS, tsc and lint silent.

- [ ] **Step 5: Mutation check**

Change `teamId === ""` to `false` in the `blocked` field. Re-run the suite.
Expected: FAIL on the three `blocked: true` assertions (all-tickets, archived team,
team-less project). Restore and confirm green. Then change `!scopeProject.teamId ||` to
`scopeProject.teamId &&` — expected FAIL on *"blocks the ticket from a team-less project"*.
Restore. Record both.

- [ ] **Step 6: Commit**

```bash
git add apps/web/src/lib/creation-seed.ts apps/web/src/lib/creation-seed.test.ts
git commit -m "feat(web): one rule for what a new entity inherits from the scope"
```

---

### Task 3: A version that comes from the build

`apps/web/package.json` says `0.1.0` and has never moved. People quote versions in bug
reports, so one that only changes when somebody remembers is worse than none.

**Files:**
- Modify: `apps/api/build.gradle.kts`
- Modify: `apps/api/src/main/kotlin/dev/kanso/api/Dtos.kt` (`MeResponse`)
- Modify: `apps/api/src/main/kotlin/dev/kanso/api/MiscControllers.kt` (`AuthController.me`)
- Modify: `apps/web/Dockerfile`, `docker-compose.yml`
- Create: `apps/web/src/lib/version.ts`
- Modify: `apps/web/src/lib/api.ts` (the `Me` type)
- Test: `apps/api/src/test/kotlin/dev/kanso/api/MeVersionTest.kt`

**Interfaces:**
- Produces:
  - `MeResponse` gains `val version: String`; the TS `Me` type gains `version: string`.
  - `apps/web/src/lib/version.ts` exports `WEB_VERSION: string`.

- [ ] **Step 1: Write the failing test**

Create `apps/api/src/test/kotlin/dev/kanso/api/MeVersionTest.kt`:

```kotlin
package dev.kanso.api

import dev.kanso.PostgresTest
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.info.BuildProperties
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The version has to come from the build, not from a constant somebody edits. If the
 * build stops generating it, this fails rather than quietly reporting a stale string.
 */
class MeVersionTest : PostgresTest() {

	@Autowired
	lateinit var build: BuildProperties

	@Test
	fun `the build stamps a version the API can report`() {
		assertTrue(build.version.isNotBlank(), "the build must generate a version")
		assertFalse(build.version == "unknown", "a placeholder is not a version")
	}
}
```

- [ ] **Step 2: Run it to verify it fails**

Run:
```bash
cd apps/api && ./gradlew cleanTest test --tests "dev.kanso.api.MeVersionTest"
```
Expected: FAIL — Spring cannot satisfy the `BuildProperties` bean, because nothing
generates `build-info.properties` yet.

- [ ] **Step 3: Generate the build info**

Append to `apps/api/build.gradle.kts`, after the `dependencies { … }` block:

```kotlin
// Spring exposes this as a BuildProperties bean; without it there is no version to
// report but the one hard-coded somewhere, which is the failure mode this avoids.
springBoot {
	buildInfo()
}
```

- [ ] **Step 4: Run it to verify it passes**

Run:
```bash
cd apps/api && ./gradlew cleanTest test --tests "dev.kanso.api.MeVersionTest"
```
Expected: PASS.

- [ ] **Step 5: Carry the version on `/api/me`**

In `apps/api/src/main/kotlin/dev/kanso/api/Dtos.kt`, add a field to `MeResponse`:

```kotlin
	val version: String,
```

In `apps/api/src/main/kotlin/dev/kanso/api/MiscControllers.kt`, inject the bean into
`AuthController`'s constructor:

```kotlin
	private val build: BuildProperties,
```

with the import `org.springframework.boot.info.BuildProperties`, and pass it in `me()`:

```kotlin
		return MeResponse(
			user = UserResponse.of(user),
			teamIds = teams.teamIdsFor(user.id),
			preferences = PreferencesResponse.of(preferences.get(user.id)),
			version = build.version,
		)
```

`/api/me` is chosen over opening `/actuator/info` because the client already fetches it
on every load, and `SecurityConfig` today permits `/actuator/health` alone.

- [ ] **Step 6: Stamp the web build**

In `apps/web/Dockerfile`, in the **build** stage, beside the existing
`ARG NEXT_PUBLIC_API_URL`:

```dockerfile
ARG NEXT_PUBLIC_KANSO_COMMIT=dev
ENV NEXT_PUBLIC_KANSO_COMMIT=$NEXT_PUBLIC_KANSO_COMMIT
```

In `docker-compose.yml`, under `web.build.args`:

```yaml
      NEXT_PUBLIC_KANSO_COMMIT: ${KANSO_COMMIT:-dev}
```

Create `apps/web/src/lib/version.ts`:

```ts
/**
 * Injected at build time, the same way NEXT_PUBLIC_API_URL is — Next inlines
 * NEXT_PUBLIC_* into the bundle, so this is a build argument, not a runtime variable.
 * `dev` is what a local `pnpm dev` reports, and saying so is more useful than pretending
 * to a version number nobody bumped.
 */
export const WEB_VERSION = process.env.NEXT_PUBLIC_KANSO_COMMIT ?? "dev";
```

In `apps/web/src/lib/api.ts`, add to the `Me` type:

```ts
  /** The API's build version. Compared against WEB_VERSION: a skew is worth seeing. */
  version: string;
```

- [ ] **Step 7: Document how to stamp a real build**

Append to `e2e/README.md`, at the end:

```markdown
## Stamping a version

`docker compose build` reads `KANSO_COMMIT`; without it the web bundle reports `dev`:

```bash
KANSO_COMMIT=$(git rev-parse --short HEAD) docker compose build web
```
```

- [ ] **Step 8: Verify**

Run:
```bash
cd apps/api && ./gradlew cleanTest test
export PATH="$HOME/.nvm/versions/node/v24.13.0/bin:$PATH"
cd ../web && pnpm exec tsc --noEmit && pnpm lint && pnpm exec vitest run
```
Expected: Kotlin 135 tests 0 failures; tsc, lint silent; vitest green.

- [ ] **Step 9: Mutation check**

Comment out the `springBoot { buildInfo() }` block and re-run
`./gradlew cleanTest test --tests "dev.kanso.api.MeVersionTest"`.
Expected: FAIL — the `BuildProperties` bean is gone. Restore and confirm green.

- [ ] **Step 10: Commit**

```bash
git add apps/api/build.gradle.kts apps/api/src/main/kotlin/dev/kanso/api/Dtos.kt \
        apps/api/src/main/kotlin/dev/kanso/api/MiscControllers.kt \
        apps/api/src/test/kotlin/dev/kanso/api/MeVersionTest.kt \
        apps/web/Dockerfile docker-compose.yml apps/web/src/lib/version.ts \
        apps/web/src/lib/api.ts e2e/README.md
git commit -m "feat: report a version that comes from the build"
```

---

### Task 4: The New menu

**Files:**
- Create: `apps/web/src/components/new-menu.tsx`
- Modify: `apps/web/src/app/page.tsx` (the top bar's New button)
- Modify: `apps/web/src/app/globals.css` (one rule)

**Interfaces:**
- Consumes: `menuItems`, `DESTRUCTIVE` (task 1); `Menu`, `MenuItem`; `ActionContext`.
- Produces: `export function NewMenu({ ctx }: { ctx: ActionContext }): React.ReactElement`

- [ ] **Step 1: Write the component**

Create `apps/web/src/components/new-menu.tsx`:

```tsx
"use client";

import { menuItems } from "./menu-items";
import { Menu } from "./menu";
import type { ActionContext } from "@/lib/actions";

/**
 * The three things a person creates, in one place, filtered by what they may do — a
 * member sees no Team without a second rule written here, because `when` already says so.
 *
 * `project.createInTeam` is offered ahead of `project.create` when the scope is a team,
 * so the project lands where you were standing. Exactly one of the two ever passes its
 * `when`, so the menu never shows both.
 */
export function NewMenu({ ctx }: { ctx: ActionContext }) {
  const items = menuItems(ctx, [
    "ticket.create",
    "project.createInTeam",
    "project.create",
    "team.createChild",
    "team.create",
  ]);

  return (
    <div className="new-menu">
      <Menu label="New" items={items} />
    </div>
  );
}
```

- [ ] **Step 2: Let `Menu` carry its own trigger text**

`Menu` hard-codes `⋯`. Hiding that with CSS and painting "New" over it with `::before`
would leave the real glyph in the accessible tree and in any copied text. Give the
component a prop instead.

In `apps/web/src/components/menu.tsx`, widen the signature and use it:

```tsx
export function Menu({
  label,
  items,
  trigger = "⋯",
}: {
  label: string;
  items: MenuItem[];
  trigger?: React.ReactNode;
}) {
```

and replace the literal `⋯` in the trigger button's body with `{trigger}`.

Then in `new-menu.tsx`, pass it: `<Menu label="New" trigger="New" items={items} />`.

Add to `apps/web/src/app/globals.css`, at the end of the `--- menu ---` block:

```css
/* The New menu borrows Menu's behaviour but not its shape: a labelled button in the
   top bar, not an ellipsis that hides until hover. */
.new-menu .menu-trigger {
  width: auto;
  height: auto;
  padding: 3px 9px;
  border: 1px solid var(--border);
  border-radius: var(--radius);
  font-size: 12px;
  color: var(--text-dim);
}
```

- [ ] **Step 3: Replace the top bar's button**

In `apps/web/src/app/page.tsx`, replace:

```tsx
          <button className="button" onClick={() => open("composer")}>
            New <kbd>c</kbd>
          </button>
```

with:

```tsx
          <NewMenu ctx={ctx} />
```

and add the import:

```ts
import { NewMenu } from "@/components/new-menu";
```

- [ ] **Step 4: Verify**

Run:
```bash
export PATH="$HOME/.nvm/versions/node/v24.13.0/bin:$PATH"
cd apps/web && pnpm exec tsc --noEmit && pnpm lint && pnpm exec vitest run && pnpm build
```
Expected: all silent/green, build succeeds.

- [ ] **Step 5: Commit**

```bash
git add apps/web/src/components/new-menu.tsx apps/web/src/app/page.tsx \
        apps/web/src/app/globals.css
git commit -m "feat(web): the top bar creates tickets, projects and teams"
```

---

### Task 5: The brand block becomes the account menu

**Files:**
- Create: `apps/web/src/components/brand-menu.tsx`
- Modify: `apps/web/src/components/sidebar.tsx` (the `brand` block at 197-202)
- Modify: `apps/web/src/app/globals.css` (`.brand` rules at 203-218)

**Interfaces:**
- Consumes: `menuItems` (task 1); `WEB_VERSION` and `Me.version` (task 3); `Menu`.
- Produces: `export function BrandMenu({ ctx }: { ctx: ActionContext }): React.ReactElement`

- [ ] **Step 1: Write the component**

Create `apps/web/src/components/brand-menu.tsx`:

```tsx
"use client";

import { menuItems } from "./menu-items";
import { Menu } from "./menu";
import type { ActionContext } from "@/lib/actions";
import { useMe } from "@/lib/queries";
import { WEB_VERSION } from "@/lib/version";

/**
 * Who am I, how do I configure this, how do I leave. Nothing in the interface answered
 * the first question outside dev mode.
 *
 * The version is not a menu item: it is not focusable and does not respond to a click.
 * Two lines appear when the web bundle and the API disagree, because a skew is exactly
 * what you want written down in a bug report.
 */
export function BrandMenu({ ctx }: { ctx: ActionContext }) {
  const me = useMe();
  const user = me.data?.user;
  const apiVersion = me.data?.version;

  const items = menuItems(ctx, ["app.settings", "app.help", "app.palette", "app.logout"]);

  return (
    <div className="brand">
      <Menu label="Account and settings" items={items} />
      <div className="brand-name">
        <strong>Kanso</strong>
        <span>簡素</span>
      </div>
      {user && (
        <div className="brand-identity" aria-hidden="true">
          {user.displayName}
        </div>
      )}
      <div className="brand-version" aria-hidden="true">
        {apiVersion && apiVersion !== WEB_VERSION ? (
          <>
            <span>web {WEB_VERSION}</span>
            <span>api {apiVersion}</span>
          </>
        ) : (
          <span>{WEB_VERSION}</span>
        )}
      </div>
    </div>
  );
}
```

- [ ] **Step 2: Mount it**

In `apps/web/src/components/sidebar.tsx`, replace the whole `brand` block:

```tsx
      <div className="brand">
        <strong>Kanso</strong>
        <span>簡素</span>
      </div>
```

with:

```tsx
      <BrandMenu ctx={rootCtx} />
```

and add the import:

```ts
import { BrandMenu } from "./brand-menu";
```

`rootCtx` (already defined at `sidebar.tsx:193`) is the right context: these actions are
about the session and the instance, not about whatever row is selected.

- [ ] **Step 3: Style it**

Replace the `.brand` rules in `apps/web/src/app/globals.css` (currently at 203-218) with:

```css
.brand {
  position: relative;
  display: flex;
  align-items: baseline;
  gap: 7px;
  padding: 0 6px;
}

.brand-name {
  display: flex;
  align-items: baseline;
  gap: 7px;
}

.brand-name strong {
  font-size: 14px;
  letter-spacing: -0.01em;
}

.brand-name span {
  font-size: 11px;
  color: var(--text-faint);
}

/* Identity and version ride along inside the menu's popover, not the sidebar itself. */
.brand-identity,
.brand-version {
  display: none;
}

.brand .menu-popover {
  right: auto;
  left: 0;
  min-width: 196px;
}
```

- [ ] **Step 4: Put identity and version inside the popover**

`Menu` renders only its `items`. Give it a header and a footer by extending its props.
In `apps/web/src/components/menu.tsx`, widen the signature:

```tsx
export function Menu({
  label,
  items,
  trigger = "⋯",
  header,
  footer,
}: {
  label: string;
  items: MenuItem[];
  trigger?: React.ReactNode;
  header?: React.ReactNode;
  footer?: React.ReactNode;
}) {
```

`trigger` is the prop task 4 added — keep it, do not drop it while widening.

and render them inside the popover, immediately after the opening `<div role="menu">`
for the header and immediately before its closing tag for the footer:

```tsx
        {header && <div className="menu-header">{header}</div>}
```

```tsx
        {footer && <div className="menu-footer">{footer}</div>}
```

Keep `if (items.length === 0) return null;` as it is — a menu with a header but nothing
actionable is still nothing to open.

Add to `globals.css`, in the `--- menu ---` block:

```css
.menu-header,
.menu-footer {
  padding: 6px 8px;
  font-size: 11px;
  color: var(--text-faint);
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.menu-header {
  border-bottom: 1px solid var(--border);
  margin-bottom: 4px;
}

.menu-header strong {
  font-size: 12px;
  color: var(--text);
}

.menu-footer {
  border-top: 1px solid var(--border);
  margin-top: 4px;
  font-family: var(--mono);
}
```

Then in `brand-menu.tsx`, drop the `.brand-identity` / `.brand-version` divs and pass
them instead:

```tsx
      <Menu
        label="Account and settings"
        items={items}
        header={
          user && (
            <>
              <strong>{user.displayName}</strong>
              <span>
                {user.email} · {user.instanceRole}
              </span>
            </>
          )
        }
        footer={
          apiVersion && apiVersion !== WEB_VERSION ? (
            <>
              <span>web {WEB_VERSION}</span>
              <span>api {apiVersion}</span>
            </>
          ) : (
            <span>{WEB_VERSION}</span>
          )
        }
      />
```

Delete the now-unused `.brand-identity` / `.brand-version` CSS rules added in step 3.

- [ ] **Step 5: Verify**

Run:
```bash
export PATH="$HOME/.nvm/versions/node/v24.13.0/bin:$PATH"
cd apps/web && pnpm exec tsc --noEmit && pnpm lint && pnpm exec vitest run && pnpm build
```
Expected: all silent/green.

- [ ] **Step 6: Commit**

```bash
git add apps/web/src/components/brand-menu.tsx apps/web/src/components/menu.tsx \
        apps/web/src/components/sidebar.tsx apps/web/src/app/globals.css
git commit -m "feat(web): the brand block opens account, settings and sign-out"
```

---

### Task 6: Status and priority become one click

**Files:**
- Modify: `apps/web/src/components/pills.tsx`
- Modify: `apps/web/src/components/tickets.tsx`
- Modify: `apps/web/src/app/globals.css`

**Interfaces:**
- Consumes: `menuItems` (task 1); `Menu`; `ActionContext`.
- Produces:
  - `StatusPill` and `PriorityMark` gain an optional `ctx?: ActionContext` prop. Without
    it they render exactly as today — the setup page's preview uses them read-only.
  - `TicketRow` and `TicketList` gain `ctx: ActionContext`.

- [ ] **Step 1: Make the pills optionally interactive**

In `apps/web/src/components/pills.tsx`, replace `StatusPill` and `PriorityMark` with:

```tsx
const STATUS_ACTIONS = [
  "ticket.status.backlog",
  "ticket.status.todo",
  "ticket.status.in_progress",
  "ticket.status.in_review",
  "ticket.status.done",
  "ticket.status.canceled",
];

const PRIORITY_ACTIONS = [
  "ticket.priority.none",
  "ticket.priority.low",
  "ticket.priority.medium",
  "ticket.priority.high",
  "ticket.priority.urgent",
];

/**
 * With a `ctx`, the pill is the control: clicking what you are already reading is one
 * gesture, where a shared row menu would be three. Without one it stays a label — the
 * setup wizard's preview renders rows nobody can act on.
 */
export function StatusPill({ status, ctx }: { status: TicketStatus; ctx?: ActionContext }) {
  const body = (
    <>
      <span className="dot" />
      <span style={{ color: "var(--text-dim)" }}>{STATUS_LABELS[status]}</span>
    </>
  );

  if (!ctx) {
    return (
      <span className="status" data-status={status} style={{ color: STATUS_COLORS[status] }}>
        {body}
      </span>
    );
  }

  return (
    <span className="status status-menu" data-status={status} style={{ color: STATUS_COLORS[status] }}>
      <Menu
        label={`Status: ${STATUS_LABELS[status]}`}
        trigger={null}
        items={menuItems(ctx, STATUS_ACTIONS)}
      />
      {body}
    </span>
  );
}

export function PriorityMark({ priority, ctx }: { priority: TicketPriority; ctx?: ActionContext }) {
  const { glyph, color, label } = PRIORITY_GLYPHS[priority];

  if (!ctx) {
    return (
      <span className="priority" style={{ color }} title={label}>
        {glyph}
      </span>
    );
  }

  return (
    <span className="priority priority-menu" style={{ color }}>
      <Menu
        label={`Priority: ${label}`}
        trigger={null}
        items={menuItems(ctx, PRIORITY_ACTIONS)}
      />
      <span aria-hidden="true">{glyph}</span>
    </span>
  );
}
```

Add the imports:

```ts
import { Menu } from "./menu";
import { menuItems } from "./menu-items";
import type { ActionContext } from "@/lib/actions";
```

- [ ] **Step 2: Give the pill triggers their shape**

Add to `globals.css`, at the end of the `--- pills ---` block:

```css
/* The pill IS the trigger: an empty button covers it and carries the accessible name
   (`trigger={null}`), while the glyph and label underneath stay purely visual. */
.status-menu,
.priority-menu {
  position: relative;
}

.status-menu .menu-trigger,
.priority-menu .menu-trigger {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
  border-radius: 4px;
}

.status-menu .menu-popover,
.priority-menu .menu-popover {
  left: 0;
  right: auto;
  min-width: 152px;
}
```

- [ ] **Step 3: Thread a per-row context through the list**

In `apps/web/src/components/tickets.tsx`, add `ctx: ActionContext` to both `RowProps` and
`ListProps`, import `type { ActionContext } from "@/lib/actions"`, and inside `TicketList`
build one context per row — the same shape `sidebar.tsx:185` uses for its rows:

```tsx
        const rowCtx: ActionContext = { ...ctx, selected: ticket };
```

pass `ctx={rowCtx}` to `TicketRow`, and inside `TicketRow` pass `ctx={ctx}` to both
`<PriorityMark>` and `<StatusPill>`.

A row's menu must act on **that** row, not on whatever happens to be selected. This is
the failure the task 13 review checked for on the sidebar; the same shape earns the same
guarantee here.

- [ ] **Step 4: Pass the context in from the page**

In `apps/web/src/app/page.tsx`, add `ctx={ctx}` to the `<TicketList …>` element.

- [ ] **Step 5: Verify**

Run:
```bash
export PATH="$HOME/.nvm/versions/node/v24.13.0/bin:$PATH"
cd apps/web && pnpm exec tsc --noEmit && pnpm lint && pnpm exec vitest run && pnpm build
```
Expected: all silent/green. `tsc` will flag the setup preview if it passes a `ctx` it does
not have — it should not; the prop is optional precisely for that call site.

- [ ] **Step 6: Commit**

```bash
git add apps/web/src/components/pills.tsx apps/web/src/components/tickets.tsx \
        apps/web/src/app/page.tsx apps/web/src/app/globals.css
git commit -m "feat(web): the status pill and priority mark open their own menus"
```

---

### Task 7: The row menu

**Files:**
- Modify: `apps/web/src/components/tickets.tsx`
- Modify: `apps/web/src/app/globals.css`

**Interfaces:**
- Consumes: everything from tasks 1 and 6.
- Produces: nothing new; `TicketRow` renders a `Menu` in its meta area.

- [ ] **Step 1: Add the menu to the row**

In `apps/web/src/components/tickets.tsx`, inside `TicketRow`'s `row-meta` div, after
`<SyncBadge …/>`:

```tsx
        <Menu
          label={`Actions for ${ticket.identifier}`}
          items={menuItems(ctx, [
            "ticket.rename",
            "ticket.archive",
            "ticket.delete",
          ])}
        />
```

with the imports:

```ts
import { Menu } from "./menu";
import { menuItems } from "./menu-items";
```

`ticket.archive`'s label reads *Archive / unarchive ticket* for both directions, matching
the registry as it stands.

- [ ] **Step 2: Reveal it on hover, keep it when focused**

Add to `globals.css`, at the end of the `--- list ---` block:

```css
/* Hidden until the row is hovered or the trigger takes focus — a keyboard user must be
   able to reach it, so `opacity` rather than `display`. */
.row .menu-trigger {
  opacity: 0;
}

.row:hover .menu-trigger,
.row .menu-trigger:focus-visible,
.row .menu-trigger[aria-expanded="true"] {
  opacity: 1;
}

/* The status and priority pills are always legible: only the row's own ⋯ hides. */
.row .status-menu .menu-trigger,
.row .priority-menu .menu-trigger {
  opacity: 1;
}
```

- [ ] **Step 3: Verify**

Run:
```bash
export PATH="$HOME/.nvm/versions/node/v24.13.0/bin:$PATH"
cd apps/web && pnpm exec tsc --noEmit && pnpm lint && pnpm exec vitest run && pnpm build
```
Expected: all silent/green.

- [ ] **Step 4: Commit**

```bash
git add apps/web/src/components/tickets.tsx apps/web/src/app/globals.css
git commit -m "feat(web): a ticket row carries rename, archive and delete"
```

---

### Task 8: Playwright — scenarios 9, 10 and 11

This is the only coverage these five tasks get. Tasks 4 to 7 have no unit tests by
design; if a scenario here would pass with the feature removed, the feature is untested.

**Files:**
- Create: `e2e/mouse.spec.ts`

**Interfaces:**
- Consumes: the helpers in `e2e/support.ts` (`openAs`, and whatever the existing specs
  use to seed teams, projects and tickets — read that file first and reuse it rather than
  writing new setup).

- [ ] **Step 1: Read the existing helpers**

Run:
```bash
cat e2e/support.ts
cat e2e/archive.spec.ts
```
Reuse the seeding and identity helpers. Do not invent a second way to create fixtures.

- [ ] **Step 2: Write scenario 9 — creation from the top bar carries the scope**

Create `e2e/mouse.spec.ts` with a test titled
`"scenario 9 — the New menu creates into the scope you are standing in"` that:

- signs in as the owner, creates team `Core` and a project `Refonte` inside it;
- selects the project in the sidebar, opens the top bar `New` menu, chooses
  `New ticket`, types a title and submits;
- asserts, **against the API**, that the created ticket carries both `Refonte`'s id and
  `Core`'s id — not merely that a row appeared;
- selects `Core`, opens `New`, chooses `New sub-team`, and asserts the dialog's parent
  field is pre-filled with `Core` before submitting;
- asserts that from the *All tickets* scope, `New ticket` opens the composer blocked with
  the team selector focused and no ticket created.

- [ ] **Step 3: Write scenario 10 — the brand menu**

`"scenario 10 — the brand menu names who you are, and signs you out"`:

- asserts the brand trigger exists and its menu shows the signed-in display name and
  role;
- asserts the menu lists Settings, Keyboard shortcuts, Command palette and Sign out, as
  an exact list (`toHaveText([...])`), so an item leaking in or out fails;
- clicks Settings and asserts the settings panel opens;
- clicks Sign out and asserts the sign-in screen returns.

- [ ] **Step 4: Write scenario 11 — a ticket row is actionable**

`"scenario 11 — a ticket row changes status, priority, name and existence by mouse"`:

- creates two tickets so the test proves the row menu acts on **its own** row;
- clicks the *second* row's status pill, chooses `In progress`, and asserts via the API
  that the second ticket changed and **the first did not**;
- clicks its priority mark, chooses `High`, asserts the same way;
- opens the row `⋯`, chooses `Rename ticket`, types a new title, and asserts it landed;
- opens it again, chooses `Delete ticket`, and asserts the ticket is gone from the API.

- [ ] **Step 5: Run the suite**

```bash
export COMPOSE_PROJECT_NAME=kansomouse KANSO_AUTH_MODE=dev \
       POSTGRES_PORT=55450 API_PORT=58100 WEB_PORT=53020 \
       KANSO_WEB_ORIGIN=http://localhost:53020
docker compose up -d --build --wait
export PATH="$HOME/.nvm/versions/node/v24.13.0/bin:$PATH" \
       KANSO_WEB_URL=http://localhost:53020 KANSO_API_URL=http://localhost:58100
pnpm exec playwright test
```
Expected: 11 passed.

A non-default `WEB_PORT` **requires** `KANSO_WEB_ORIGIN`; without it CORS silently makes
every page render as though the visitor were a member, and the permission assertions fail
for the wrong reason.

- [ ] **Step 6: Mutation check, three of them**

1. In `new-menu.tsx`, remove `"project.createInTeam"` from the id list.
   Expected: scenario 9 fails on the pre-filled team.
2. In `tickets.tsx`, change `{ ...ctx, selected: ticket }` to `ctx`.
   Expected: scenario 11 fails — the pill acts on the selected row, not the clicked one.
3. In `brand-menu.tsx`, remove `"app.logout"` from the id list.
   Expected: scenario 10 fails on the exact-list assertion.

Restore each and confirm green. Report all three outcomes.

- [ ] **Step 7: Tear down and commit**

```bash
docker compose down -v
git add e2e/mouse.spec.ts
git commit -m "test(e2e): cover the New menu, the brand menu and row actions"
```

---

## Self-review

**Spec coverage.** New menu → task 4. Brand menu with identity, settings, help, palette,
sign out, version → tasks 3 and 5. Ticket rows: pills → task 6, `⋯` → task 7. The two
registry additions → task 1. The creation rule → task 2. Build-injected version → task 3.
Tests → tasks 1, 2, 3 (unit) and 8 (e2e). The deliberately deferred logo image appears in
no task, which is correct.

**Type consistency.** `ActionContext` gains `deleteTicket` and `logout` in task 1 and is
used with those names in tasks 4-7. `menuItems(ctx, ids)` keeps one signature across
tasks 1, 4, 5, 6 and 7. `creationSeed`'s return shape is fixed in task 2 and consumed
nowhere else in this plan — the dialogs already read `composerSeed`; wiring them onto
`creationSeed` is task 4's `project.createInTeam` path and task 8's assertion, which is
why scenario 9 checks the pre-filled parent rather than trusting the function alone.

**Known limit, stated rather than hidden.** `creationSeed` supersedes `composerSeed` in
intent but this plan does not delete the older function: the composer's call site is
covered by existing tests, and swapping it is a change worth its own review rather than a
silent rider on task 2.
