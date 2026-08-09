import { expect, test } from "@playwright/test";
import {
  ADMIN,
  apiAs,
  openAs,
  openRowMenu,
  seedInstance,
  seedProject,
  seedTeam,
  seedTicket,
  sidebarRow,
  ticketRow,
  unique,
  uniqueKey,
} from "./support";

test.beforeAll(seedInstance);

/**
 * Scenario 1. All by mouse, end to end: the only test that builds its stage through
 * the interface, because those paths are exactly what it checks.
 */
test("scenario 1 — a team, a sub-team, a project inside it, a team-less project and a ticket", async ({
  browser,
}) => {
  const page = await openAs(browser, ADMIN);

  const team = unique("Core");
  const subTeam = unique("Mobile");
  const teamProject = unique("Rework");
  const looseProject = unique("Audit");
  const ticket = unique("Fix the OAuth login");

  // A root team, from the + on the Teams header.
  await page.getByRole("button", { name: "New team", exact: true }).click();
  const teamDialog = page.getByRole("dialog");
  await expect(teamDialog).toBeVisible();
  await teamDialog.getByLabel("Name").fill(team);
  // Role-scoped: the "Parent team" select's accessible name folds in every option's
  // text, and a team seeded by another test (`Keys-…`, from the keyboard scenario)
  // makes a bare `getByLabel("Key")` ambiguous once the database has accumulated a
  // few runs.
  await teamDialog.getByRole("textbox", { name: "Key" }).fill(uniqueKey());
  await teamDialog.getByRole("button", { name: "Create" }).click();
  await expect(page.getByRole("button", { name: team, exact: true })).toBeVisible();

  // A sub-team, from the row's menu — "New team" on a team's own row, not the
  // retired "New sub-team" action folded into it (`dd55d86`).
  await openRowMenu(page, team);
  await page.getByRole("menuitem", { name: "New team", exact: true }).click();
  const childDialog = page.getByRole("dialog");
  // The parent is prefilled by the registry action, not retyped by hand.
  await expect(childDialog.getByLabel("Parent team")).not.toHaveValue("");
  await childDialog.getByLabel("Name").fill(subTeam);
  await childDialog.getByRole("textbox", { name: "Key" }).fill(uniqueKey());
  await childDialog.getByRole("button", { name: "Create" }).click();
  await expect(page.getByRole("button", { name: subTeam, exact: true })).toBeVisible();

  // The sub-team is drawn under its parent, not at the root.
  await expect(sidebarRow(page, subTeam)).toHaveClass(/nav-depth-1/);

  // A project inside the sub-team.
  await openRowMenu(page, subTeam);
  await page.getByRole("menuitem", { name: /project/i }).click();
  const projectDialog = page.getByRole("dialog");
  await expect(projectDialog.getByLabel("Team")).not.toHaveValue("");
  await projectDialog.getByLabel("Name").fill(teamProject);
  await projectDialog.getByRole("button", { name: "Create" }).click();
  await expect(page.getByRole("button", { name: teamProject, exact: true })).toBeVisible();

  // A team-less project, from the + on the Projects header.
  await page.getByRole("button", { name: "New project", exact: true }).click();
  const looseDialog = page.getByRole("dialog");
  await expect(looseDialog.getByLabel("Team")).toHaveValue("");
  await looseDialog.getByLabel("Name").fill(looseProject);
  await looseDialog.getByRole("button", { name: "Create" }).click();
  await expect(page.getByRole("button", { name: looseProject, exact: true })).toBeVisible();
  // A team-less project lives at the root, not indented under anything.
  await expect(sidebarRow(page, looseProject)).toHaveClass(/nav-depth-0/);

  // A ticket, in the sub-team, through the composer.
  await page.getByRole("button", { name: subTeam, exact: true }).click();
  await page.keyboard.press("c");
  const title = page.getByPlaceholder("New ticket…");
  await expect(title).toBeFocused();
  // The scope resolved the team: this is the `teams.data[0]` fallback, fixed. The
  // field is called "Ticket team", which is what removes the cross-region collision
  // with the sidebar's "New team" button at its source rather than in this locator.
  await expect(page.getByLabel("Ticket team")).not.toHaveValue("");
  await title.fill(ticket);
  await title.press("Enter");
  await expect(ticketRow(page, ticket)).toBeVisible();

  // And everything is findable in the sidebar.
  for (const name of [team, subTeam, teamProject, looseProject]) {
    await expect(page.getByRole("button", { name, exact: true })).toBeVisible();
  }
});

/**
 * Scenario 2. A parent team shows the work of its sub-teams; a project filters to
 * itself alone.
 */
test("scenario 2 — a parent team shows its sub-teams' tickets, a project filters to itself", async ({
  browser,
}) => {
  const api = await apiAs(ADMIN);

  const parent = await seedTeam(api, { name: unique("Parent"), key: uniqueKey() });
  const child = await seedTeam(api, {
    name: unique("Child"),
    key: uniqueKey(),
    parentTeamId: parent.id,
  });
  const project = await seedProject(api, { name: unique("Stream"), teamId: parent.id });

  const parentTicket = unique("Parent work");
  const childTicket = unique("Child work");
  const projectTicket = unique("Project work");

  await seedTicket(api, { teamId: parent.id, title: parentTicket });
  await seedTicket(api, { teamId: child.id, title: childTicket });
  await seedTicket(api, { teamId: parent.id, title: projectTicket, projectId: project.id });
  await api.dispose();

  const page = await openAs(browser, ADMIN);

  // The parent team: its own tickets and its sub-team's.
  await page.getByRole("button", { name: parent.name, exact: true }).click();
  await expect(ticketRow(page, parentTicket)).toBeVisible();
  await expect(ticketRow(page, childTicket)).toBeVisible();
  await expect(ticketRow(page, projectTicket)).toBeVisible();

  // The sub-team: its own only.
  await page.getByRole("button", { name: child.name, exact: true }).click();
  await expect(ticketRow(page, childTicket)).toBeVisible();
  await expect(ticketRow(page, parentTicket)).toHaveCount(0);

  // The project: its own only.
  await page.getByRole("button", { name: project.name, exact: true }).click();
  await expect(ticketRow(page, projectTicket)).toBeVisible();
  await expect(ticketRow(page, parentTicket)).toHaveCount(0);
  await expect(ticketRow(page, childTicket)).toHaveCount(0);
});
