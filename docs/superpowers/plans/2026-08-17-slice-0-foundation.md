# Slice 0 — Foundation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Restructure the six files every slice would otherwise edit, and build the three
pieces of backend that three slices each depend on, so six branches can run at once.

**Architecture:** Two halves that do not touch. First a pure mechanical refactor of the web
client — three modules split into a barrel plus one file per slice, two unions widened, the
nav list extracted, thirteen route stubs and one component stub committed — which changes no
behaviour and is proved by the existing suite still passing. Then one migration and three
services on the API side: comments with mentions, team-scoped labels, and a persisted
activity log written in the same transaction as the change it records.

**Tech Stack:** Kotlin / Spring Boot / Exposed / Flyway / Postgres on the API; Next.js 16,
React 19, TanStack Query 5, zustand 5, Tailwind v4 on the web. Vitest for web units,
Testcontainers for API, Playwright for e2e.

**Spec:** `docs/superpowers/specs/2026-08-17-remaining-screens-design.md`

## Global Constraints

- Migration numbers are assigned by the spec and are not negotiable: `V8` is this slice's.
  `V9` B, `V10` C, `V11` E, `V12` F, `V13` D belong to other branches — do not use them.
- `app/globals.css`, `styles/tokens.css`, `app/layout.tsx`, `app/providers.tsx` are frozen.
  Existing tokens only; Tailwind utilities for anything new. `lib/tokens.test.ts` enforces it.
- Every write goes through `TicketAccess`. Reads are open, writes are scoped — the rule
  `architecture.md` states, with no exception for new endpoints.
- Closed vocabularies are enforced by a database `CHECK`, following `user_preferences`.
- The API test suite is `@Transactional` and rolls back, so `EventPublisher`'s `afterCommit`
  never fires in tests. Assert the activity row through the service, never through an event.
- Verification, run from `apps/web`: `pnpm typecheck`, `pnpm test`. From `apps/api`:
  `./gradlew test`. From the repository root: `pnpm test:e2e`.

---

### Task 1: Split `lib/api.ts` into a barrel and a core

**Files:**
- Create: `apps/web/src/lib/api/core.ts` (verbatim content of today's `lib/api.ts`)
- Create: `apps/web/src/lib/api/index.ts`
- Delete: `apps/web/src/lib/api.ts`

**Interfaces:**
- Consumes: nothing.
- Produces: `@/lib/api` resolves to `lib/api/index.ts` and re-exports everything it does
  today — `api`, `ApiError`, `Ticket`, `Team`, `Project`, `Preferences`,
  `DEFAULT_PREFERENCES`, `TICKET_STATUSES`, `TICKET_PRIORITIES`, `dayValue`,
  `fromDayValue`, `getDevUser`, `setDevUser`, `API_URL` and the rest, unchanged. Every
  existing `import … from "@/lib/api"` keeps working untouched.

- [ ] **Step 1: Move the file**

```bash
cd apps/web/src/lib && mkdir -p api && git mv api.ts api/core.ts
```

- [ ] **Step 2: Write the barrel**

`apps/web/src/lib/api/index.ts`:

```ts
/**
 * One import path, one file per slice. `core` is the client every screen already had;
 * the rest are added by the branches that need them, so six of them can be written at
 * once without six edits to one file.
 */
export * from "./core";
```

- [ ] **Step 3: Verify nothing moved but the file**

Run, from `apps/web`: `pnpm typecheck && pnpm test`
Expected: PASS, with no import in the tree edited.

- [ ] **Step 4: Commit**

```bash
git add -A apps/web/src/lib/api apps/web/src/lib/api.ts
git commit -m "refactor(web): make lib/api a directory with a barrel"
```

---

### Task 2: Split `lib/queries.ts` the same way

**Files:**
- Create: `apps/web/src/lib/queries/core.ts` (verbatim move)
- Create: `apps/web/src/lib/queries/index.ts`
- Delete: `apps/web/src/lib/queries.ts`

**Interfaces:**
- Consumes: `@/lib/api` from Task 1.
- Produces: `@/lib/queries` re-exports every hook it exports today — `useMe`,
  `useTeams`, `useTickets`, `useProjects`, `usePreferences`, `useSetupState`,
  `useSyncStatus`, `useAuthMode`, `usePatchTicket`, `useLinkDependency`,
  `useUnlinkDependency` and the rest.

- [ ] **Step 1: Move and write the barrel**

```bash
cd apps/web/src/lib && mkdir -p queries && git mv queries.ts queries/core.ts
```

`apps/web/src/lib/queries/index.ts`:

```ts
export * from "./core";
```

- [ ] **Step 2: Verify**

Run, from `apps/web`: `pnpm typecheck && pnpm test`
Expected: PASS.

- [ ] **Step 3: Commit**

```bash
git add -A apps/web/src/lib/queries apps/web/src/lib/queries.ts
git commit -m "refactor(web): make lib/queries a directory with a barrel"
```

---

### Task 3: Split `lib/actions.ts` so six branches can add actions

777 lines, and `ACTIONS` is one array literal. Six agents appending to it is six conflicts
in one expression.

**Files:**
- Create: `apps/web/src/lib/actions/core.ts` — everything in today's file, with
  `export const ACTIONS: readonly Action[]` renamed `export const coreActions`
- Create: `apps/web/src/lib/actions/index.ts` — types, `ACTIONS` composition, and the
  four exported functions
- Create: `apps/web/src/lib/actions/{board,docs,organise,inbox,trash,publik}.ts` — each
  exporting an empty typed array
- Delete: `apps/web/src/lib/actions.ts`
- Test: `apps/web/src/lib/actions/index.test.ts`

**Interfaces:**
- Consumes: `@/lib/api`, `@/store/ui`.
- Produces: `@/lib/actions` exports `ACTIONS`, `resolveShortcut(key, view)`,
  `availableActions(ctx)`, `hintOf(action, isMac)`, `predecessorsOf(ctx, successorId)`,
  `canPlan(ctx)`, and the types `Action`, `ActionContext`, `ActionGroup` — the same names
  `page.tsx`, `menu-items.ts`, `help-overlay.tsx` and `use-action-ctx.ts` import today.
  Each slice fills its own file with `Action[]`; `index.ts` concatenates in a fixed order.

`publik` is spelled with a k because `public` is a reserved word in enough of the
toolchain's contexts to be worth not finding out.

- [ ] **Step 1: Write the failing test**

`apps/web/src/lib/actions/index.test.ts`:

```ts
import { describe, expect, it } from "vitest";
import { ACTIONS, resolveShortcut } from "./index";
import { coreActions } from "./core";

describe("the action registry", () => {
  it("holds every core action", () => {
    for (const action of coreActions) {
      expect(ACTIONS.some((candidate) => candidate.id === action.id)).toBe(true);
    }
  });

  // A slice that reuses an id silently shadows another slice's action in every
  // surface that looks one up by id — the palette, the menus and the help sheet.
  it("has no duplicate id across slices", () => {
    const ids = ACTIONS.map((action) => action.id);
    expect(new Set(ids).size).toBe(ids.length);
  });

  // The registry only owns bare keys; a slice registering "k" would collide with
  // ticket.moveUp, which is why hint exists for anything modified.
  it("resolves one action per key and view", () => {
    expect(resolveShortcut("j", "list")?.id).toBe("ticket.moveDown");
  });
});
```

- [ ] **Step 2: Run it and watch it fail**

Run, from `apps/web`: `pnpm vitest run src/lib/actions/index.test.ts`
Expected: FAIL — cannot resolve `./index` or `./core`.

- [ ] **Step 3: Move the file and rename the array**

```bash
cd apps/web/src/lib && mkdir -p actions && git mv actions.ts actions/core.ts
```

In `actions/core.ts`, rename `export const ACTIONS: readonly Action[] =` to
`export const coreActions: readonly Action[] =`. Move the type declarations
(`ActionGroup`, `ActionContext`, `Action`), `canPlan`, `predecessorsOf`,
`resolveShortcut`, `availableActions` and `hintOf` out of it into `actions/index.ts`;
`core.ts` imports the types back from `./types`.

Split the types into `apps/web/src/lib/actions/types.ts` so `core.ts` and the six slice
files can import them without importing the barrel that imports them — a cycle otherwise.

- [ ] **Step 4: Write the six empty slice files**

Each of `board.ts`, `docs.ts`, `organise.ts`, `inbox.ts`, `trash.ts`, `publik.ts`:

```ts
import type { Action } from "./types";

/** Filled by its own branch. Empty here so the barrel can name it from day one. */
export const boardActions: readonly Action[] = [];
```

- [ ] **Step 5: Write the barrel**

`apps/web/src/lib/actions/index.ts`:

```ts
import { boardActions } from "./board";
import { coreActions } from "./core";
import { docsActions } from "./docs";
import { inboxActions } from "./inbox";
import { organiseActions } from "./organise";
import { publikActions } from "./publik";
import { trashActions } from "./trash";
import type { Action, ActionContext } from "./types";

export type { Action, ActionContext, ActionGroup } from "./types";
export { canPlan, predecessorsOf } from "./core";

/**
 * One registry, composed rather than written. The order is fixed and core comes first:
 * `resolveShortcut` returns the first match, so a slice cannot take a key core already
 * owns by being loaded earlier.
 */
export const ACTIONS: readonly Action[] = [
  ...coreActions,
  ...boardActions,
  ...docsActions,
  ...organiseActions,
  ...inboxActions,
  ...trashActions,
  ...publikActions,
];
```

`resolveShortcut`, `availableActions` and `hintOf` move here verbatim, reading `ACTIONS`.

- [ ] **Step 6: Run the tests**

Run, from `apps/web`: `pnpm typecheck && pnpm test`
Expected: PASS, including `actions/index.test.ts` and the existing `actions.test.ts`
(which moves to `actions/core.test.ts` and keeps its assertions).

- [ ] **Step 7: Commit**

```bash
git add -A apps/web/src/lib
git commit -m "refactor(web): compose the action registry from one file per slice"
```

---

### Task 4: Widen the two unions and extract the nav list

**Files:**
- Modify: `apps/web/src/store/ui.ts`
- Create: `apps/web/src/components/nav-items.ts`
- Modify: `apps/web/src/components/sidebar.tsx` (render the list; no other change)

**Interfaces:**
- Consumes: nothing new.
- Produces: `View` is `"list" | "board" | "timeline"`. `Overlay` gains `"bulk"`,
  `"triage"`, `"conflict"`, `"blockInsert"`. `Dialog` gains
  `{ kind: "saveView"; id?: string }`, `{ kind: "importMap" }`,
  `{ kind: "restore"; target: { kind: "ticket" | "doc" | "view" | "folder"; id: string } }`.
  `nav-items.ts` exports `NAV_ITEMS: readonly NavItem[]` where
  `NavItem = { id: string; label: string; href: string; live: boolean; badge?: "inbox" | "triage" | "docs" | "trash" }`.

- [ ] **Step 1: Widen `store/ui.ts`**

Add the values above to the three unions. Nothing reads them yet; the point is that no
slice edits this file later.

- [ ] **Step 2: Write `nav-items.ts` with all fourteen rows**

Every route from the spec, `live: false` for anything still a stub. The owning branch
flips one boolean — the only edit to a shared file any slice is allowed.

```ts
export type NavItem = {
  id: string;
  label: string;
  href: string;
  /** False while the route is still slice 0's placeholder. Its own branch flips it. */
  live: boolean;
  badge?: "inbox" | "triage" | "docs" | "trash";
};

export const NAV_ITEMS: readonly NavItem[] = [
  { id: "tickets", label: "Team tickets", href: "/", live: true },
  { id: "inbox", label: "Inbox", href: "/inbox", live: false, badge: "inbox" },
  { id: "triage", label: "Triage", href: "/triage", live: false, badge: "triage" },
  { id: "cycle", label: "Cycle", href: "/cycles/current", live: false },
  { id: "views", label: "Saved views", href: "/views", live: false },
  { id: "workload", label: "Workload", href: "/workload", live: false },
  { id: "docs", label: "Documents", href: "/docs", live: false, badge: "docs" },
  { id: "trash", label: "Trash", href: "/trash", live: false, badge: "trash" },
];
```

- [ ] **Step 3: Render it from `sidebar.tsx`**

Replace the hand-written nav rows with a map over `NAV_ITEMS.filter((item) => item.live)`,
keeping `data-testid="nav-item"` and the `nav-item` class — `e2e` keys on both, as
`follow-ups.md` records, and this refactor is not the branch that fixes that.

- [ ] **Step 4: Verify**

Run, from `apps/web`: `pnpm typecheck && pnpm test`
Then from the root: `pnpm test:e2e` — the nav is what `crud.spec.ts` and
`14-menu-keyboard.spec.ts` walk.
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add -A apps/web/src/store/ui.ts apps/web/src/components/nav-items.ts apps/web/src/components/sidebar.tsx
git commit -m "refactor(web): move the sidebar nav into a list every slice can light up"
```

---

### Task 5: Commit the route stubs and the board seam

**Files:**
- Create: `apps/web/src/app/{t/[key],p/[id],docs,docs/[id],cycles/[number],triage,views,views/[id],workload,inbox,trash,roadmap,roadmap/[key],about}/page.tsx`

`/views` is in this list and not in the spec's: `NAV_ITEMS` points the sidebar row at the
index, and `/views/[id]` alone would make that row a 404. Fourteen stubs, not thirteen.
- Create: `apps/web/src/components/board/view.tsx`
- Modify: `apps/web/src/app/page.tsx` — third segmented button, third render branch

**Interfaces:**
- Consumes: `View` from Task 4.
- Produces: `<BoardView reportError={(message: string | null) => void} />` — the same
  prop `TimelineView` already takes, so the page's two chart branches read alike. Slice A
  replaces the body; nobody else touches the file.

- [ ] **Step 1: Write one stub, thirteen times**

Each stub renders the screen's name and the slice that owns it, so a stray link lands
somewhere legible rather than on a 404:

```tsx
export default function TrashPage() {
  return <main className="p-6 text-13 text-faint">Trash — slice E, not built yet.</main>;
}
```

- [ ] **Step 2: Write the board stub**

```tsx
"use client";

/** Screen 04. Slice A fills this; slice 0 only cuts the hole so `app/page.tsx` can freeze. */
export function BoardView({ reportError }: { reportError: (message: string | null) => void }) {
  void reportError;
  return <div className="empty">Board — slice A, not built yet.</div>;
}
```

- [ ] **Step 3: Add the third button and the third branch to `app/page.tsx`**

In the `View` segmented control, a `Board` button between `List` and `Timeline`, and in the
render switch a `view === "board"` branch rendering `<BoardView reportError={reportError} />`.

- [ ] **Step 4: Verify**

Run, from `apps/web`: `pnpm typecheck && pnpm test && pnpm build`
`pnpm build` is the step that catches a malformed route directory, which typecheck does not.
Expected: PASS, thirteen routes listed in the build output.

- [ ] **Step 5: Commit**

```bash
git add -A apps/web/src/app apps/web/src/components/board
git commit -m "feat(web): stub the thirteen routes and the board seam"
```

---

### Task 6: `V8` — comments, labels, activity, and one preference column

**Files:**
- Create: `apps/api/src/main/resources/db/migration/V8__comments_labels_activity.sql`
- Modify: `apps/api/src/main/kotlin/dev/kanso/db/Tables.kt`

**Interfaces:**
- Consumes: `tickets`, `notion_docs`, `users`, `teams`, `user_preferences` as they stand.
- Produces: Exposed objects `Comments`, `CommentMentions`, `Labels`, `TicketLabels`,
  `Activity`, and `UserPreferences.openTicket`.

- [ ] **Step 1: Write the migration**

```sql
-- A comment belongs to one thing. A nullable pair with no constraint is how a third
-- case gets written by accident, so the check is the schema's, not the service's.
CREATE TABLE comments (
  id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  ticket_id  UUID REFERENCES tickets(id) ON DELETE CASCADE,
  doc_id     UUID REFERENCES notion_docs(id) ON DELETE CASCADE,
  author_id  UUID NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
  body       TEXT NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT comments_one_parent_chk CHECK (num_nonnulls(ticket_id, doc_id) = 1)
);

CREATE INDEX comments_ticket_idx ON comments (ticket_id, created_at);
CREATE INDEX comments_doc_idx    ON comments (doc_id, created_at);

CREATE TRIGGER comments_set_updated_at BEFORE UPDATE ON comments
  FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- Resolved at write time, not re-parsed on read: renaming a user must not silently
-- drop a mention that was already delivered.
CREATE TABLE comment_mentions (
  comment_id UUID NOT NULL REFERENCES comments(id) ON DELETE CASCADE,
  user_id    UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  PRIMARY KEY (comment_id, user_id)
);

-- Team-scoped: two teams calling different things `sync` is normal, and one global
-- namespace would make them fight over the word.
CREATE TABLE labels (
  id      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  team_id UUID NOT NULL REFERENCES teams(id) ON DELETE CASCADE,
  name    TEXT NOT NULL,
  colour  TEXT NOT NULL DEFAULT 'indigo',
  UNIQUE (team_id, name),
  CONSTRAINT labels_colour_chk
    CHECK (colour IN ('indigo', 'blue', 'green', 'amber', 'rose', 'violet'))
);

CREATE TABLE ticket_labels (
  ticket_id UUID NOT NULL REFERENCES tickets(id) ON DELETE CASCADE,
  label_id  UUID NOT NULL REFERENCES labels(id) ON DELETE CASCADE,
  PRIMARY KEY (ticket_id, label_id)
);

CREATE INDEX ticket_labels_label_idx ON ticket_labels (label_id);

-- Written in the same transaction as the change it records. A listener on pg_notify
-- would make the log lossy in exactly the case it exists to explain.
CREATE TABLE activity (
  id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  entity_type TEXT NOT NULL,
  entity_id   UUID NOT NULL,
  actor_id    UUID REFERENCES users(id) ON DELETE SET NULL,
  kind        TEXT NOT NULL,
  payload     JSONB NOT NULL DEFAULT '{}'::jsonb,
  created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT activity_entity_type_chk
    CHECK (entity_type IN ('ticket', 'project', 'team', 'doc')),
  CONSTRAINT activity_kind_chk
    CHECK (kind IN ('created', 'status_changed', 'priority_changed', 'assigned',
                    'unassigned', 'renamed', 'scheduled', 'archived', 'commented',
                    'labelled', 'mirror_pushed'))
);

CREATE INDEX activity_entity_idx ON activity (entity_type, entity_id, created_at DESC);
CREATE INDEX activity_actor_idx  ON activity (actor_id, created_at DESC);

-- Screen 02 says the setting lives in the preferences. It did not.
ALTER TABLE user_preferences
  ADD COLUMN open_ticket TEXT NOT NULL DEFAULT 'panel';

ALTER TABLE user_preferences ADD CONSTRAINT user_preferences_open_ticket_chk
  CHECK (open_ticket IN ('panel', 'page'));
```

- [ ] **Step 2: Declare the tables in Exposed**

Add `Comments`, `CommentMentions`, `Labels`, `TicketLabels`, `Activity` to `Tables.kt`
following the objects already there, and `openTicket` to the preferences object.

- [ ] **Step 3: Run the suite so Flyway applies the migration**

Run, from `apps/api`: `./gradlew test`
Expected: PASS. A `CHECK` typo shows up here as a Flyway failure on container start, not
as a test assertion.

- [ ] **Step 4: Commit**

```bash
git add -A apps/api/src/main/resources/db/migration apps/api/src/main/kotlin/dev/kanso/db/Tables.kt
git commit -m "feat(api): add comments, labels and an activity log"
```

---

### Task 7: `ActivityService`, written from the services that already publish events

**Files:**
- Create: `apps/api/src/main/kotlin/dev/kanso/service/ActivityService.kt`
- Create: `apps/api/src/main/kotlin/dev/kanso/repo/ActivityRepository.kt`
- Modify: `apps/api/src/main/kotlin/dev/kanso/service/TicketService.kt` (record on create,
  patch, assignees, archive)
- Create: `apps/api/src/main/kotlin/dev/kanso/api/ActivityController.kt`
- Test: `apps/api/src/test/kotlin/dev/kanso/service/ActivityServiceTest.kt`

**Interfaces:**
- Consumes: `TicketAccess`, `CurrentUser`.
- Produces: `ActivityService.record(entityType: String, entityId: UUID, actorId: UUID?,
  kind: String, payload: Map<String, Any?> = emptyMap())` and
  `ActivityService.forEntity(entityType: String, entityId: UUID, limit: Int = 50):
  List<ActivityRow>`, where `ActivityRow(id, entityType, entityId, actor: UserSummary?,
  kind, payload, createdAt)`. `GET /api/activity?entityType=&entityId=`.

- [ ] **Step 1: Write the failing test**

```kotlin
@Test
fun `a status change records one activity row carrying before and after`() {
    val ticket = tickets.create(actor, CreateTicket(teamId = team.id, title = "Echo"))
    tickets.patch(actor, ticket.id, PatchTicket(status = "in_progress"))

    val rows = activity.forEntity("ticket", ticket.id)

    assertEquals(listOf("status_changed", "created"), rows.map { it.kind })
    assertEquals("todo", rows.first().payload["from"])
    assertEquals("in_progress", rows.first().payload["to"])
}
```

Newest first — the index is `created_at DESC` and every reader of this list draws a feed.

- [ ] **Step 2: Run it and watch it fail**

Run, from `apps/api`: `./gradlew test --tests '*ActivityServiceTest*'`
Expected: FAIL — `ActivityService` does not exist.

- [ ] **Step 3: Implement the repository, the service, and the four call sites**

`record` inserts inside the caller's transaction. `TicketService.patch` records one row per
scalar that actually changed, comparing before and after — not one row per call, and not one
row for a patch that changed nothing.

- [ ] **Step 4: Run the suite**

Run, from `apps/api`: `./gradlew test`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add -A apps/api/src/main/kotlin/dev/kanso
git commit -m "feat(api): record what happened, in the transaction that made it happen"
```

---

### Task 8: `CommentService`, with mentions resolved at write time

**Files:**
- Create: `apps/api/src/main/kotlin/dev/kanso/service/CommentService.kt`
- Create: `apps/api/src/main/kotlin/dev/kanso/repo/CommentRepository.kt`
- Create: `apps/api/src/main/kotlin/dev/kanso/api/CommentController.kt`
- Test: `apps/api/src/test/kotlin/dev/kanso/service/CommentServiceTest.kt`

**Interfaces:**
- Consumes: `TicketAccess`, `ActivityService.record` from Task 7, `UserRepository`.
- Produces: `CommentService.create(actor, CreateComment(ticketId, docId, body)):
  CommentRow`, `CommentService.forTicket(ticketId): List<CommentRow>`,
  `CommentService.delete(actor, id)`, with
  `CommentRow(id, author: UserSummary, body, mentions: List<UserSummary>, createdAt,
  updatedAt)`. `GET /api/comments?ticketId=`, `POST /api/comments`,
  `DELETE /api/comments/{id}`.

- [ ] **Step 1: Write the failing tests**

```kotlin
@Test
fun `an at-handle in the body becomes a stored mention`() {
    val comment = comments.create(actor, CreateComment(ticketId = ticket.id,
        body = "@lea — queue on disk or in memory?"))

    assertEquals(listOf(lea.id), comment.mentions.map { it.id })
}

@Test
fun `a comment on another team's ticket is refused`() {
    assertThrows<ForbiddenException> {
        comments.create(outsider, CreateComment(ticketId = ticket.id, body = "hello"))
    }
}

@Test
fun `commenting records one activity row`() {
    comments.create(actor, CreateComment(ticketId = ticket.id, body = "reproduced"))
    assertEquals("commented", activity.forEntity("ticket", ticket.id).first().kind)
}
```

- [ ] **Step 2: Run them and watch them fail**

Run, from `apps/api`: `./gradlew test --tests '*CommentServiceTest*'`
Expected: FAIL — `CommentService` does not exist.

- [ ] **Step 3: Implement**

`requireTicket` before anything else, exactly as `TicketService.create` does. Handles are
resolved against `users` at write time and stored; an unresolved handle stays plain text in
the body rather than failing the write.

- [ ] **Step 4: Run the suite**

Run, from `apps/api`: `./gradlew test`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add -A apps/api/src/main/kotlin/dev/kanso apps/api/src/test/kotlin/dev/kanso
git commit -m "feat(api): comments, with mentions resolved when they are written"
```

---

### Task 9: `LabelService`, team-scoped

**Files:**
- Create: `apps/api/src/main/kotlin/dev/kanso/service/LabelService.kt`
- Create: `apps/api/src/main/kotlin/dev/kanso/repo/LabelRepository.kt`
- Create: `apps/api/src/main/kotlin/dev/kanso/api/LabelController.kt`
- Test: `apps/api/src/test/kotlin/dev/kanso/service/LabelServiceTest.kt`

**Interfaces:**
- Consumes: `TicketAccess`, `ActivityService.record`.
- Produces: `LabelService.list(teamId): List<LabelRow>`,
  `LabelService.create(actor, teamId, name, colour): LabelRow`,
  `LabelService.attach(actor, ticketId, labelId)`,
  `LabelService.detach(actor, ticketId, labelId)`, with
  `LabelRow(id, teamId, name, colour)`. `GET /api/teams/{id}/labels`,
  `POST /api/teams/{id}/labels`, `PUT /api/tickets/{id}/labels`.

- [ ] **Step 1: Write the failing tests**

```kotlin
@Test
fun `two teams may both own the name sync`() {
    labels.create(actor, core.id, "sync", "indigo")
    val other = labels.create(actor, platform.id, "sync", "amber")
    assertEquals("sync", other.name)
}

@Test
fun `a label from another team cannot be attached`() {
    val foreign = labels.create(actor, platform.id, "sync", "amber")
    assertThrows<ConflictException> { labels.attach(actor, coreTicket.id, foreign.id) }
}
```

The second is the whole point of team scoping, and it is the assertion a future
saved-view filter leans on.

- [ ] **Step 2: Run them and watch them fail**

Run, from `apps/api`: `./gradlew test --tests '*LabelServiceTest*'`
Expected: FAIL.

- [ ] **Step 3: Implement**

- [ ] **Step 4: Run the suite**

Run, from `apps/api`: `./gradlew test`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add -A apps/api/src/main/kotlin/dev/kanso apps/api/src/test/kotlin/dev/kanso
git commit -m "feat(api): team-scoped labels"
```

---

### Task 10: Carry the three onto the client, and `openTicket` with them

**Files:**
- Create: `apps/web/src/lib/api/social.ts`, `apps/web/src/lib/queries/social.ts`
- Modify: `apps/web/src/lib/api/index.ts`, `apps/web/src/lib/queries/index.ts`
- Modify: `apps/web/src/lib/api/core.ts` — `Preferences.openTicket`, `DEFAULT_PREFERENCES`
- Modify: `apps/api/.../settings/PreferencesService.kt` and its DTO
- Test: `apps/web/src/lib/api/social.test.ts`

**Interfaces:**
- Consumes: the endpoints from Tasks 7–9.
- Produces: `socialApi.comments(ticketId)`, `socialApi.comment(body)`,
  `socialApi.deleteComment(id)`, `socialApi.labels(teamId)`, `socialApi.activity(entityType,
  entityId)`; hooks `useComments`, `useCreateComment`, `useLabels`, `useActivity`.
  `Preferences.openTicket: "panel" | "page"`, default `"panel"`. Slice A reads it;
  slice 0 only makes it exist end to end.

- [ ] **Step 1: Write the failing test**

```ts
it("defaults openTicket to the panel", () => {
  expect(DEFAULT_PREFERENCES.openTicket).toBe("panel");
});
```

- [ ] **Step 2: Run it, watch it fail, implement, run it again**

Run, from `apps/web`: `pnpm vitest run src/lib/api/social.test.ts`
Then `pnpm typecheck && pnpm test`.
Expected: FAIL, then PASS.

- [ ] **Step 3: Commit**

```bash
git add -A apps/web/src/lib apps/api/src/main/kotlin/dev/kanso/settings
git commit -m "feat(web): client for comments, labels and activity"
```

---

### Task 11: Record the slice, then hand out the six

**Files:**
- Modify: `docs/architecture.md`, `docs/architecture.fr.md` — one section on the activity
  log, saying plainly that it is written in the business transaction and why `pg_notify`
  could not be the source
- Modify: `docs/follow-ups.md` — anything this slice left

- [ ] **Step 1: Write the architecture section, both languages**

- [ ] **Step 2: Full verification before the fan-out**

Run, from `apps/api`: `./gradlew test`
Run, from `apps/web`: `pnpm typecheck && pnpm test && pnpm build`
Run, from the root: `pnpm test:e2e`
Expected: PASS. This is the tree six branches will be cut from; a red suite here is six
red branches.

- [ ] **Step 3: Commit**

```bash
git add -A docs
git commit -m "docs: say where the activity log is written, and why not on pg_notify"
```
