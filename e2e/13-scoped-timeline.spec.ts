import { expect, test } from "@playwright/test";
import {
  ADMIN,
  MEMBER,
  apiAs,
  floatingDay,
  openAs,
  seedInstance,
  seedMember,
  seedProject,
  seedTeam,
  seedTicket,
  unique,
  uniqueKey,
  userIdOf,
  viewButton,
} from "./support";

test.beforeAll(seedInstance);

/** So an identifier or team key can be dropped into a `RegExp` without its characters read as one. */
const escapeRe = (value: string) => value.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");

/**
 * Scenario 13. Two teams sharing one project, seen from inside one of them.
 *
 * The three claims that matter, and each fails differently: a foreign bar is drawn
 * (the widened scope works), it has no grip (the read-only affordance works), and an
 * overlap the cascade never examines is reported (the warning works). Two cheaper
 * assertions ride along afterwards because the fixture that proves the first three
 * already has everything they need: a bar that is in scope but not editable, and a
 * chart with no scope at all.
 *
 * The project the two teams share has to be team-less. `TicketService.create` refuses
 * a ticket whose project belongs to a *different* team, and the codebase's rule for
 * that refusal is "a team-less project is transverse and belongs everywhere" — the
 * only shape that lets both teams legally post a ticket into the same project, and
 * therefore the only shape that exercises the feature at all.
 *
 * Both teams are given a member on purpose, and not with the pair of people the
 * assertions below actually check. `TicketAccess`'s "empty team is open" clause exists
 * so a fresh instance with no memberships yet does not lock every board behind a 403 —
 * but it also means an unclaimed team is editable by anyone. Leaving `theirs` unclaimed
 * would make the no-grip assertion pass because nobody had claimed it, not because the
 * viewer belongs to a different team, which is the one thing this scenario is for.
 */
test("scenario 13 — a team sees the other team's work, cannot move it, and is warned about its own overlap", async ({
  browser,
}) => {
  const api = await apiAs(ADMIN);
  const mine = await seedTeam(api, { name: unique("Mine"), key: uniqueKey() });
  const theirs = await seedTeam(api, { name: unique("Theirs"), key: uniqueKey() });
  const project = await seedProject(api, { name: unique("Shared") });

  const first = await seedTicket(api, {
    teamId: mine.id,
    projectId: project.id,
    title: unique("First"),
    start: "2026-09-01",
    due: "2026-09-10",
  });
  const second = await seedTicket(api, {
    teamId: mine.id,
    projectId: project.id,
    title: unique("Second"),
    start: "2026-09-20",
    due: "2026-09-25",
  });
  const foreign = await seedTicket(api, {
    teamId: theirs.id,
    projectId: project.id,
    title: unique("Foreign"),
    start: "2026-09-05",
    due: "2026-09-15",
  });

  const linked = await api.post(`/api/tickets/${second.id}/dependencies`, {
    data: { predecessorId: first.id },
  });
  expect(linked.status(), "the dependency was refused").toBe(201);
  // Backwards, under its own predecessor and not done. The cascade's descent only
  // considers a node one of whose predecessors just moved — dragging the successor
  // itself is never examined — which is why this scenario has to seed the overlap by
  // hand rather than produce it with a gesture the cascade would repair on the spot.
  const shifted = await api.patch(`/api/tickets/${second.id}`, {
    data: { start: floatingDay("2026-09-05"), due: floatingDay("2026-09-08") },
  });
  expect(shifted.ok(), "the backwards shift was refused").toBeTruthy();

  await seedMember(api, mine.id, await userIdOf(MEMBER));
  await seedMember(api, theirs.id, await userIdOf(ADMIN));
  await api.dispose();

  const page = await openAs(browser, MEMBER);
  await page.getByRole("button", { name: mine.name, exact: true }).click();
  await viewButton(page, "Timeline").click();

  // A foreign bar, drawn because the shared project widened the scope, named after the
  // team that owns it rather than the one the viewer opened — identifiers already carry
  // that key, so no other locator is needed to tell it apart from `mine`'s own bars.
  const foreignBar = page.getByRole("button", { name: new RegExp(`^${escapeRe(theirs.key)}-`) });
  await expect(foreignBar).toBeVisible();
  await expect(foreignBar.locator(".tl-handle")).toHaveCount(0);

  // The ⚠ on `second`'s row. Its accessible name is `overlapNotice`'s return value, and
  // with exactly one broken dependency and `first` in scope, that sentence names it —
  // the prefix match only avoids re-encoding the sentence rather than testing it.
  await expect(page.getByRole("img", { name: /starts before .* ends/ })).toBeVisible();

  // --- extra: the mouse-selection fix, on the shape it actually needs ---------------
  //
  // The previous task fixed a defect where a mouse could not select an in-scope,
  // non-editable bar. `foreignBar` above is not that shape — it is a *context* row, and
  // `canSelectTicket` closes off the cursor from a context row entirely, on purpose. The
  // shape the fix was for is a bar that is in scope but that this viewer may not move,
  // which only exists here from inside `theirs`' own timeline: `foreign` is `theirs`'
  // own ticket there, not context, and the member still holds no membership in `theirs`.
  await page.getByRole("button", { name: theirs.name, exact: true }).click();
  const foreignOwnBar = page.getByRole("button", {
    name: new RegExp(`^${escapeRe(foreign.identifier)}: `),
  });
  await expect(foreignOwnBar).toBeVisible();
  await expect(foreignOwnBar.locator(".tl-handle")).toHaveCount(0);
  await foreignOwnBar.click();
  await expect(foreignOwnBar).toHaveAttribute("aria-current", "true");

  // --- extra: the read-only global chart --------------------------------------------
  //
  // "All tickets" carries no team and no project, so `canPlan` is false for every row on
  // it regardless of what `editable` says — the other half of this feature, covered by
  // one bar that would otherwise never be exercised by this scenario.
  await page.getByRole("button", { name: "All tickets", exact: true }).click();
  await expect(page.getByRole("status")).toHaveText("Read-only — open a team or a project to plan.");
  const firstBar = page.getByRole("button", { name: new RegExp(`^${escapeRe(first.identifier)}: `) });
  await expect(firstBar).toBeVisible();
  await expect(firstBar.locator(".tl-handle")).toHaveCount(0);

  await page.close();
});
