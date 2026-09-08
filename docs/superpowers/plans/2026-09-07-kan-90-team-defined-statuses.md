# A team defines its own statuses — KAN-90

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans. This plan is the measurement of a refactor that was started, compiled, and deliberately not landed — read "What was tried" before writing a line.

**Goal:** A team adds a seventh status, or keeps four, and everything that reasons about work goes on being right.

**Architecture:** `Ticket.status` stops being `DefaultStatus` and becomes the key of one of its team's statuses. What a status *means* moves to a collaborator that reads the team's catalogue — `StatusCategories` — because a property on the ticket could only ever have answered out of the six, which is the silent wrong answer this whole change exists to prevent.

**Spec:** `docs/superpowers/specs/2026-09-07-team-statuses-design.md` — the `KAN-90` sections.

**Blocked by:** nothing. `KAN-28` landed the catalogue, the trigger, the composite foreign key and the words; `KAN-91` landed the Notion mirror's per-team resolution. This is the third and last part.

---

## What was tried, and why it is not merged

The type change was made, compiled, and reverted in one sitting. Nothing here is guessed:
the numbers below are what the compiler said.

- **`Ticket.status: DefaultStatus` → `String` produces 44 errors in `apps/api/src/main`.**
  Eight are `.wire` on a value that is already a string — mechanical. The rest are the
  work: `.category` on a ticket, comparisons against an enum constant, and service
  signatures that carried the enum through.
- **Down to 36 after `CycleService`, `TicketService`, the DTOs and the MCP printers.**
  Fixing those four surfaced the second wave: `TicketController`, `BulkEditService`,
  `TriageService`, `TimelineService`, `DocBlockService`, `GithubWebhookService`,
  `RequestSiphon`, `TicketImport`, `MyStatsService`, `ProgressService`,
  `SubTicketService`, `ScheduleService`, `VelocityService`, `WorkloadService`.
- **134 test sites** write `status = DefaultStatus.X`, across 78 files. Mechanical, but it
  is 78 files.
- **100 references to `TicketStatus`** in `apps/web/src`. The TypeScript union stops being
  the vocabulary the same way the enum does, and that ripples the same distance.

It was reverted because a half-migrated domain is unmergeable and a rushed one is worse
than none: about ten of those sites are product decisions rather than mechanics, and each
deserves to be made deliberately. Two were already made and are recorded below.

---

## Decisions taken while executing, and the order they changed

**Task 3 runs before Task 2.** The plan had the door before the type, and that order has a
window in it: `remove` moves tickets to a status it is given the key of, so a team that has
added *Devis* and then removes `done` naming *Devis* as the destination writes
`status = 'devis'` onto real rows — and `Mappers.kt` calls `DefaultStatus.from` on every
read of them. That is a 500 on data somebody owns, not a red test. The type change has to
land first, so the order is **1 → 3 → 2 → 4 → 5 → 6**.

**A category filter in SQL is a join, not a list of keys.** Three of the seven
`DefaultStatus.entries.filter { … }` lists feed queries whose scope spans teams —
`WorkloadService.OPEN_STATUSES` into `tickets.search(teamIds = …)`,
`PublicRoadmapService.ROADMAP_STATUSES` and `NOT_STARTED_STATUSES` into `findPublished`,
and `TicketRepository.MOVED_ALONG_STATUSES` into a probe with no team scope at all. No
single team's keys can serve any of them, so `keysMeaning` is not the answer there:
`search` and `findPublished` take `categories: List<StatusCategory>` and the repository
joins `team_statuses` on `(team_id, status)`. Rejected: a denormalised `tickets.category`
column, because a second copy of the mapping on disk is what `DefaultStatus.category`'s
docstring exists to refuse; and expanding every team's keys in Kotlin, because the
unscoped probe would have to read all of `team_statuses` to ask whether anything moved.

**The three client bars follow `KAN-28`'s rule rather than a third one.** `burndown.ts`,
`progress-charts.tsx` and `workload-view.tsx` all segment a `Record<string, number>` by
iterating a hardcoded order, so a seventh status vanishes from all three without a sound.
A scope of one team reads that team's order out of `Team.statuses`, which already carries
`position`; a scope spanning teams reads `CATEGORY_ORDER`. This is exactly what `KAN-28`
decided for the grouped lists, so there is no third rule to remember — and it retires
`StatusOrder`'s argument that these two client orders must never come from the server,
which held only while the vocabulary was closed.

---

## Global Constraints

- **The wire keeps its shape.** `TicketResponse.status` stays a string. `TeamResponse.statuses` already carries the catalogue.
- **The category is the only thing anything reasons about.** No service may read a status literal to decide what work means. `StatusCategories` is the one place that answers.
- **A team keeps at least one status**, and removing one names where its tickets go — `KAN-4`'s disposition shape.
- **A ticket moving between teams rebases its status** by category. `tickets_status_fk` makes an unrebased move impossible, and `KAN-9` made the move ordinary.
- **Tests:** `class …Test : PostgresTest()`, `@Transactional`, `kotlin.test`. Web logic in `.ts`, components in `.tsx`.

---

## Task 1: What a status means, in one place

**Files:**
- Create: `apps/api/src/main/kotlin/dev/kanso/service/StatusCategories.kt`
- Test: `apps/api/src/test/kotlin/dev/kanso/service/StatusCategoriesTest.kt`

**Interfaces:**
- Produces: `StatusCategories.of(tickets): Categories` (one query for every team in play), `.forTeam(teamId): Map<String, StatusCategory>`, `.categoryOf(teamId, status): StatusCategory`, `.keysMeaning(teamId, category): List<String>`; `class Categories` with `operator fun get(ticket): StatusCategory`.

This one was written and its tests passed. Reproduce it as it stood:

- `of` reads `TeamStatusRepository.forTeams` once and answers a value, not a service — the
  callers are burndowns and workload charts holding hundreds of rows across a handful of
  teams, and a lookup per ticket is an N+1 on every one of those screens.
- `Categories[ticket]` reads at the call site where `ticket.status.category` used to.
- The fallback chain is the team's catalogue, then `DefaultStatus` for a draft with no team
  to ask, then `UNSTARTED` — unreachable rather than lenient, so a row read through a path
  nobody has written yet is counted as unstarted work instead of crashing a burndown.
- `keysMeaning` is what replaces the seven `DefaultStatus.entries.filter { … }` lists that
  were rendered into `WHERE status IN (…)`.

- [ ] **Step 1: Write the failing test** — four cases, all of which passed: a status a team
  invented means what the team said; two teams' words for one category are both that
  category; a draft answers out of the six; `forTeam` answers a seven-entry map after an
  `add`.
- [ ] **Step 2: Run it.** `./gradlew test --tests 'dev.kanso.service.StatusCategoriesTest'` — expect `Unresolved reference 'StatusCategories'`.
- [ ] **Step 3: Write the class** as described above.
- [ ] **Step 4: Run it.** Expect four passes.
- [ ] **Step 5: Commit.** `feat(statuses): what a status means, asked in one place`

---

## Task 2: Add and remove, in the service

**Files:**
- Modify: `apps/api/src/main/kotlin/dev/kanso/service/TeamStatusService.kt`
- Modify: `apps/api/src/main/kotlin/dev/kanso/api/TeamStatusController.kt`
- Modify: `apps/api/src/main/kotlin/dev/kanso/repo/TicketRepository.kt` — `withStatus(teamId, key)`, `moveStatus(id, key)`
- Test: `apps/api/src/test/kotlin/dev/kanso/service/TeamStatusServiceTest.kt`

This was written too. Two things it found:

- **`remove` must not go through `TicketService`.** `TicketService` is about to depend on
  the catalogue to validate a status, and two services needing each other is a Spring
  context that will not start. `remove` takes `TicketRepository` and `ActivityService`
  instead, which is all it needs: move the rows, write a `status_changed` line each.
- **`add` asks for the category and never lets it change afterwards.** Moving a status
  between categories moves what the burndown counts, so it is decided once, where somebody
  is deciding what the word *means* rather than what it says.

- [ ] Steps: the failing tests first — a duplicate label refused by name, the last status
  refused, a removal that names no destination refused, a removal that moves its tickets
  and writes the activity. Then the service, then `POST` and `DELETE` on the controller.

---

## Task 3: The type change, and the ten decisions in it

**Files:** `domain/Model.kt`, `db/Mappers.kt`, `repo/TicketRepository.kt`, and the twenty files the compiler names.

Two decisions were already taken while this compiled, and they are the pattern for the
rest — **read the category, never the word**:

1. **`CycleReport.byStatus` is keyed by the team's keys** (`Map<String, Int>`), counted
   from the tickets the cycle actually holds rather than from a fixed vocabulary. A map
   that could only hold the six would have dropped a team's seventh status out of the bar
   silently.
2. **"Delivered points" is the `COMPLETED` category**, not `DefaultStatus.DONE`. A team
   whose finished status is called `Livré` delivers points too.

The ones still to make, each with the file that asks it:

- **`GithubWebhookService`** — a pull request opening moves a ticket to `in_review`. What
  does it do for a team that has no `in_review`? (Suggested: the team's first `STARTED`
  status; a team with none leaves the ticket alone rather than inventing a move.)
- **`MyStatsService`, `ProgressService`, `SubTicketService`, `ScheduleService`,
  `VelocityService`, `WorkloadService`** — six category readings, all mechanical once
  `StatusCategories` is injected, but each needs its scope decided: one team's map, or a
  `Categories` over the rows it holds.
- **`TriageService`, `BulkEditService`, `TicketController`, `DocBlockService`,
  `RequestSiphon`, `TicketImport`** — all write a status. Each has to validate against the
  destination team's catalogue, which is a new refusal sentence per door.
- **The four MCP tools' `STATUSES`** — they advertise `DefaultStatus.entries` as the
  vocabulary. An agent working in a team should be told that team's words; the tool schema
  is static, so this is either a per-call description or a refusal that names them.
- **`PublicRoadmapService`** — groups by status across teams. `KAN-28` decided that
  question for the app's own lists (group by category); the roadmap should match.

- [ ] Steps: change the type, then work the compiler's list file by file, running
  `./gradlew compileKotlin` after each. Then the 134 test sites — `status = DefaultStatus.X`
  to `status = "x"` is a scripted rewrite, but read the diff: a few tests *assert* on the
  enum and mean it.

---

## Task 4: The rebase on a team move

**Files:** `domain/TeamStatus.kt` (`rebase`), `service/TicketService.kt`, test `service/TicketStatusRebaseTest.kt`

The rule, from the spec: the same key if the destination has it, then the destination's
first status of the same category, then its first status — first by `position`. The move
writes a `status_changed` row, because the ticket did change status and a burndown that saw
the number move with no line behind it is a burndown nobody trusts.

- [ ] Steps: four failing tests (same key, same category, first status, the activity line),
  then `rebase`, then the call in `patch` where `teamId` changes.

---

## Task 5: The web stops treating the six as the vocabulary

**Files:** `lib/api/core.ts`, `lib/statuses.ts`, `lib/status.ts`, `lib/status-order.ts`, `components/settings/statuses-section.tsx`, and the files the type checker names.

`TICKET_STATUSES` is a `const` tuple and `TicketStatus` is its union — 100 references. The
union has to widen to `string`, and the honest way is to keep `DEFAULT_STATUSES` as the
tuple it is (the draft vocabulary, and the composer's) while `TicketStatus` becomes a
string alias. `lib/statuses.ts` already resolves words and orders from `Team.statuses`, so
most screens need nothing.

The section gains **Add** (a label and a category) and **Remove** (naming a destination),
and its client-side duplicate check needs the same derivation as `statusKeyOf` — which
means a `statusKeyOf` in TypeScript, tested against the same table as the Kotlin one.

- [ ] Steps: widen the type, run `npx tsc --noEmit` and work the list; then the two
  controls, each with a component test for its refusal.

---

## Task 6: Scenario 29 grows a seventh status

**Files:** `e2e/29-team-statuses.spec.ts`

One test: a team adds *Devis* in `backlog`, files a ticket into it, sees it stacked in the
right bucket on the list, then removes it naming *Boîte* — and the ticket is there, with an
activity line saying so. Then the cross-team screen, where the headers are still the five
categories.

---

## Self-review notes

- **Spec coverage:** add/remove → Tasks 2 and 5; the domain change → Task 3; the rebase →
  Task 4; the category audit → Tasks 1 and 3; the web → Task 5; e2e → Task 6.
- **Already done and not to be redone:** the catalogue, the trigger, the composite foreign
  key, the words and their order (`KAN-28`); the Notion mirror's per-team resolution
  (`KAN-91`).
- **The one number to re-check:** none. This ticket adds no migration — `team_statuses` and
  `tickets_status_fk` already allow everything it needs.
