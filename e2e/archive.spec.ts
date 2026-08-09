import { expect, test, type Locator } from "@playwright/test";
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

/** The category's row, addressed through the radiogroup it contains. */
function categoryRow(dialog: Locator, noun: string): Locator {
  return dialog
    .locator(".disposition-row")
    .filter({ has: dialog.page().getByRole("radiogroup", { name: noun }) });
}

/**
 * Scenario 6. The other verb of the disposition dialog, and the way back.
 *
 * Nothing in the suite had ever opened the dialog on `archive` or clicked "Show
 * archived", so the reversible half of the feature was drawn by hand and never seen.
 *
 * The tree is deliberately the shape the counting bug made unusable: a parent that
 * delegates its work, holding no ticket of its own while its sub-team holds two.
 * Under the default plan the parent's tickets are nobody's business — the sub-team
 * leaves intact with them — and the dialog must therefore show no ticket row at all.
 * Move the sub-teams into the operation and the same two tickets become exactly what
 * is being archived, so the row and its destination selector have to appear.
 */
test("scenario 6 — archiving a team counts what the plan reaches, and Show archived brings it back", async ({
  browser,
}) => {
  const api = await apiAs(ADMIN);

  const parent = await seedTeam(api, { name: unique("Delegating"), key: uniqueKey() });
  const child = await seedTeam(api, {
    name: unique("Doing"),
    key: uniqueKey(),
    parentTeamId: parent.id,
  });
  const project = await seedProject(api, { name: unique("Running"), teamId: parent.id });
  const firstTitle = unique("Work in the sub-team");
  const secondTitle = unique("More work in the sub-team");
  await seedTicket(api, { teamId: child.id, title: firstTitle });
  await seedTicket(api, { teamId: child.id, title: secondTitle });
  await api.dispose();

  const page = await openAs(browser, ADMIN);
  await expect(sidebarRow(page, child.name)).toHaveClass(/nav-depth-1/);

  await openRowMenu(page, parent.name);
  await page.getByRole("menuitem", { name: /^Archive team$/ }).click();

  const dialog = page.getByRole("dialog");
  await expect(dialog).toBeVisible();

  // The reversible verb: one button, no name to retype, and it says how to undo it.
  await expect(dialog.getByLabel(/to confirm$/)).toHaveCount(0);
  await expect(dialog).toContainText("Show archived");
  await expect(dialog.getByRole("button", { name: "Archive" })).toBeVisible();

  // What the default plan reaches: this team alone. Its sub-team and its project are
  // decisions to take; the sub-team's two tickets are not.
  await expect(categoryRow(dialog, "sub-teams").locator(".disposition-count")).toHaveText(
    "1 sub-teams",
  );
  await expect(categoryRow(dialog, "projects").locator(".disposition-count")).toHaveText(
    "1 projects",
  );
  await expect(dialog.getByRole("radiogroup", { name: "tickets" })).toHaveCount(0);
  await expect(dialog.getByLabel("Destination team")).toHaveCount(0);

  // Take the sub-teams and the same two tickets are on the table — with the selector
  // that decides where they go, which is what the 400 used to demand and no control
  // on screen could supply.
  await dialog
    .getByRole("radiogroup", { name: "sub-teams" })
    .getByRole("radio", { name: "Archive with it" })
    .check();
  await expect(categoryRow(dialog, "tickets").locator(".disposition-count")).toHaveText("2 tickets");
  await expect(dialog.getByLabel("Destination team")).toBeVisible();
  await expect(categoryRow(dialog, "sub-teams").locator(".disposition-count")).toHaveText(
    "1 sub-teams",
  );

  // Put it back: the numbers follow the choice in both directions.
  await dialog
    .getByRole("radiogroup", { name: "sub-teams" })
    .getByRole("radio", { name: /^Keep active/ })
    .check();
  await expect(dialog.getByRole("radiogroup", { name: "tickets" })).toHaveCount(0);

  await dialog.getByRole("button", { name: "Archive" }).click();
  await expect(page.getByRole("dialog")).toHaveCount(0);

  // Hidden, not destroyed. What it held stayed active and moved up.
  await expect(page.getByRole("button", { name: parent.name, exact: true })).toHaveCount(0);
  await expect(sidebarRow(page, child.name)).toHaveClass(/nav-depth-0/);
  await expect(sidebarRow(page, project.name)).toHaveClass(/nav-depth-0/);
  await expect(ticketRow(page, firstTitle)).toBeVisible();

  // Show archived is the only way back, and it is at the foot of the sidebar.
  const showArchived = page.getByRole("button", { name: "Show archived" });
  await expect(showArchived).toHaveAttribute("aria-pressed", "false");
  await showArchived.click();
  await expect(showArchived).toHaveAttribute("aria-pressed", "true");
  await expect(sidebarRow(page, parent.name)).toHaveAttribute("data-archived", "true");

  // And the row's menu offers the way back rather than the way out.
  const menu = await openRowMenu(page, parent.name);
  await expect(menu.getByRole("menuitem", { name: /^Archive team$/ })).toHaveCount(0);
  await menu.getByRole("menuitem", { name: /^Unarchive team$/ }).click();

  await expect(sidebarRow(page, parent.name)).toHaveAttribute("data-archived", "false");
  await showArchived.click();
  await expect(page.getByRole("button", { name: parent.name, exact: true })).toBeVisible();
});

/**
 * Scenario 7. The project side of the same two verbs, and the one error channel with
 * nowhere to report into.
 *
 * A project asks a single question — its tickets keep their team either way, so all
 * they can lose is the grouping. Unarchiving is run from a menu with no dialog behind
 * it and nothing optimistic to snap back, so its failures go to the top bar; that
 * line, its message and its dismiss button had never been rendered by a test.
 */
test("scenario 7 — archiving a project asks one question, and a failed unarchive says so in the top bar", async ({
  browser,
}) => {
  const api = await apiAs(ADMIN);

  const team = await seedTeam(api, { name: unique("Holder"), key: uniqueKey() });
  const project = await seedProject(api, { name: unique("Winding down"), teamId: team.id });
  const groupedTitle = unique("Grouped under the project");
  await seedTicket(api, { teamId: team.id, title: groupedTitle, projectId: project.id });
  await api.dispose();

  const page = await openAs(browser, ADMIN);

  await openRowMenu(page, project.name);
  await page.getByRole("menuitem", { name: /^Archive project$/ }).click();

  const dialog = page.getByRole("dialog");
  await expect(dialog).toBeVisible();
  // One row, and no destination: a ticket already has a team, so keeping one costs it
  // only its project. That is the whole difference between a project and a team.
  await expect(dialog.getByRole("radiogroup")).toHaveCount(1);
  await expect(categoryRow(dialog, "tickets").locator(".disposition-count")).toHaveText("1 tickets");
  await expect(dialog.getByLabel("Destination team")).toHaveCount(0);
  await expect(dialog.getByLabel(/to confirm$/)).toHaveCount(0);

  await dialog.getByRole("button", { name: "Archive" }).click();
  await expect(page.getByRole("dialog")).toHaveCount(0);
  await expect(page.getByRole("button", { name: project.name, exact: true })).toHaveCount(0);
  // The ticket kept its life and its team; only the grouping went.
  await page.getByRole("button", { name: team.name, exact: true }).click();
  await expect(ticketRow(page, groupedTitle)).toBeVisible();

  const showArchived = page.getByRole("button", { name: "Show archived" });
  await showArchived.click();
  await expect(sidebarRow(page, project.name)).toHaveAttribute("data-archived", "true");

  /**
   * The failure is injected at the network rather than provoked on the server. Every
   * server-side way to make this call fail — deleting the row out from under the open
   * page — races the realtime channel, which invalidates the list and removes the row
   * before it can be clicked. What is under test here is the client's error channel,
   * and this is the one way to exercise it without a coin flip. The body is the
   * RFC 7807 document `ApiExceptionHandler` really produces.
   */
  await page.route(`**/api/projects/${project.id}/unarchive`, (route) =>
    route.fulfill({
      status: 403,
      contentType: "application/problem+json",
      body: JSON.stringify({
        type: "about:blank",
        title: "Forbidden",
        status: 403,
        detail: "Only the owner or an admin can change teams",
      }),
    }),
  );

  await (await openRowMenu(page, project.name))
    .getByRole("menuitem", { name: /^Unarchive project$/ })
    .click();

  const topbarError = page.locator(".topbar-error");
  await expect(topbarError).toBeVisible();
  await expect(topbarError).toContainText("Only the owner or an admin can change teams");
  await expect(sidebarRow(page, project.name)).toHaveAttribute("data-archived", "true");

  await topbarError.getByRole("button", { name: "Dismiss this message" }).click();
  await expect(topbarError).toHaveCount(0);

  // And with the route released, the same menu entry works.
  await page.unroute(`**/api/projects/${project.id}/unarchive`);
  await (await openRowMenu(page, project.name))
    .getByRole("menuitem", { name: /^Unarchive project$/ })
    .click();
  await expect(sidebarRow(page, project.name)).toHaveAttribute("data-archived", "false");
  await expect(page.locator(".topbar-error")).toHaveCount(0);
});

/**
 * Scenario 8. Three things a `node` vitest run cannot see, and a browser can.
 *
 * Each is a fix whose whole effect is in the DOM — which list refetches, where the
 * focus lands, which handler answers a keystroke — so a suite with no rendering could
 * only assert that the code says what it says. Grouped in one test because they share
 * a stage, and each section is independent of the ones before it.
 */
test("scenario 8 — reparenting refreshes the list, a dialog gives the focus back, ⌘K stays out of the composer", async ({
  browser,
}) => {
  const api = await apiAs(ADMIN);

  const host = await seedTeam(api, { name: unique("Host"), key: uniqueKey() });
  const guest = await seedTeam(api, { name: unique("Guest"), key: uniqueKey() });
  const hostTitle = unique("Work already at the host");
  const guestTitle = unique("Work arriving with the guest");
  await seedTicket(api, { teamId: host.id, title: hostTitle });
  await seedTicket(api, { teamId: guest.id, title: guestTitle });
  await api.dispose();

  const page = await openAs(browser, ADMIN);

  // Scoped to Host, which shows its own work and its descendants'. Guest is a root
  // team, so its ticket is not here yet.
  await page.getByRole("button", { name: host.name, exact: true }).click();
  await expect(ticketRow(page, hostTitle)).toBeVisible();
  await expect(ticketRow(page, guestTitle)).toHaveCount(0);

  // --- the list follows the tree ------------------------------------------------
  // Moving Guest under Host changes what Host's `includeDescendants` list contains.
  // Nothing in the realtime channel says so — a team event invalidates teams — so
  // the dialog has to invalidate the ticket lists itself, or this stays wrong for
  // the client-wide 30s `staleTime`.
  await (await openRowMenu(page, guest.name)).getByRole("menuitem", { name: /^Rename team$/ }).click();
  const teamDialog = page.getByRole("dialog");
  await teamDialog.getByLabel("Parent team").selectOption({ label: host.name });
  await teamDialog.getByRole("button", { name: "Save" }).click();
  await expect(page.getByRole("dialog")).toHaveCount(0);

  await expect(sidebarRow(page, guest.name)).toHaveClass(/nav-depth-1/);
  // No reload anywhere in this test: the list is refetched or it is not.
  await expect(ticketRow(page, guestTitle)).toBeVisible();

  // --- the focus comes back -----------------------------------------------------
  // A dialog that takes the focus and never gives it back leaves a keyboard user on
  // `<body>`, with the next Tab starting again from the top of the document. The
  // menu refocuses its trigger before running an entry, so there is something still
  // mounted for the dialog to return to.
  const trigger = page.getByRole("button", { name: `Actions for ${guest.name}`, exact: true });
  await (await openRowMenu(page, guest.name)).getByRole("menuitem", { name: /^Rename team$/ }).click();
  await expect(page.getByRole("dialog")).toBeVisible();
  await page.keyboard.press("Escape");
  await expect(page.getByRole("dialog")).toHaveCount(0);
  await expect(trigger).toBeFocused();

  // --- ⌘K does not reach behind the composer ------------------------------------
  // The title input stops propagation; its four `<select>`s do not. Handled ahead of
  // the overlay guard, ⌘K from one of them opened the palette over the composer and
  // threw the typed title away.
  await page.keyboard.press("c");
  const title = page.getByPlaceholder("New ticket…");
  await expect(title).toBeFocused();
  const draft = unique("A title worth not losing");
  await title.fill(draft);
  await page.keyboard.press("Tab");
  await expect(page.getByLabel("Ticket team")).toBeFocused();

  await page.keyboard.press("ControlOrMeta+k");
  await expect(page.getByPlaceholder("Type a command…")).toHaveCount(0);
  await expect(title).toHaveValue(draft);

  // Still the same composer, and it still files the ticket it was holding.
  await title.press("Enter");
  await expect(page.getByPlaceholder("New ticket…")).toHaveCount(0);
  await expect(ticketRow(page, draft)).toBeVisible();
});
