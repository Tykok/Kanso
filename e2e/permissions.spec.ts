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
 * DOES see a team row's `⋯` — the path to "New project in this team" runs through it,
 * and creating a project is work open to every member (`actions.ts:313`,
 * `project.createInTeam`'s `when` is deliberately ungated on role). What a member must
 * never see is the *shape of the organisation* changing under them: the Teams header
 * `+`, and every team-management entry that would otherwise share that same menu
 * (rename, sub-team, archive, delete). So the guard worth having here is not "the
 * member sees no team `⋯`" but "the member's team `⋯`, when it does show, contains
 * exactly the one item it is entitled to — no more." Asserting the full item list
 * rather than just the presence of one is what actually catches a management action
 * leaking back into a member's menu later.
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
  const adminMenu = await openRowMenu(admin, team.name);
  await expect(adminMenu.getByRole("menuitem")).toHaveText([
    "New project in this team",
    "New sub-team",
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
  await expect(memberMenu.getByRole("menuitem")).toHaveText(["New project in this team"]);
  await member.keyboard.press("Escape");

  // The daily work stays open to both: the Projects + and a project's own ⋯.
  for (const page of [admin, member]) {
    await expect(page.getByRole("button", { name: "New project", exact: true })).toBeVisible();
    await expect(
      page.getByRole("button", { name: `Actions for ${project.name}`, exact: true }),
    ).toHaveCount(1);
  }
});
