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
 * Scenario 4. Deleting a team while keeping everything.
 *
 * The tree is grandparent → parent → sub-team so that the difference counts: the
 * sub-team must move up to the grandparent, not to the root, which is exactly where
 * the `ON DELETE SET NULL` cascade would have got it wrong.
 */
test("scenario 4 — deleting a team keeping everything re-homes and renumbers what it held", async ({
  browser,
}) => {
  const api = await apiAs(ADMIN);

  const grandParent = await seedTeam(api, { name: unique("Root"), key: uniqueKey() });
  const parent = await seedTeam(api, {
    name: unique("Doomed"),
    key: uniqueKey(),
    parentTeamId: grandParent.id,
  });
  const subTeam = await seedTeam(api, {
    name: unique("Survivor"),
    key: uniqueKey(),
    parentTeamId: parent.id,
  });
  const project = await seedProject(api, { name: unique("Kept"), teamId: parent.id });

  const movedTitle = unique("Moves and is renumbered");
  const moved = await seedTicket(api, { teamId: parent.id, title: movedTitle });
  const untouchedTitle = unique("Stays where it is");
  const untouched = await seedTicket(api, { teamId: subTeam.id, title: untouchedTitle });
  await api.dispose();

  expect(moved.identifier.startsWith(`${parent.key}-`)).toBeTruthy();

  const page = await openAs(browser, ADMIN);

  // The starting state, as the sidebar draws it.
  await expect(sidebarRow(page, parent.name)).toHaveClass(/nav-depth-1/);
  await expect(sidebarRow(page, subTeam.name)).toHaveClass(/nav-depth-2/);
  await expect(sidebarRow(page, project.name)).toHaveClass(/nav-depth-2/);

  await openRowMenu(page, parent.name);
  // The registry's label is "Delete team", not bare "Delete" — matched loosely, as
  // the interface contract promises: the tests do not depend on exact wording.
  await page.getByRole("menuitem", { name: /delete/i }).click();

  const dialog = page.getByRole("dialog");
  await expect(dialog).toBeVisible();

  // Everything defaults to "keep", in all three categories. Asserted by the choice's
  // own name: `.first()` would only say that whichever radio the markup happens to
  // put first is checked, which stays true if the two ever swap places.
  for (const noun of ["sub-teams", "projects", "tickets"]) {
    const group = dialog.getByRole("radiogroup", { name: noun });
    await expect(group.getByRole("radio", { name: /^Keep active/ })).toBeChecked();
    await expect(group.getByRole("radio", { name: /^Delete with it$/ })).not.toBeChecked();
  }

  // Kept tickets need a destination.
  await dialog.getByLabel("Destination team").selectOption({ label: grandParent.name });

  // The warning announces the prefixes, in this severity as in the other.
  const warning = page.locator(".disposition-warning");
  await expect(warning).toContainText(`${parent.key}-`);
  await expect(warning).toContainText(`${grandParent.key}-`);

  // Only the destructive side asks for the name, and it really asks: clicking without
  // having typed goes nowhere, and says so under the field.
  await dialog.getByRole("button", { name: "Delete" }).click();
  await expect(dialog).toBeVisible();
  await expect(dialog).toContainText(`Type the name exactly: ${parent.name}`);
  await expect(page.getByRole("button", { name: parent.name, exact: true })).toBeVisible();

  await dialog.getByLabel(/to confirm$/).fill(parent.name);
  await dialog.getByRole("button", { name: "Delete" }).click();
  await expect(page.getByRole("dialog")).toHaveCount(0);

  // The team is gone; what it held is still reachable, at its new place.
  await expect(page.getByRole("button", { name: parent.name, exact: true })).toHaveCount(0);
  await expect(sidebarRow(page, subTeam.name)).toHaveClass(/nav-depth-1/);
  await expect(sidebarRow(page, project.name)).toHaveClass(/nav-depth-1/);

  // The moved tickets carry the prefix the dialog announced.
  await page.getByRole("button", { name: grandParent.name, exact: true }).click();
  const movedRow = ticketRow(page, movedTitle);
  await expect(movedRow).toBeVisible();
  await expect(movedRow.getByTestId("row-id")).toContainText(`${grandParent.key}-`);
  await expect(movedRow.getByTestId("row-id")).not.toContainText(moved.identifier);

  // The ticket of a kept sub-team has not moved at all: no renumbering, identifier
  // untouched. This is the common case, and it has to stay free.
  const untouchedRow = ticketRow(page, untouchedTitle);
  await expect(untouchedRow).toBeVisible();
  await expect(untouchedRow.getByTestId("row-id")).toHaveText(untouched.identifier);
});
