# Scoped Timeline and Overlap Warnings Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the timeline say who owns each bar and whether the plan still holds — team-membership authorization on every ticket mutation, read-only context rows from other teams, an amber warning on broken dependencies, and a status pill on every bar.

**Architecture:** One new Kotlin service, `TicketAccess`, answers "may this actor edit this ticket" for both the mutations and the timeline's `editable` field, so the rule exists once. `TimelineService` widens its scope to the projects the scope shares and the dependency closure, marking the extra rows `context`. The web client obeys the server's `editable` and never re-derives it. Two new response fields — `TimelineEdge.overlap` and `TimelineView.truncated` — turn two silent facts into visible ones.

**Tech Stack:** Kotlin / Spring Boot / Exposed / Postgres (Testcontainers, `kotlin.test`) on the API; Next.js 16 / React 19 / TanStack Query / Zustand / Vitest on the web; Playwright for end-to-end.

## Global Constraints

- Spec: `docs/superpowers/specs/2026-08-11-scoped-timeline-and-overlap-warnings-design.md`. Read it before Task 1.
- **No database migration.** `team_members` already has every column the rule needs.
- Tabs, not spaces, in Kotlin files. Two spaces in TypeScript. Match the surrounding file.
- Kotlin tests extend `PostgresTest` and are annotated `@Transactional`; `EventPublisher` fires on `afterCommit`, so **no test in this suite can assert on a published event**.
- Authorization refusals throw `org.springframework.security.access.AccessDeniedException`. `ApiExceptionHandler` already maps it to a 403 with its own message. Do not add a new exception class or a new handler.
- Every refusal message names the **team by name**, never by UUID.
- Comments in this codebase explain *why*, not *what*. A comment restating the code will be rejected in review.
- Run the API suite with `cd apps/api && ./gradlew test`. A single test: `./gradlew test --tests 'dev.kanso.service.TicketAccessTest'`.
- Run the web suite with `cd apps/web && npm test`. Typecheck with `npm run typecheck`.
- Run Playwright from the repo root with `npx playwright test e2e/<file>`. Read `e2e/README.md` first: **a non-default `WEB_PORT` also needs `KANSO_WEB_ORIGIN`, or CORS makes every visitor render as a member** — which on a permissions scenario means green for the wrong reason.

---

## File Structure

**Created**

| Path | Responsibility |
|---|---|
| `apps/api/src/main/kotlin/dev/kanso/service/TicketAccess.kt` | The single authorization rule, plus its batched form for the timeline |
| `apps/api/src/test/kotlin/dev/kanso/service/TicketAccessTest.kt` | The rule's five cases |
| `apps/api/src/test/kotlin/dev/kanso/service/TicketAuthorizationTest.kt` | 403s from the mutating services, and the escape hatch that is closed |
| `apps/web/src/components/dialogs/members-section.tsx` | Team membership editing, owner/admin only |
| `apps/web/src/lib/actions.test.ts` additions | (existing file) read-only predicates |
| `e2e/13-scoped-timeline.spec.ts` | Scenario 13: two teams, one chart |

**Modified**

| Path | Change |
|---|---|
| `apps/api/.../service/TimelineService.kt` | `overlap`, own/context scope, `editable`, `teamKey`, `truncated` |
| `apps/api/.../api/Dtos.kt` | The four new response fields |
| `apps/api/.../api/TimelineController.kt` | Passes the acting user |
| `apps/api/.../api/TicketController.kt` | Injects `CurrentUser`, passes the actor to four calls |
| `apps/api/.../service/TicketService.kt` | `patch` and `delete` take an actor |
| `apps/api/.../service/ScheduleService.kt` | `link` and `unlink` take an actor |
| `apps/api/.../repo/TicketRepository.kt` | `findByProjectIds` |
| `apps/web/src/lib/api.ts` | Timeline types, three member calls |
| `apps/web/src/lib/queries.ts` | `useTeamMembers`, `useAddMember`, `useRemoveMember` |
| `apps/web/src/lib/actions.ts` | `canPlan` guard on the eight writing chart actions |
| `apps/web/src/components/timeline/bar.tsx` | The status pill sibling |
| `apps/web/src/components/timeline/row.tsx` | Context rows, `editable`, the ⚠ badge |
| `apps/web/src/components/timeline/view.tsx` | Read-only scope, the truncation banner |
| `apps/web/src/components/timeline/arrows.tsx` | The amber overlap marker |
| `apps/web/src/components/timeline/tray.tsx` | No drop when the scope is read-only |
| `apps/web/src/components/dialogs/team-dialog.tsx` | Hosts the members section |
| `apps/web/src/app/timeline.css` | Pill, context, overlap, banner |
| `e2e/12-timeline.spec.ts`, `e2e/mouse.spec.ts` | Bar accessible names gain the status suffix |
| `docs/follow-ups.md` | Supersede the "two implementations of one fact" note; record the month-zoom cost |

---

# STAGE 1 — What the bar says

No permission model involved. Mergeable on its own.

---

### Task 1: `overlap` — the broken dependency the cascade never looks at

**Files:**
- Modify: `apps/api/src/main/kotlin/dev/kanso/service/TimelineService.kt:40-46` (the `TimelineEdge` class), `:100`, `:124-133`, `:139-150`
- Modify: `apps/api/src/main/kotlin/dev/kanso/api/Dtos.kt:340-345`, `:379-381`
- Test: `apps/api/src/test/kotlin/dev/kanso/service/TimelineServiceTest.kt`

**Interfaces:**
- Consumes: nothing from earlier tasks.
- Produces: `TimelineEdge(predecessorId: UUID, successorId: UUID, violated: Boolean, overlap: Boolean, outOfScope: Boolean)` and `TimelineDependencyResponse(predecessorId, successorId, violated, overlap, outOfScope)`. Task 2 reads both.

- [ ] **Step 1: Write the two failing tests**

Append to `apps/api/src/test/kotlin/dev/kanso/service/TimelineServiceTest.kt`, inside the class:

```kotlin
	@Test
	fun `a successor that starts before its predecessor ends is an overlap, not a violation`() {
		val first = ticket("First", null, 1, 10)
		val second = ticket("Second", null, 20, 25)
		deps.insert(first, second)
		// Dragged backwards, under its own predecessor. `Cascade` never examines this
		// edge: its descent only considers a node one of whose predecessors moved.
		tickets.patch(second, TicketPatch(start = day(5), due = day(9)))

		val edge = timeline.load(teamId = team.id, projectId = null)
			.dependencies.single { it.predecessorId == first && it.successorId == second }

		assertEquals(true, edge.overlap, "the constraint is broken right now")
		assertEquals(false, edge.violated, "but the successor is not done, so the cascade could still repair it")
	}

	@Test
	fun `a done successor is violated and not merely overlapping`() {
		val first = ticket("Predecessor", null, 10, 20)
		val second = ticket("Finished early", null, 1, 5, status = TicketStatus.DONE)
		deps.insert(first, second)

		val edge = timeline.load(teamId = team.id, projectId = null)
			.dependencies.single { it.predecessorId == first && it.successorId == second }

		assertEquals(true, edge.violated)
		assertEquals(false, edge.overlap, "the two are exclusive: one names what can be repaired, the other what cannot")
	}
```

- [ ] **Step 2: Run them and watch them fail**

Run: `cd apps/api && ./gradlew test --tests 'dev.kanso.service.TimelineServiceTest'`
Expected: FAIL — `Unresolved reference: overlap`.

- [ ] **Step 3: Add the field to the domain type**

In `TimelineService.kt`, replace the `TimelineEdge` declaration:

```kotlin
data class TimelineEdge(
	val predecessorId: UUID,
	val successorId: UUID,
	/**
	 * The cascade cannot repair this edge, which happens exactly when the successor is
	 * done. Distinct from [overlap] on purpose: "it is finished, too late" and "move the
	 * successor and it is fixed" are different sentences, and one red state would print
	 * only the first.
	 */
	val violated: Boolean,
	/** Broken now, and repairable: the successor starts before the predecessor ends and is not done. */
	val overlap: Boolean,
	/** True when the other end is absent from this response — the view draws a stub. */
	val outOfScope: Boolean,
)
```

- [ ] **Step 4: Compute it**

In `TimelineService.kt`, replace the private `violatedEdges` function with one pass that answers both, and rename it for what it now returns:

```kotlin
	/**
	 * Which edges are broken, and which kind of broken.
	 *
	 * This deliberately reports more than [dev.kanso.schedule.Cascade] does, and the
	 * divergence must survive review. The two answer different questions: the cascade
	 * reports what *this request* could not repair, and skips any node none of whose
	 * predecessors moved — so dragging a successor backwards under its own predecessor
	 * never examines that edge at all. This reports what is broken *now*, including
	 * breakage that predates every request.
	 */
	private fun brokenEdges(byId: Map<UUID, Ticket>, edges: List<Edge>): Map<Edge, Boolean> =
		edges.mapNotNull { edge ->
			val predecessorEnd = byId[edge.predecessorId]?.let { it.due?.at ?: it.start?.at }
			val successor = byId[edge.successorId]
			val successorStart = successor?.let { it.start?.at ?: it.due?.at }
			if (predecessorEnd == null || successorStart == null) return@mapNotNull null
			if (!successorStart.isBefore(predecessorEnd)) return@mapNotNull null
			// The value is "is this one the cascade cannot repair".
			edge to (successor.status == TicketStatus.DONE)
		}.toMap()
```

Replace the `val violated = violatedEdges(byId, edges)` line at `:100` with:

```kotlin
		val broken = brokenEdges(byId, edges)
```

And in the `dependencies = edges.filter { … }.map { … }` block, replace the `TimelineEdge` construction:

```kotlin
					.map {
						val unrepairable = broken[it]
						TimelineEdge(
							predecessorId = it.predecessorId,
							successorId = it.successorId,
							violated = unrepairable == true,
							overlap = unrepairable == false,
							outOfScope = it.predecessorId !in scopeIds || it.successorId !in scopeIds,
						)
					},
```

- [ ] **Step 5: Carry it on the wire**

In `Dtos.kt`, add the field to the response class:

```kotlin
data class TimelineDependencyResponse(
	val predecessorId: UUID,
	val successorId: UUID,
	val violated: Boolean,
	val overlap: Boolean,
	val outOfScope: Boolean,
)
```

and to the mapping inside `TimelineResponse.of`:

```kotlin
			dependencies = view.dependencies.map {
				TimelineDependencyResponse(
					it.predecessorId,
					it.successorId,
					it.violated,
					it.overlap,
					it.outOfScope,
				)
			},
```

- [ ] **Step 6: Run the whole API suite**

Run: `cd apps/api && ./gradlew test`
Expected: PASS, including the pre-existing `TimelineServiceTest` cases.

- [ ] **Step 7: Commit**

```bash
git add apps/api/src/main/kotlin/dev/kanso/service/TimelineService.kt \
        apps/api/src/main/kotlin/dev/kanso/api/Dtos.kt \
        apps/api/src/test/kotlin/dev/kanso/service/TimelineServiceTest.kt
git commit -m "feat(api): an overlap the cascade never looks at is now reported

Cascade.apply skips any node none of whose predecessors moved, so dragging a
successor backwards under its own predecessor breaks the edge in silence. The
timeline now answers what is broken now, not only what the last request failed
to repair."
```

---

### Task 2: The amber arrow and the ⚠ on the successor

**Files:**
- Modify: `apps/web/src/lib/api.ts:125-131`
- Modify: `apps/web/src/components/timeline/arrows.tsx:31`, `:120-180`, `:360-400`
- Modify: `apps/web/src/components/timeline/row.tsx:44-56`
- Modify: `apps/web/src/app/timeline.css`

**Interfaces:**
- Consumes: `TimelineDependency.overlap: boolean` from Task 1.
- Produces: `overlapNotice(deps: TimelineDependency[], ticketId: string, nameOf: (id: string) => string): string | undefined` exported from `apps/web/src/components/timeline/row.tsx`. Task 10's end-to-end scenario asserts on the string it returns.

- [ ] **Step 1: Extend the client type**

In `apps/web/src/lib/api.ts`:

```ts
export type TimelineDependency = {
  predecessorId: string;
  successorId: string;
  /** The cascade cannot repair this: the successor is done and starts too early. */
  violated: boolean;
  /** Broken now and repairable by moving the successor. Exclusive with `violated`. */
  overlap: boolean;
  /** The other end is outside this response, so the arrow is drawn as a stub. */
  outOfScope: boolean;
};
```

- [ ] **Step 2: Paint the arrow**

In `arrows.tsx`, the `Arrow` type at `:31` gains `overlap: boolean` beside `violated`, and each of the three `Arrow` constructions (around `:136`, `:159`, `:174`) gains `overlap: dep.overlap`.

Add a third `<marker>` next to `tl-arrowhead-violated`, keeping the same `markerUnits="userSpaceOnUse"`:

```tsx
        <marker
          id="tl-arrowhead-overlap"
          viewBox="0 0 6 6"
          refX="5"
          refY="3"
          markerWidth="6"
          markerHeight="6"
          orient="auto"
          markerUnits="userSpaceOnUse"
        >
          <path className="tl-arrowhead" data-overlap="" d="M 0 0 L 6 3 L 0 6 z" />
        </marker>
```

On the drawn path, `data-violated` gains a sibling and the marker choice gains a branch. Violated first: an edge is never both, but if the two ever disagreed the unrepairable one is the news.

```tsx
          data-violated={arrow.violated ? "" : undefined}
          data-overlap={arrow.overlap ? "" : undefined}
          markerEnd={`url(#${
            arrow.violated
              ? "tl-arrowhead-violated"
              : arrow.overlap
                ? "tl-arrowhead-overlap"
                : "tl-arrowhead"
          })`}
```

Extend the accessible sentence built around `:124-127` so the overlap has words of its own:

```tsx
    const trouble = dep.violated
      ? ` · violated: ${predecessor} ends after ${successor} started`
      : dep.overlap
        ? ` · ${successor} starts before ${predecessor} ends`
        : "";
```

(Replace the existing `dep.violated ? … : ""` expression with this, and keep the variable name the surrounding code already uses.)

- [ ] **Step 3: Write the badge helper and render it**

At the top of `row.tsx`, above `TimelineRow`:

```tsx
/**
 * What the ⚠ on a successor's row says, or nothing when its dependencies all hold.
 *
 * Derived from the edges the response already carries rather than from a field of its
 * own: this is a projection of data in hand, not a second copy of a rule.
 */
export function overlapNotice(
  deps: TimelineDependency[],
  ticketId: string,
  nameOf: (id: string) => string | undefined,
): string | undefined {
  const broken = deps.filter(
    (dep) => dep.successorId === ticketId && (dep.overlap || dep.violated),
  );
  if (broken.length === 0) return undefined;
  if (broken.length > 1) return `${broken.length} dependencies not respected`;
  const name = nameOf(broken[0].predecessorId);
  return name ? `starts before ${name} ends` : "starts before a dependency ends";
}
```

`TimelineRow` takes two new props, `deps: TimelineDependency[]` and `nameOf: (id: string) => string | undefined`, and the name cell renders the glyph:

```tsx
  const notice = row.kind === "ticket" ? overlapNotice(deps, row.ticket.id, nameOf) : undefined;

  return (
    <div className="tl-row" data-kind={row.kind}>
      <div
        className="tl-name"
        title={row.kind === "project" ? row.project.name : row.ticket.title}
      >
        {notice && (
          <span className="tl-warn" role="img" aria-label={notice} title={notice}>
            ⚠
          </span>
        )}
        {name}
      </div>
```

In `view.tsx`, pass them where `TimelineRow` is rendered:

```tsx
            <TimelineRow
              key={rowKey(row)}
              row={row}
              deps={view?.dependencies ?? []}
              nameOf={nameOf}
              origin={bounds.origin}
              …
```

with `nameOf` memoised beside `rows`:

```tsx
  /** A ticket's identifier by id, for the sentence the ⚠ prints. */
  const nameOf = useMemo(() => {
    const byId = new Map((view?.tickets ?? []).map((ticket) => [ticket.id, ticket.identifier]));
    return (id: string) => byId.get(id);
  }, [view]);
```

- [ ] **Step 4: Style them**

Append to `apps/web/src/app/timeline.css`, beside the existing `.tl-arrowhead[data-violated]` rule:

```css
/*
 * Amber against the violated red, and a distinct arrowhead: an overlap is a thing
 * somebody can still fix by moving a bar, and printing it in the same red as the case
 * nobody can fix would lose the only distinction that tells a reader what to do.
 */
.tl-arrow[data-overlap] {
  stroke: var(--warn, #d08a1e);
}

.tl-arrowhead[data-overlap] {
  fill: var(--warn, #d08a1e);
}

/* Shape as well as colour, like data-state's hatch: the glyph survives greyscale. */
.tl-warn {
  margin-right: 4px;
  color: var(--warn, #d08a1e);
  font-size: 11px;
}
```

If `--warn` is not already defined in `apps/web/src/app/globals.css`, add it to the same block that defines `--status-*`, in both the light and dark palettes.

- [ ] **Step 5: Typecheck and run the web suite**

Run: `cd apps/web && npm run typecheck && npm test`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add apps/web/src/lib/api.ts apps/web/src/components/timeline/arrows.tsx \
        apps/web/src/components/timeline/row.tsx apps/web/src/components/timeline/view.tsx \
        apps/web/src/app/timeline.css apps/web/src/app/globals.css
git commit -m "feat(web): a broken dependency says so, in amber and in words"
```

---

### Task 3: The status pill

**Files:**
- Modify: `apps/web/src/components/timeline/bar.tsx:84-124`, `:311-341`
- Modify: `apps/web/src/components/timeline/row.tsx` (the ticket branch of `bar()`)
- Modify: `apps/web/src/app/timeline.css`
- Modify: `e2e/12-timeline.spec.ts`, `e2e/mouse.spec.ts`

**Interfaces:**
- Consumes: nothing from earlier tasks.
- Produces: a ticket bar's accessible name becomes `` `${identifier}: ${title} — ${statusLabel}` ``. Every later task and every end-to-end scenario that queries a bar by role and name must use this shape.

- [ ] **Step 1: Add the prop and the element**

`BarProps` in `bar.tsx` gains, next to `done`:

```tsx
  /**
   * The pill's colour and the word appended to the accessible name. Absent on a project
   * bar, which has a status of its own that means something else.
   */
  status?: TicketStatus;
```

with `import type { KansoInstant, TicketStatus } from "@/lib/api";` at the top, and two module-level maps copied from `pills.tsx` so the chart and the list cannot drift:

```tsx
const STATUS_LABELS: Record<TicketStatus, string> = {
  backlog: "Backlog",
  todo: "Todo",
  in_progress: "In progress",
  in_review: "In review",
  done: "Done",
  canceled: "Canceled",
};

const STATUS_COLORS: Record<TicketStatus, string> = {
  backlog: "var(--status-backlog)",
  todo: "var(--status-todo)",
  in_progress: "var(--status-progress)",
  in_review: "var(--status-review)",
  done: "var(--status-done)",
  canceled: "var(--status-canceled)",
};
```

The accessible name gains the status, and the pill is rendered as a **sibling** before the bar — the same montage as `.tl-link`, and for the same reason: `.tl-bar` has `overflow: hidden`, so a child straddling the edge would be cut in half.

```tsx
  const accessibleName = status ? `${name} — ${STATUS_LABELS[status]}` : name;
```

Use `accessibleName` in `aria-label` and in the `title` template, then add before the `<div className="tl-bar" …>`:

```tsx
      {status && (
        <span
          className="tl-status"
          aria-hidden="true"
          data-status={status}
          style={{ left, background: STATUS_COLORS[status] }}
        />
      )}
```

`aria-hidden`, because the word is already in the bar's own name — a second announcement per bar is noise on a chart of five hundred rows.

- [ ] **Step 2: Pass it from the row**

In `row.tsx`, in the ticket branch, add `status={ticket.status}` to the `TimelineBar` props. Leave the project branch alone.

- [ ] **Step 3: Style it**

Append to `timeline.css`:

```css
/*
 * Straddling the bar's left edge, half in and half out, and a sibling of it for the
 * same reason `.tl-link` is one: the bar clips its children. Straddling rather than
 * sitting beside, because a pill fully outside would eat the gutter and collide with
 * the neighbouring bar at month zoom, where a column is three pixels.
 */
.tl-status {
  position: absolute;
  top: 50%;
  width: 8px;
  height: 8px;
  margin-top: -4px;
  margin-left: -4px;
  border-radius: 50%;
  border: 1px solid var(--bg);
  z-index: 2;
  pointer-events: none;
}
```

- [ ] **Step 4: Update the end-to-end names**

Find every bar lookup: `rg -n 'getByRole\("button", \{ name: .*: ' e2e/`. Each name that addresses a **ticket** bar gains the status suffix. A seeded ticket is created `todo` unless the seed says otherwise, so `KAN-1: Title` becomes `KAN-1: Title — Todo`.

Prefer a prefix match where the scenario does not care about the status, which is most of them:

```ts
page.getByRole("button", { name: new RegExp(`^${escapeRe(ticket.identifier)}: `) });
```

Project bars are unchanged: they carry no status and their name is still `` `${project.name}: project` ``.

- [ ] **Step 5: Typecheck, unit test, and run the two touched suites**

Run: `cd apps/web && npm run typecheck && npm test`
Then, from the repo root with the stack up: `npx playwright test e2e/12-timeline.spec.ts e2e/mouse.spec.ts`
Expected: PASS. If a bar cannot be found, the name suffix is the first thing to check — it is the only handle the suite has on a bar.

- [ ] **Step 6: Commit**

```bash
git add apps/web/src/components/timeline/bar.tsx apps/web/src/components/timeline/row.tsx \
        apps/web/src/app/timeline.css e2e/12-timeline.spec.ts e2e/mouse.spec.ts
git commit -m "feat(web): a bar says what state its work is in

The pill is a sibling straddling the left edge, like the link handle on the right:
the bar clips its children, and a pill fully outside would collide with the
neighbouring bar at month zoom."
```

---

# STAGE 2 — Who may move it

---

### Task 4: `TicketAccess`, the rule

**Files:**
- Create: `apps/api/src/main/kotlin/dev/kanso/service/TicketAccess.kt`
- Test: `apps/api/src/test/kotlin/dev/kanso/service/TicketAccessTest.kt`

**Interfaces:**
- Consumes: `TeamRepository.teamIdsFor(userId: UUID): List<UUID>`, `TeamRepository.ancestorIds(id: UUID): List<UUID>`, `TeamRepository.members(teamId: UUID): List<TeamMember>`, `TeamRepository.findById(id: UUID): Team?`, `User.instanceRole.canConfigureInstance`.
- Produces:
  - `TicketAccess.mayEdit(actor: User, ticket: Ticket): Boolean`
  - `TicketAccess.require(actor: User, ticket: Ticket)` — throws `AccessDeniedException`
  - `TicketAccess.requireTeam(actor: User, teamId: UUID)` — throws `AccessDeniedException`
  - `TicketAccess.editableTeams(actor: User, teamIds: Set<UUID>): Set<UUID>`

- [ ] **Step 1: Write the failing tests**

Create `apps/api/src/test/kotlin/dev/kanso/service/TicketAccessTest.kt`:

```kotlin
package dev.kanso.service

import dev.kanso.PostgresTest
import dev.kanso.auth.hash
import dev.kanso.domain.InstanceRole
import dev.kanso.domain.MemberRole
import dev.kanso.domain.TicketPriority
import dev.kanso.domain.TicketStatus
import dev.kanso.domain.User
import dev.kanso.repo.TeamRepository
import dev.kanso.repo.UserRepository
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.transaction.annotation.Transactional
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

@Transactional
class TicketAccessTest : PostgresTest() {

	@Autowired lateinit var teams: TeamService
	@Autowired lateinit var teamRepo: TeamRepository
	@Autowired lateinit var tickets: TicketService
	@Autowired lateinit var access: TicketAccess
	@Autowired lateinit var users: UserRepository
	@Autowired lateinit var encoder: PasswordEncoder

	private fun user(role: InstanceRole) = users.createLocalUser(
		email = "ta-${UUID.randomUUID()}@kanso.test",
		displayName = "Access ${role.wire}",
		passwordHash = encoder.hash("correct-horse-battery"),
		role = role,
	)

	private val admin: User by lazy { user(InstanceRole.ADMIN) }

	private fun key() = "K${UUID.randomUUID().toString().take(4).uppercase()}"

	private fun ticketIn(teamId: UUID) = tickets.create(
		teamId = teamId,
		title = "Work",
		description = null,
		status = TicketStatus.TODO,
		priority = TicketPriority.NONE,
		start = null,
		due = null,
		projectId = null,
		assigneeIds = emptyList(),
		docIds = emptyList(),
	).ticket

	@Test
	fun `an admin may edit a ticket in a team they are not in`() {
		val team = teams.create(admin, "Alone", key(), null)
		val ticket = ticketIn(team.id)
		teamRepo.addMember(team.id, user(InstanceRole.MEMBER).id, MemberRole.MEMBER)

		assertTrue(access.mayEdit(admin, ticket))
	}

	@Test
	fun `a team with no members is open to everyone`() {
		val team = teams.create(admin, "Unclaimed", key(), null)
		val ticket = ticketIn(team.id)

		assertTrue(
			access.mayEdit(user(InstanceRole.MEMBER), ticket),
			"every instance deploying this has an empty team_members; locking them all out is not an upgrade",
		)
	}

	@Test
	fun `a member of the ticket's own team may edit it`() {
		val team = teams.create(admin, "Owning", key(), null)
		val ticket = ticketIn(team.id)
		val member = user(InstanceRole.MEMBER)
		teamRepo.addMember(team.id, member.id, MemberRole.MEMBER)

		assertTrue(access.mayEdit(member, ticket))
	}

	@Test
	fun `membership is inherited downwards, never upwards`() {
		val parent = teams.create(admin, "Product", key(), null)
		val child = teams.create(admin, "Mobile", key(), parent.id)
		val parentMember = user(InstanceRole.MEMBER)
		val childMember = user(InstanceRole.MEMBER)
		teamRepo.addMember(parent.id, parentMember.id, MemberRole.MEMBER)
		teamRepo.addMember(child.id, childMember.id, MemberRole.MEMBER)

		assertTrue(
			access.mayEdit(parentMember, ticketIn(child.id)),
			"a member of Product may move work in Product / Mobile",
		)
		assertFalse(
			access.mayEdit(childMember, ticketIn(parent.id)),
			"the other direction is an escalation: joining the smallest team would grant the largest",
		)
	}

	@Test
	fun `a refusal names the team, not its uuid`() {
		val team = teams.create(admin, "Mobile", key(), null)
		val ticket = ticketIn(team.id)
		teamRepo.addMember(team.id, admin.id, MemberRole.MEMBER)
		val stranger = user(InstanceRole.MEMBER)

		val error = assertFailsWith<AccessDeniedException> { access.require(stranger, ticket) }

		assertTrue(error.message!!.contains("Mobile"), "a UUID in a dialog footer is not actionable")
		assertFalse(error.message!!.contains(team.id.toString()))
	}

	@Test
	fun `editableTeams answers the same question in bulk`() {
		val mine = teams.create(admin, "Mine", key(), null)
		val theirs = teams.create(admin, "Theirs", key(), null)
		val member = user(InstanceRole.MEMBER)
		teamRepo.addMember(mine.id, member.id, MemberRole.MEMBER)
		teamRepo.addMember(theirs.id, admin.id, MemberRole.MEMBER)

		assertEquals(setOf(mine.id), access.editableTeams(member, setOf(mine.id, theirs.id)))
	}
}
```

- [ ] **Step 2: Run them and watch them fail**

Run: `cd apps/api && ./gradlew test --tests 'dev.kanso.service.TicketAccessTest'`
Expected: FAIL — `Unresolved reference: TicketAccess`.

- [ ] **Step 3: Write the service**

Create `apps/api/src/main/kotlin/dev/kanso/service/TicketAccess.kt`:

```kotlin
package dev.kanso.service

import dev.kanso.domain.Ticket
import dev.kanso.domain.User
import dev.kanso.repo.TeamRepository
import org.springframework.security.access.AccessDeniedException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * Who may move a ticket.
 *
 * A service of its own rather than a method on [TicketService], because
 * [ScheduleService] asks the same question and a mutual dependency between the two
 * would be the wrong way to share three rules. [TimelineService] asks it too, in bulk.
 */
@Service
class TicketAccess(private val teams: TeamRepository) {

	@Transactional(readOnly = true)
	fun mayEdit(actor: User, ticket: Ticket): Boolean = mayEditTeam(actor, ticket.teamId)

	@Transactional(readOnly = true)
	fun require(actor: User, ticket: Ticket) {
		if (mayEdit(actor, ticket)) return
		throw AccessDeniedException("${nameOf(ticket.teamId)} is not one of your teams")
	}

	/** The destination side of a team move, which has no ticket row of its own yet. */
	@Transactional(readOnly = true)
	fun requireTeam(actor: User, teamId: UUID) {
		if (mayEditTeam(actor, teamId)) return
		throw AccessDeniedException("${nameOf(teamId)} is not one of your teams")
	}

	/**
	 * The subset of [teamIds] this actor may edit, in one pass.
	 *
	 * A timeline response carries up to `SCOPE_LIMIT` tickets, and asking per ticket
	 * would be two thousand ancestor walks. Per distinct team it is a few dozen.
	 */
	@Transactional(readOnly = true)
	fun editableTeams(actor: User, teamIds: Set<UUID>): Set<UUID> {
		if (teamIds.isEmpty()) return emptySet()
		if (actor.instanceRole.canConfigureInstance) return teamIds
		val mine = teams.teamIdsFor(actor.id).toSet()
		return teamIds.filterTo(mutableSetOf()) { id -> claimedBy(mine, id) }
	}

	private fun mayEditTeam(actor: User, teamId: UUID): Boolean {
		if (actor.instanceRole.canConfigureInstance) return true
		return claimedBy(teams.teamIdsFor(actor.id).toSet(), teamId)
	}

	/**
	 * The three-part rule, in the order the spec states it.
	 *
	 * The empty-team clause is the migration guarantee, not a convenience: every
	 * instance running today has an empty `team_members`, and without it deploying this
	 * locks every board behind a 403 whose only cure is a SQL prompt. An unclaimed team
	 * is an open team.
	 *
	 * Ancestry runs downwards only. A member of Product may move work in Product /
	 * Mobile; the reverse would make joining the smallest team in the instance a way to
	 * reach the largest.
	 */
	private fun claimedBy(actorTeamIds: Set<UUID>, teamId: UUID): Boolean {
		if (teamId in actorTeamIds) return true
		if (teams.members(teamId).isEmpty()) return true
		return teams.ancestorIds(teamId).any { it in actorTeamIds }
	}

	private fun nameOf(teamId: UUID): String = teams.findById(teamId)?.name ?: "That team"
}
```

- [ ] **Step 4: Run the tests**

Run: `cd apps/api && ./gradlew test --tests 'dev.kanso.service.TicketAccessTest'`
Expected: PASS, six tests.

- [ ] **Step 5: Commit**

```bash
git add apps/api/src/main/kotlin/dev/kanso/service/TicketAccess.kt \
        apps/api/src/test/kotlin/dev/kanso/service/TicketAccessTest.kt
git commit -m "feat(api): one rule for who may move a ticket

Admin passes, an unclaimed team is open, and membership is inherited down the
tree and never up. Not wired to anything yet."
```

---

### Task 5: Wire the rule into the four mutations

**Files:**
- Modify: `apps/api/src/main/kotlin/dev/kanso/service/TicketService.kt` (`patch`, `delete`)
- Modify: `apps/api/src/main/kotlin/dev/kanso/service/ScheduleService.kt` (`link`, `unlink`)
- Modify: `apps/api/src/main/kotlin/dev/kanso/api/TicketController.kt`
- Test: `apps/api/src/test/kotlin/dev/kanso/service/TicketAuthorizationTest.kt` (create)
- Modify: every existing test that calls `tickets.patch`, `tickets.delete`, `schedule.link` or `schedule.unlink`

**Interfaces:**
- Consumes: `TicketAccess` from Task 4.
- Produces: `TicketService.patch(actor: User, id: UUID, patch: TicketPatch): TicketDetail`, `TicketService.delete(actor: User, id: UUID)`, `ScheduleService.link(actor: User, predecessorId: UUID, successorId: UUID): List<UUID>`, `ScheduleService.unlink(actor: User, predecessorId: UUID, successorId: UUID)`. The actor is the **first** parameter, matching `TeamService.create(actor, …)`.

- [ ] **Step 1: Write the failing tests**

Create `apps/api/src/test/kotlin/dev/kanso/service/TicketAuthorizationTest.kt`. Reuse the fixture shape of `TicketAccessTest` — an admin, a `key()` helper, a `ticketIn(teamId)` helper — and add:

```kotlin
	@Test
	fun `a stranger cannot patch a claimed team's ticket`() {
		val team = teams.create(admin, "Mobile", key(), null)
		teamRepo.addMember(team.id, admin.id, MemberRole.MEMBER)
		val ticket = ticketIn(team.id)
		val stranger = user(InstanceRole.MEMBER)

		val error = assertFailsWith<AccessDeniedException> {
			tickets.patch(stranger, ticket.id, TicketPatch(title = "Hijacked"))
		}
		assertTrue(error.message!!.contains("Mobile"))
	}

	@Test
	fun `a ticket cannot be moved into a team the actor is not in`() {
		val mine = teams.create(admin, "Mine", key(), null)
		val theirs = teams.create(admin, "Theirs", key(), null)
		val member = user(InstanceRole.MEMBER)
		teamRepo.addMember(mine.id, member.id, MemberRole.MEMBER)
		teamRepo.addMember(theirs.id, admin.id, MemberRole.MEMBER)
		val ticket = ticketIn(mine.id)

		// The escape hatch: without the destination check, this moves someone else's
		// board into reach and the rule is defeated in two requests.
		assertFailsWith<AccessDeniedException> {
			tickets.patch(member, ticket.id, TicketPatch(teamId = theirs.id))
		}
	}

	@Test
	fun `linking asks for the successor, not the predecessor`() {
		val mine = teams.create(admin, "Mine", key(), null)
		val theirs = teams.create(admin, "Theirs", key(), null)
		val member = user(InstanceRole.MEMBER)
		teamRepo.addMember(mine.id, member.id, MemberRole.MEMBER)
		teamRepo.addMember(theirs.id, admin.id, MemberRole.MEMBER)

		// Declaring that my ticket waits on theirs commits nobody but me.
		schedule.link(member, predecessorId = ticketIn(theirs.id).id, successorId = ticketIn(mine.id).id)

		// The mirror case imposes a constraint on a ticket that is not mine.
		assertFailsWith<AccessDeniedException> {
			schedule.link(member, predecessorId = ticketIn(mine.id).id, successorId = ticketIn(theirs.id).id)
		}
	}

	@Test
	fun `the cascade still pushes tickets in teams the actor is not in`() {
		val mine = teams.create(admin, "Mine", key(), null)
		val theirs = teams.create(admin, "Theirs", key(), null)
		val member = user(InstanceRole.MEMBER)
		teamRepo.addMember(mine.id, member.id, MemberRole.MEMBER)
		teamRepo.addMember(theirs.id, admin.id, MemberRole.MEMBER)

		val predecessor = dated(mine.id, start = 1, due = 5)
		val successor = dated(theirs.id, start = 6, due = 10)
		schedule.link(admin, predecessor.id, successor.id)

		tickets.patch(member, predecessor.id, TicketPatch(due = day(20)))

		assertEquals(
			day(20).at,
			tickets.get(successor.id).ticket.start!!.at,
			"permission governs the gesture; the graph governs its consequences",
		)
	}
```

Add the two helpers this file needs beside `ticketIn`:

```kotlin
	private fun day(d: Int) = dev.kanso.domain.KansoInstant(
		java.time.OffsetDateTime.of(2026, 8, d, 0, 0, 0, 0, java.time.ZoneOffset.UTC),
		false,
	)

	private fun dated(teamId: UUID, start: Int, due: Int) = tickets.create(
		teamId = teamId,
		title = "Dated",
		description = null,
		status = TicketStatus.TODO,
		priority = TicketPriority.NONE,
		start = day(start),
		due = day(due),
		projectId = null,
		assigneeIds = emptyList(),
		docIds = emptyList(),
	).ticket
```

- [ ] **Step 2: Run and watch it fail**

Run: `cd apps/api && ./gradlew test --tests 'dev.kanso.service.TicketAuthorizationTest'`
Expected: FAIL — the four-argument `patch` and the three-argument `link` do not exist.

- [ ] **Step 3: Change the signatures and add the checks**

`TicketService` takes `private val access: TicketAccess` in its constructor. Then:

```kotlin
	@Transactional
	fun patch(actor: User, id: UUID, patch: TicketPatch): TicketDetail {
		val current = tickets.findById(id) ?: throw NotFoundException("No ticket $id")
		access.require(actor, current)
		// Both ends, not one. `TicketPatch` carries `teamId`, so a single-sided check
		// lets anyone move a foreign ticket into a team of their own and then edit it
		// freely — the whole rule defeated in two requests.
		patch.teamId?.let { access.requireTeam(actor, it) }
```

with the rest of the body unchanged.

```kotlin
	@Transactional
	fun delete(actor: User, id: UUID) {
		val ticket = tickets.findById(id) ?: throw NotFoundException("No ticket $id")
		access.require(actor, ticket)
```

`ScheduleService` takes `private val access: TicketAccess` too:

```kotlin
	@Transactional
	fun link(actor: User, predecessorId: UUID, successorId: UUID): List<UUID> {
		val predecessor = tickets.findById(predecessorId)
			?: throw NotFoundException("No ticket $predecessorId")
		val successor = tickets.findById(successorId)
			?: throw NotFoundException("No ticket $successorId")
		// The successor only. Drawing an arrow *into* my ticket declares that I wait,
		// which commits nobody else; drawing one *out of* it imposes a constraint on
		// work that is not mine.
		access.require(actor, successor)
```

```kotlin
	@Transactional
	fun unlink(actor: User, predecessorId: UUID, successorId: UUID) {
		val successor = tickets.findById(successorId)
			?: throw NotFoundException("No ticket $successorId")
		access.require(actor, successor)
		if (!dependencies.delete(predecessorId, successorId)) {
			throw NotFoundException("No dependency $predecessorId -> $successorId")
		}
```

(the existing `val successor = tickets.findById(successorId)` further down is now redundant — delete it and use the one above.)

`cascadeFrom` is **not** touched. It has no actor and takes none.

- [ ] **Step 4: Pass the actor from the controller**

`TicketController` injects `private val currentUser: CurrentUser` and the four call sites become:

```kotlin
		return TicketResponse.of(tickets.patch(currentUser.require(), id, patch))
```
```kotlin
	fun delete(@PathVariable id: UUID) = tickets.delete(currentUser.require(), id)
```
```kotlin
	): CascadeResponse = CascadeResponse(schedule.link(currentUser.require(), request.predecessorId, id))
```
```kotlin
		schedule.unlink(currentUser.require(), predecessorId, id)
```

with `import dev.kanso.auth.CurrentUser`.

- [ ] **Step 5: Fix the callers the compiler names**

`NotionPoller` calls `TicketService.patch` for inbound scalar writes and has no acting user. Give it a package-private sibling rather than a nullable actor, so "no actor" is a call site and not a branch inside the rule:

```kotlin
	/**
	 * The inbound path, which has no acting user: the poller is the instance, not a
	 * person. Unchecked on purpose and consistent with the mirror being
	 * Kanso-authoritative — reaching it at all requires instance-level access.
	 */
	@Transactional
	fun patchUnchecked(id: UUID, patch: TicketPatch): TicketDetail = applyPatch(id, patch)
```

Extract the current body of `patch` into `private fun applyPatch(id, patch)` and have the public `patch` call `access.require(...)` then `applyPatch`. Point `NotionPoller` at `patchUnchecked`.

Then run the compiler and fix each remaining test that calls the moved signatures — `TicketWorkflowTest`, `TicketProjectCoherenceTest`, `TimelineServiceTest`, `ScheduleServiceTest`, `DependencyTest` — by passing their existing `admin` fixture as the first argument. They all already have one, and an admin passes rule 1, so no test's meaning changes.

- [ ] **Step 6: Run the whole API suite**

Run: `cd apps/api && ./gradlew test`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add apps/api/src/main/kotlin/dev/kanso apps/api/src/test/kotlin/dev/kanso
git commit -m "feat(api): a ticket mutation asks whose ticket it is

patch checks both ends of a team move, because TicketPatch carries teamId and a
one-sided check is defeated in two requests. link and unlink ask for the
successor: waiting is my decision, being waited on is not."
```

---

### Task 6: Membership, from the web

**Files:**
- Modify: `apps/web/src/lib/api.ts`, `apps/web/src/lib/queries.ts`
- Create: `apps/web/src/components/dialogs/members-section.tsx`
- Modify: `apps/web/src/components/dialogs/team-dialog.tsx`

**Interfaces:**
- Consumes: `GET|POST|DELETE /api/teams/{id}/members`, already served by `TeamController`.
- Produces: `<MembersSection teamId={string} />`, rendered by `TeamDialog` when editing an existing team and the viewer can configure the instance.

- [ ] **Step 1: Add the three calls**

In `api.ts`, beside the other team calls:

```ts
export type MemberRole = "lead" | "member";

export type TeamMemberRow = { user: User; role: MemberRole };

  teamMembers: (teamId: string) => request<TeamMemberRow[]>(`/api/teams/${teamId}/members`),

  addTeamMember: (teamId: string, userId: string, role: MemberRole) =>
    request<TeamMemberRow[]>(`/api/teams/${teamId}/members`, {
      method: "POST",
      body: JSON.stringify({ userId, role }),
    }),

  removeTeamMember: (teamId: string, userId: string) =>
    request<void>(`/api/teams/${teamId}/members/${userId}`, { method: "DELETE" }),
```

Check `MemberResponse` and `AddMemberRequest` in `Dtos.kt` and make `TeamMemberRow` and the POST body match them field for field before writing the component. If `MemberRole`'s wire values differ from `"lead" | "member"`, take the ones in `dev.kanso.domain.MemberRole`.

- [ ] **Step 2: Add the hooks**

In `queries.ts`, following the shape the file already uses for a keyed read plus two invalidating mutations:

```ts
export const useTeamMembers = (teamId: string) =>
  useQuery({ queryKey: keys.teamMembers(teamId), queryFn: () => api.teamMembers(teamId) });

export const useAddTeamMember = (teamId: string) => {
  const client = useQueryClient();
  return useMutation({
    mutationFn: ({ userId, role }: { userId: string; role: MemberRole }) =>
      api.addTeamMember(teamId, userId, role),
    onSettled: () => client.invalidateQueries({ queryKey: keys.teamMembers(teamId) }),
  });
};

export const useRemoveTeamMember = (teamId: string) => {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (userId: string) => api.removeTeamMember(teamId, userId),
    // The timeline's `editable` is the server's answer to this same membership, so a
    // removal that did not invalidate it would leave handles on bars the viewer can no
    // longer move.
    onSettled: () => {
      client.invalidateQueries({ queryKey: keys.teamMembers(teamId) });
      client.invalidateQueries({ queryKey: ["timeline"] });
    },
  });
};
```

Add `teamMembers: (id: string) => ["teams", id, "members"] as const` to `keys`.

- [ ] **Step 3: Write the component**

Create `apps/web/src/components/dialogs/members-section.tsx`: the current members with their role, a `<select>` over `usePeople()` filtered to those not already in, an Add button, and a remove control per row. Follow `people-section.tsx` in `components/settings/` for the row markup and the error handling — it is the closest existing screen and it already talks to `/api/people`.

Every failure reports through the dialog's footer, the way `team-dialog.tsx` already routes server messages.

- [ ] **Step 4: Mount it**

In `team-dialog.tsx`, below the existing fields and only when editing:

```tsx
      {id && me.data?.user.instanceRole !== "member" && <MembersSection teamId={id} />}
```

A new team has no id to post against, so the section is absent there — and it is worth a sentence in the empty case: *Members can be added once the team exists.*

- [ ] **Step 5: Typecheck, lint, and check it in a browser**

Run: `cd apps/web && npm run typecheck && npm run lint`
Then start the stack and add a member to a team, reload, confirm the row persists.

- [ ] **Step 6: Commit**

```bash
git add apps/web/src/lib/api.ts apps/web/src/lib/queries.ts \
        apps/web/src/components/dialogs/members-section.tsx \
        apps/web/src/components/dialogs/team-dialog.tsx
git commit -m "feat(web): team membership becomes editable from the app

The endpoints have existed since the CRUD branch and the client never called
them. Without this surface the authorization rule has no way to be populated."
```

---

### Task 7: The widened timeline — own, context, editable, truncated

**Files:**
- Modify: `apps/api/src/main/kotlin/dev/kanso/repo/TicketRepository.kt`
- Modify: `apps/api/src/main/kotlin/dev/kanso/service/TimelineService.kt`
- Modify: `apps/api/src/main/kotlin/dev/kanso/api/TimelineController.kt`, `Dtos.kt`
- Test: `apps/api/src/test/kotlin/dev/kanso/service/TimelineServiceTest.kt`

**Interfaces:**
- Consumes: `TicketAccess.editableTeams` (Task 4).
- Produces: `TimelineService.load(actor: User, teamId: UUID?, projectId: UUID?): TimelineView`; `TimelineTicket` gains `teamKey: String`, `context: Boolean`, `editable: Boolean`; `TimelineView` gains `truncated: Boolean`. `TimelineTicketResponse` and `TimelineResponse` mirror them.

- [ ] **Step 1: Write the failing tests**

Append to `TimelineServiceTest.kt` — note that every existing call to `timeline.load` in this file gains `admin` as its first argument in Step 5:

```kotlin
	@Test
	fun `a ticket of another team in a shared project is drawn as context`() {
		val other = teams.create(admin, "Other", "O${UUID.randomUUID().toString().take(4).uppercase()}", null)
		val project = projects.create(
			name = "Shared",
			status = ProjectStatus.IN_PROGRESS,
			start = null,
			end = null,
			leadUserId = null,
			teamId = team.id,
			docIds = emptyList(),
		).project
		val mine = ticket("Mine", project.id, 1, 5)
		val theirs = tickets.create(
			teamId = other.id,
			title = "Theirs",
			description = null,
			status = TicketStatus.TODO,
			priority = TicketPriority.NONE,
			start = day(2),
			due = day(6),
			projectId = project.id,
			assigneeIds = emptyList(),
			docIds = emptyList(),
		).ticket.id
		teamRepo.addMember(team.id, member.id, MemberRole.MEMBER)
		teamRepo.addMember(other.id, admin.id, MemberRole.MEMBER)

		val view = timeline.load(member, teamId = team.id, projectId = null)

		val ours = view.tickets.single { it.id == mine }
		val visitor = view.tickets.single { it.id == theirs }
		assertEquals(false, ours.context)
		assertEquals(true, ours.editable)
		assertEquals(true, visitor.context, "a shared project is who else is working here")
		assertEquals(false, visitor.editable)
		assertEquals(other.key, visitor.teamKey)
	}

	@Test
	fun `a ticket in the dependency closure is drawn rather than left as a stub`() {
		val other = teams.create(admin, "Chained", "C${UUID.randomUUID().toString().take(4).uppercase()}", null)
		val here = ticket("Here", null, 1, 10)
		val elsewhere = tickets.create(
			teamId = other.id,
			title = "Elsewhere",
			description = null,
			status = TicketStatus.TODO,
			priority = TicketPriority.NONE,
			start = day(10),
			due = day(30),
			projectId = null,
			assigneeIds = emptyList(),
			docIds = emptyList(),
		).ticket.id
		deps.insert(here, elsewhere)

		val view = timeline.load(admin, teamId = team.id, projectId = null)

		assertTrue(view.tickets.any { it.id == elsewhere && it.context })
		assertEquals(
			false,
			view.dependencies.single().outOfScope,
			"both ends are in the response now, so there is nothing to stub",
		)
	}
```

Add the two fixtures the first test needs, beside `admin`:

```kotlin
	private val member: User by lazy {
		users.createLocalUser(
			email = "tl-member-${UUID.randomUUID()}@kanso.test",
			displayName = "Timeline member",
			passwordHash = encoder.hash("correct-horse-battery"),
			role = InstanceRole.MEMBER,
		)
	}

	@Autowired lateinit var teamRepo: dev.kanso.repo.TeamRepository
```

- [ ] **Step 2: Run and watch it fail**

Run: `cd apps/api && ./gradlew test --tests 'dev.kanso.service.TimelineServiceTest'`
Expected: FAIL — `load` takes two arguments, and `context` does not exist.

- [ ] **Step 3: Add the repository query**

In `TicketRepository.kt`, beside `search`:

```kotlin
	/**
	 * Every unarchived ticket in these projects, whatever team owns it.
	 *
	 * `search` cannot express it: its `projectId` filter is one id, and widening that
	 * parameter would change the meaning of a call every controller already makes.
	 */
	fun findByProjectIds(projectIds: Collection<UUID>, limit: Int): List<Ticket> =
		if (projectIds.isEmpty()) emptyList()
		else Tickets.selectAll()
			.where { (Tickets.projectId inList projectIds) and (Tickets.archived eq false) }
			.orderBy(Tickets.updatedAt to SortOrder.DESC)
			.limit(limit)
			.map { it.toTicket() }
```

- [ ] **Step 4: Rewrite `load`**

`TimelineService` takes `private val access: TicketAccess`. `load` gains an actor and resolves two sets:

```kotlin
	@Transactional(readOnly = true)
	fun load(actor: User, teamId: UUID?, projectId: UUID?): TimelineView {
		val teamIds = teamId?.let { teams.descendantIds(it) }
		val own = tickets.search(
			teamIds = teamIds,
			projectId = projectId,
			includeArchived = false,
			limit = SCOPE_LIMIT,
		)
		val ownIds = own.map { it.id }.toSet()

		// The closure, not the scope: anchoring the critical path on what happens to be
		// visible would repaint identical data when the filter changes.
		val componentIds = dependencies.componentIds(ownIds)
		val graphTickets = tickets.findAllById(componentIds)
		val edges = dependencies.edgesTouching(componentIds)

		// Who else is working in the projects this scope has work in. The widening the
		// reader asked for, and the part of the response most able to surprise: a shared
		// project pulls in another team's whole board.
		val shared = tickets.findByProjectIds(
			own.mapNotNull { it.projectId }.toSet(),
			limit = SCOPE_LIMIT,
		)

		val context = (shared + graphTickets)
			.filter { it.id !in ownIds && !it.archived }
			.distinctBy { it.id }
		val drawn = own + context
		val drawnIds = drawn.map { it.id }.toSet()
		val truncated = own.size >= SCOPE_LIMIT || shared.size >= SCOPE_LIMIT
		…
```

The `deadlines`, `byId`, `slack` and `broken` computations stay as they are, but `byId` and the critical-path node list now cover `drawn + graphTickets` rather than `graphTickets` alone. Keys are fetched for every team in `drawn`, not only in `own`, so a context row can print its owner's key.

`editable` comes from one bulk call:

```kotlin
		val editableTeams = access.editableTeams(actor, drawn.map { it.teamId }.toSet())
```

and each row is built with:

```kotlin
					TimelineTicket(
						…
						teamKey = keys[ticket.teamId] ?: "?",
						context = ticket.id !in ownIds,
						// The server's answer, so the client has no rule to re-derive and
						// no membership graph to hold.
						editable = ticket.teamId in editableTeams,
					)
```

`outOfScope` is now `predecessorId !in drawnIds || successorId !in drawnIds`, and the `dependencies` filter widens to `drawnIds` the same way. `unscheduled` stays built from `own` only — the tray is where *your* undated work waits, and filling it with other teams' would make it unemptiable.

Add the three fields to `TimelineTicket` and `truncated` to `TimelineView`, each with a one-line comment saying what it answers.

- [ ] **Step 5: Update the controller, the DTOs, and the existing tests**

`TimelineController` injects `CurrentUser` and calls `timeline.load(currentUser.require(), teamId, projectId)`.

`TimelineTicketResponse` gains `teamKey: String`, `context: Boolean`, `editable: Boolean`; `TimelineResponse` gains `truncated: Boolean`; `TimelineResponse.of` maps all four.

Every existing `timeline.load(teamId = …, projectId = …)` in `TimelineServiceTest` becomes `timeline.load(admin, teamId = …, projectId = …)`.

One existing assertion changes meaning and must be rewritten rather than deleted: *"criticality is computed over the whole component, not the visible scope"* asserts `view.dependencies.single().outOfScope`. That edge's far end is now drawn as context, so the stub is gone. Keep the test — its subject is the criticality anchoring, which still holds — and change the second assertion to `assertEquals(false, view.dependencies.single().outOfScope)` with a comment saying the closure is now returned rather than discarded.

- [ ] **Step 6: Run the API suite**

Run: `cd apps/api && ./gradlew test`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add apps/api/src/main/kotlin/dev/kanso apps/api/src/test/kotlin/dev/kanso
git commit -m "feat(api): the timeline draws who else is in the room

Shared projects and the dependency closure come back as context rows carrying
their owner's key and editable=false, computed by the same rule the mutations
use. Most outOfScope stubs disappear with them."
```

---

### Task 8: Read-only rendering — context rows and bars that are not yours

**Files:**
- Modify: `apps/web/src/lib/api.ts` (`TimelineTicket`, `TimelineView`)
- Modify: `apps/web/src/components/timeline/row.tsx`, `view.tsx`
- Modify: `apps/web/src/app/timeline.css`

**Interfaces:**
- Consumes: `teamKey`, `context`, `editable`, `truncated` from Task 7.
- Produces: `TimelineRow` renders a bar with `drag`/`link` only when `ticket.editable && !ticket.context && canPlan`. Task 9 supplies `canPlan`.

- [ ] **Step 1: Extend the client types**

```ts
export type TimelineTicket = {
  …
  /** Whose ticket this is, printed before the identifier on a context row. */
  teamKey: string;
  /** Drawn for reading: outside the scope, not selectable, never draggable. */
  context: boolean;
  /** The server's answer to "may this viewer move it". Never re-derived here. */
  editable: boolean;
};

export type TimelineView = {
  …
  /** The scope hit `SCOPE_LIMIT`, so bars are missing and the chart has to say so. */
  truncated: boolean;
};
```

- [ ] **Step 2: Make the row obey**

In `row.tsx`'s ticket branch, replace the unconditional `drag` and `link` props:

```tsx
  // Three reasons a bar cannot be moved, and any one of them is enough: the whole
  // chart is read-only, the row is context, or the ticket belongs to a team the
  // viewer is not in. All three end in the same place — no `drag` prop — which is
  // what a project bar has always done.
  const movable = control.canPlan && !ticket.context && ticket.editable;
```

then `drag={movable ? { … } : undefined}` and `link={movable ? { … } : undefined}`.

Selection follows the same rule for context only:

```tsx
      selected={!ticket.context && ticket.id === control.selectedId}
      onSelect={ticket.context ? undefined : () => control.onSelect(ticket.id)}
```

A context row is **not** selectable because `page.tsx` builds its cursor list from the scoped tickets query, where a context ticket does not exist: selecting one would set `selectedId` and the cursor-keeping effect would bounce straight back to `visible[0]`. A ticket that is in scope but not editable stays selectable — it is in that list, and the keys that act on it are inert for their own reasons.

The name cell prefixes the owning team on a context row:

```tsx
  const name =
    row.kind === "project"
      ? row.project.name
      : row.ticket.context
        ? `${row.ticket.teamKey} · ${row.ticket.identifier}`
        : row.ticket.identifier;
```

and the row element carries `data-context={row.kind === "ticket" && row.ticket.context ? "" : undefined}`.

- [ ] **Step 3: Group the context rows and draw the banner**

In `view.tsx`, the `rows` memo puts context tickets under their project as it already does, and any context ticket with no project row goes into the existing orphan tail. Add the banner above the canvas:

```tsx
      {view?.truncated && (
        <div className="tl-truncated" role="status">
          This view hit its limit — some bars are not drawn. Narrow the scope to see them all.
        </div>
      )}
```

A Gantt missing bars without saying so is a plan that lies, which is worse than a message.

- [ ] **Step 4: Style them**

```css
/* Muted and unmistakably not yours, without becoming unreadable: a context row is
   there to be understood, not merely noticed. */
.tl-row[data-context] .tl-name {
  color: var(--text-faint);
  font-style: italic;
}

.tl-row[data-context] .tl-bar {
  opacity: 0.55;
}

.tl-truncated {
  padding: 4px 8px;
  font-size: 11px;
  color: var(--warn, #d08a1e);
}
```

- [ ] **Step 5: Typecheck and run the web suite**

Run: `cd apps/web && npm run typecheck && npm test`
Expected: PASS. `control.canPlan` does not exist yet — add it to `RowControl` as `canPlan: boolean` in this task and pass `true` from `view.tsx` for now; Task 9 computes it.

- [ ] **Step 6: Commit**

```bash
git add apps/web/src/lib/api.ts apps/web/src/components/timeline apps/web/src/app/timeline.css
git commit -m "feat(web): a bar that is not yours is drawn and not moved"
```

---

### Task 9: Read-only at scope `all`, mouse and keyboard

**Files:**
- Modify: `apps/web/src/lib/actions.ts`
- Modify: `apps/web/src/components/timeline/view.tsx`, `tray.tsx`
- Modify: `apps/web/src/app/page.tsx` (the topbar strip)
- Test: `apps/web/src/lib/actions.test.ts`

**Interfaces:**
- Consumes: `ActionContext.scope` (already present).
- Produces: `canPlan(ctx: ActionContext): boolean`, exported from `actions.ts` — `ctx.scope.kind !== "all"`. `view.tsx` reads the same predicate from the store.

- [ ] **Step 1: Write the failing tests**

Append to `apps/web/src/lib/actions.test.ts`, following the context-builder the file already uses:

```ts
const WRITING_CHART_ACTIONS = [
  "timeline.shiftEarlier",
  "timeline.shiftLater",
  "timeline.shrinkEnd",
  "timeline.growEnd",
  "timeline.schedule",
  "timeline.unschedule",
  "timeline.link",
  "timeline.unlink",
];

const VIEWPORT_CHART_ACTIONS = ["timeline.zoomOut", "timeline.zoomIn", "timeline.today"];

describe("the global timeline is read-only", () => {
  it("refuses every writing chart action in scope all", () => {
    const ctx = ctxFor({ view: "timeline", scope: { kind: "all" } });
    for (const id of WRITING_CHART_ACTIONS) {
      expect(byId(id).when(ctx), `${id} must be inert on the global chart`).toBe(false);
    }
  });

  it("keeps the viewport actions live, because navigating is not planning", () => {
    const ctx = ctxFor({ view: "timeline", scope: { kind: "all" } });
    for (const id of VIEWPORT_CHART_ACTIONS) {
      expect(byId(id).when(ctx), `${id} moves the viewport, not the plan`).toBe(true);
    }
  });

  it("allows the writing actions again inside a team", () => {
    const ctx = ctxFor({ view: "timeline", scope: { kind: "team", id: "t1" } });
    expect(byId("timeline.shiftLater").when(ctx)).toBe(true);
  });
});
```

Read the existing tests in that file first and reuse its own context helper and lookup rather than the placeholder names `ctxFor`/`byId` — match whatever it already defines. The scoped-team case needs a selected, scheduled ticket in the context, and the `unlink` case needs one edge in `ctx.dependencies`; build them the way the file's existing timeline tests do.

- [ ] **Step 2: Run and watch them fail**

Run: `cd apps/web && npm test`
Expected: FAIL — the actions are live in scope `all`.

- [ ] **Step 3: Add the guard**

In `actions.ts`, beside `onTimeline`:

```ts
/**
 * Whether this scope is one you plan in.
 *
 * The global chart is read-only: it crosses every team, and a drag there is as often a
 * slip as an intention. Planning happens where a scope has been chosen. This is the
 * keyboard half of that rule — a view that refuses the mouse and accepts `h` is not
 * read-only, it is a trap with a discoverability problem.
 */
export const canPlan = (ctx: ActionContext) => ctx.scope.kind !== "all";
```

Add `canPlan(ctx) &&` to the `when` of each of the eight writing chart actions. Leave `timeline.zoomOut`, `timeline.zoomIn` and `timeline.today` alone.

- [ ] **Step 4: Do the same for the pointer**

In `view.tsx`:

```tsx
  const scope = useUi((state) => state.scope);
  const canPlan = scope.kind !== "all";
```

Pass `canPlan` into the `control` memo (Task 8 already reads it) and into `trayControl`, whose `onDrop` returns early when it is false. Render the strip in `page.tsx`, under the topbar and above the chart:

```tsx
        {view === "timeline" && scope.kind === "all" && (
          <div className="topbar-note" role="status">
            Read-only — open a team or a project to plan.
          </div>
        )}
```

A feature indistinguishable from a bug is a bug.

- [ ] **Step 5: Run everything on the web side**

Run: `cd apps/web && npm run typecheck && npm test && npm run lint`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add apps/web/src/lib/actions.ts apps/web/src/lib/actions.test.ts \
        apps/web/src/components/timeline apps/web/src/app/page.tsx
git commit -m "feat(web): the global timeline is read-only, keyboard included

Zoom and today stay live: they move the viewport, not the plan."
```

---

### Task 10: Scenario 13 — two teams, one chart

**Files:**
- Create: `e2e/13-scoped-timeline.spec.ts`
- Modify: `e2e/support.ts` (a `seedMember` helper, if none exists)

**Interfaces:**
- Consumes: everything above.
- Produces: nothing later depends on it.

- [ ] **Step 1: Add the seeding helper**

In `support.ts`, beside `seedTeam`:

```ts
/** Puts a user in a team. Admin-only on the server, so call it with an ADMIN context. */
export async function seedMember(
  api: APIRequestContext,
  teamId: string,
  userId: string,
): Promise<void> {
  const response = await api.post(`${API_URL}/api/teams/${teamId}/members`, {
    data: { userId, role: "member" },
  });
  if (!response.ok()) throw new Error(`seedMember: ${response.status()} ${await response.text()}`);
}
```

You will need the `MEMBER` user's id. `GET /api/me` as that identity returns it; add a `userIdOf(email)` helper if `support.ts` has none.

- [ ] **Step 2: Write the scenario**

```ts
import { expect, test } from "@playwright/test";
import {
  ADMIN, MEMBER, apiAs, floatingDay, openAs, seedInstance, seedMember,
  seedProject, seedTeam, seedTicket, unique, uniqueKey, userIdOf,
} from "./support";

test.beforeAll(seedInstance);

/**
 * Scenario 13. Two teams sharing one project, seen from inside one of them.
 *
 * The three claims that matter, and each fails differently: a foreign bar is drawn
 * (the widened scope works), it has no grip (the read-only affordance works), and an
 * overlap the cascade never examines is reported (the warning works).
 */
test("scenario 13 — a team sees the other team's work, cannot move it, and is warned about its own overlap", async ({
  browser,
}) => {
  const api = await apiAs(ADMIN);
  const mine = await seedTeam(api, { name: unique("Mine"), key: uniqueKey() });
  const theirs = await seedTeam(api, { name: unique("Theirs"), key: uniqueKey() });
  const project = await seedProject(api, { name: unique("Shared"), teamId: mine.id });

  const first = await seedTicket(api, {
    teamId: mine.id, projectId: project.id, title: unique("First"),
    start: floatingDay("2026-09-01"), due: floatingDay("2026-09-10"),
  });
  const second = await seedTicket(api, {
    teamId: mine.id, projectId: project.id, title: unique("Second"),
    start: floatingDay("2026-09-20"), due: floatingDay("2026-09-25"),
  });
  const foreign = await seedTicket(api, {
    teamId: theirs.id, projectId: project.id, title: unique("Foreign"),
    start: floatingDay("2026-09-05"), due: floatingDay("2026-09-15"),
  });

  await api.post(`${API_URL}/api/tickets/${second.id}/dependencies`, {
    data: { predecessorId: first.id },
  });
  // Backwards, under its own predecessor. The cascade never examines this edge —
  // its descent only considers a node one of whose predecessors moved.
  await api.patch(`${API_URL}/api/tickets/${second.id}`, {
    data: { start: floatingDay("2026-09-05"), due: floatingDay("2026-09-08") },
  });

  await seedMember(api, mine.id, await userIdOf(api, MEMBER));
  await seedMember(api, theirs.id, await userIdOf(api, ADMIN));
  await api.dispose();

  const page = await openAs(browser, MEMBER);
  await page.getByRole("button", { name: mine.name }).click();
  await page.getByRole("button", { name: "Timeline" }).click();

  const foreignBar = page.getByRole("button", { name: new RegExp(`^${theirs.key}-`) });
  await expect(foreignBar).toBeVisible();
  await expect(foreignBar.locator(".tl-handle")).toHaveCount(0);

  await expect(page.getByRole("img", { name: /starts before .* ends/ })).toBeVisible();

  await page.close();
});
```

Adjust every locator to what the components actually render — the accessible name of a ticket bar is `` `${identifier}: ${title} — ${statusLabel}` `` after Task 3, and the ⚠ is a `role="img"` whose `aria-label` is `overlapNotice`'s return value.

- [ ] **Step 3: Run it**

Run, from the repo root with the stack up: `npx playwright test e2e/13-scoped-timeline.spec.ts`
Expected: PASS.

If the foreign bar shows handles, check `KANSO_WEB_ORIGIN` before checking the code: without it CORS makes every visitor render as a member, and the assertion would be green for the wrong reason on the previous line and red here.

- [ ] **Step 4: Run the whole end-to-end suite**

Run: `npx playwright test`
Expected: PASS. Any failure naming a bar is the Task 3 rename; any 403 in a scenario that used to pass is a seeding gap — that scenario's actor needs a `seedMember` call, or its team needs to stay unclaimed.

- [ ] **Step 5: Commit**

```bash
git add e2e/13-scoped-timeline.spec.ts e2e/support.ts
git commit -m "test(e2e): a team reads the room, cannot move it, and is told when its own plan slips"
```

---

### Task 11: Write down what this cost

**Files:**
- Modify: `docs/follow-ups.md`
- Modify: `docs/architecture.md`, `docs/architecture.fr.md`

**Interfaces:** none.

- [ ] **Step 1: Supersede the stale note**

In `docs/follow-ups.md`, the entry *"The timeline computes violated edges independently of the cascade"* says the two agree because both encode the same rule. They no longer do, deliberately. Rewrite it to say so, and to say why: the cascade reports what a request could not repair; the timeline reports what is broken now. Mark it closed rather than deleting it — the reasoning is the point.

- [ ] **Step 2: Record the two new costs**

Add, under a new heading for this branch:

- **The status pill covers its own bar at month zoom.** A column is three pixels, so a one-day ticket's bar is entirely under its 8px pill and its criticality colour disappears. The red outline of `data-state="late"` bleeds past it and saves the worst case; "critical but on time" does not survive. Accepted when the pill was chosen over a shared fill, on the grounds that colour on the bar already means criticality.
- **A context row cannot be opened.** There is no detail panel for a ticket you do not own: the cursor list is the scoped tickets query, and putting a foreign ticket in it would reintroduce the selection bounce closed on the timeline branch. Its name, dates and status are in the tooltip and the accessible name.

- [ ] **Step 3: Update the architecture notes**

`docs/architecture.md` has an **Auth** section describing `instance_role` against `team_members.role` and saying the latter is "about belonging to a team". That is no longer the whole story — belonging now decides who may move a ticket. Add a short paragraph under Auth stating the three-part rule, the empty-team clause and its migration reason, and the downward-only inheritance. Mirror it in `architecture.fr.md`.

- [ ] **Step 4: Commit**

```bash
git add docs/follow-ups.md docs/architecture.md docs/architecture.fr.md
git commit -m "docs: membership became a permission, and two costs worth knowing"
```

---

## Self-Review

**Spec coverage.** Every section maps to a task: the authorization rule → 4; the call sites and the two-sided `patch` → 5; the members surface → 6; scope resolution, response shape and the cap → 7; the three read-only levels → 8 and 9; the overlap warning → 1 and 2; the status pill → 3; the tests section → distributed across 1–10; the mirror section needs no work, as the spec states. The spec's shipping order maps to the Stage 1 / Stage 2 split.

**Placeholders.** None. Three places name a file to read before writing (`pills.tsx` for the colour maps, `people-section.tsx` for the member row markup, `actions.test.ts` for its own context helper) rather than inventing an interface that may not match — that is a pointer to existing code, not a deferred decision.

**Type consistency.** `TimelineEdge.overlap` (Task 1) is read as `TimelineDependency.overlap` (Task 2) and asserted in Task 10. `TicketAccess.editableTeams` (Task 4) is consumed in Task 7. `TimelineTicket.editable`/`context`/`teamKey` (Task 7) are consumed in Task 8. `RowControl.canPlan` is introduced in Task 8 with a hardcoded `true` and computed in Task 9 — the one deliberate two-step, called out in Task 8 Step 5 so a reviewer does not read it as an omission. The bar's accessible name changes once, in Task 3, and every later locator uses the new shape.

**One gap found and closed while reviewing:** Task 5 originally left `NotionPoller` broken, since it calls `TicketService.patch` with no actor. Step 5 now specifies `patchUnchecked` and the `applyPatch` extraction.
