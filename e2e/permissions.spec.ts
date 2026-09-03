import { expect, test } from "@playwright/test";
import {
  ADMIN,
  MEMBER,
  apiAs,
  openAs,
  openRowMenu,
  seedInstance,
  seedProject,
  seedTeam,
  unique,
  uniqueKey,
} from "./support";

test.beforeAll(seedInstance);

/**
 * Scenario 3. Two identities in a single test, which `dev` mode makes possible with
 * no OAuth to simulate: the same page, two headers.
 *
 * Ruling from the human partner, against the first version of this test: a member
 * DOES see a team row's `⋯` — the path to "New project" runs through it, and creating
 * a project is work open to every member (`actions.ts`, `project.create`'s `when` is
 * deliberately ungated on role). What a member must never see is the *shape of the
 * organisation* changing under them: the Teams header `+`, and every team-management
 * entry that would otherwise share that same menu (create, rename, archive, delete —
 * "New team" on a team's own row opens the dialog with that team pre-filled as parent,
 * same as the old dedicated "New sub-team" action did). So the guard worth having here
 * is not "the member sees no team `⋯`" but "the member's team `⋯`, when it does show,
 * contains exactly the one item it is entitled to — no more." Asserting the full item
 * list rather than just the presence of one is what actually catches a management
 * action leaking back into a member's menu later.
 */
test("scenario 3 — a member's team menu holds only project creation, an admin's holds every team action", async ({
  browser,
}) => {
  const api = await apiAs(ADMIN);
  const team = await seedTeam(api, { name: unique("Shape"), key: uniqueKey() });
  const project = await seedProject(api, { name: unique("Shared"), teamId: team.id });
  await api.dispose();

  const admin = await openAs(browser, ADMIN);
  const member = await openAs(browser, MEMBER);

  // The admin: the Teams + …
  await expect(admin.getByRole("button", { name: "New team", exact: true })).toBeVisible();
  // … and every team-management action on the row's ⋯, in full.
  //
  // `Favourite s` heads both lists and is not a team action: it writes one row in one
  // person's sidebar, which is why it is ungated on role like `project.create`. The
  // keycap is part of the text deliberately — `menu.tsx` separates label from hint with a
  // real space so the accessible name reads "Favourite s" — and both lists are still
  // asserted whole, which is the property that catches a management action leaking into
  // the member's menu.
  const adminMenu = await openRowMenu(admin, team.name);
  await expect(adminMenu.getByRole("menuitem")).toHaveText([
    "Favourite s",
    "New project",
    "New team",
    "Rename team",
    "Archive team",
    "Delete team",
  ]);
  await admin.keyboard.press("Escape");

  // The member: no Teams +. The shape of the organisation is an admin decision.
  await expect(member.getByRole("button", { name: team.name, exact: true })).toBeVisible();
  await expect(member.getByRole("button", { name: "New team", exact: true })).toHaveCount(0);
  // The row's ⋯ DOES show — but holds exactly the one entry a member is entitled to.
  const memberMenu = await openRowMenu(member, team.name);
  await expect(memberMenu.getByRole("menuitem")).toHaveText(["Favourite s", "New project"]);
  await member.keyboard.press("Escape");

  // The daily work stays open to both: the Projects + and a project's own ⋯.
  for (const page of [admin, member]) {
    await expect(page.getByRole("button", { name: "New project", exact: true })).toBeVisible();
    await expect(
      page.getByRole("button", { name: `Actions for ${project.name}`, exact: true }),
    ).toHaveCount(1);
  }
});
